import Foundation
import UIKit
import AuthenticationServices
import CryptoKit
import Security
import FirebaseCore
import FirebaseAuth
import GoogleSignIn
import WidgetKit
// The KMP framework. Needed for `UserSyncBridge.tearDownDeletedAccount()` —
// signing out of a DELETED account has to tear down the Kotlin-owned state
// (SQLite boards, widget, topics, per-account settings) as well as the Firebase
// session, and that half lives on the other side of this bridge.
import composeApp

/// Swift-side Firebase Auth adapter.
///
/// KMP writes `auth_pending_command` to NSUserDefaults; this class observes
/// that key via UserDefaults.didChangeNotification and dispatches the
/// corresponding Firebase call. On completion it either stores
/// `firebase_auth_token` (success) or `auth_pending_error` (failure), and
/// clears `auth_pending_command`.  The KMP IosPlatformAuthProvider polls for
/// these keys every 250 ms (up to 15 s).
///
/// Command format:  "<verb>|<arg1>|<arg2>"
///   signIn|<email>|<password>
///   register|<email>|<password>
///   googleSignIn|<idToken>
///   googleSignInInteractive
///   appleSignInInteractive
///   resetConfirm|<oobCode>|<newPassword>
class AuthBridge {
    static let shared = AuthBridge()
    private init() {}

    private var observerAdded = false

    // Retains the one-shot Apple coordinator while its sheet is up — the
    // ASAuthorizationController delegate is weak, so without this the flow
    // would silently die the moment the local variable went out of scope.
    private var appleCoordinator: AppleSignInCoordinator?

    /// Whether a sign-out THIS APP asked for is in progress.
    ///
    /// The one fact the auth-state listener cannot work out for itself — see
    /// `settleAuthState`. Set by every deliberate teardown (`logout`, the
    /// `signOut` command, account deletion, a confirmed gone session); cleared
    /// the moment it is honoured or a user appears.
    private var expectingSignOut = false

    private var commandInFlight = false
    /// When a KMP-issued auth command last started or finished.
    private var authCommandTouchedAt: Date = .distantPast

    /// Whether the Compose login flow is driving auth right now — or was, just
    /// now.
    ///
    /// Read by `ContentView` to tell a session Firebase RESTORED from one the
    /// Compose login flow just performed. The two need opposite treatment: a
    /// restore under a login screen has to rebuild the Compose host, while
    /// rebuilding mid-login throws away the flow's own navigation.
    ///
    /// The trailing window is not slack, it is the ordering. `storeUserInfo`
    /// fires the auth-state listener, whose notification reaches SwiftUI on a
    /// LATER main-queue turn than `markDone()` — so a plain in-flight boolean is
    /// already false by the time the sign-in is observed, and the guard would
    /// miss the only case it exists for.
    var isHandlingAuthCommand: Bool {
        commandInFlight || Date().timeIntervalSince(authCommandTouchedAt) < 10
    }

    /// Whether this device holds a session.
    ///
    /// `Auth.currentUser` is authoritative and is believed — it reads through
    /// `kAuthGlobalWorkQueue.sync`, so unlike the listener's argument it waits
    /// for the Keychain restore instead of racing it.
    ///
    /// ## The one case it is wrong about, and how we know we are in it
    /// Firebase cannot read the Keychain before the device's first unlock, or
    /// during an iOS prewarm launch. It gives up, registers for
    /// `protectedDataDidBecomeAvailable` and retries later — so `currentUser` is
    /// nil for a live account, and stays nil for as long as the phone stays
    /// locked. That is not a moment of uncertainty this app can wait out:
    /// `startLoggedIn` is read ONCE, and `AppNavigation` turns it into a start
    /// destination fixed for the life of the Compose host.
    ///
    /// `isProtectedDataAvailable` is the same condition Firebase itself tests
    /// (see the `keychainError` branch of `Auth.protectedDataInitialization`),
    /// so it identifies exactly that case and nothing else.
    ///
    /// ## Why the stored token is NOT a general fallback
    /// Because it outlives the thing it stands for. Trusting it whenever
    /// `currentUser` was nil produced a device that looked signed in, rendered
    /// a "?" avatar and a "User" name, and could not repair itself —
    /// `refreshTokenIfNeeded` returns early with no `currentUser`, so nothing
    /// would ever fetch the identity it was missing. A signed-out app that says
    /// so is strictly better than a signed-in one that cannot say who.
    var hasSession: Bool {
        if FirebaseApp.app() == nil { return false }
        if Auth.auth().currentUser != nil { return true }
        guard !UIApplication.shared.isProtectedDataAvailable else { return false }
        return UserDefaults.standard.string(forKey: "firebase_auth_token")?.isEmpty == false
    }

    // MARK: - KMP wiring

    func wireToKMP() {
        guard !observerAdded else { return }
        observerAdded = true

        // React to every NSUserDefaults change; filter for auth_pending_command inside
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleUserDefaultsChange),
            name: UserDefaults.didChangeNotification,
            object: nil
        )

        // Refresh token whenever Firebase auth state changes (only if Firebase is configured)
        guard FirebaseApp.app() != nil else { return }
        // The listener's own `user` argument is deliberately DISCARDED — see
        // `settleAuthState` for why it cannot be trusted at launch.
        Auth.auth().addStateDidChangeListener { [weak self] _, _ in
            self?.settleAuthState()
        }
    }

    /// Decide what a Firebase auth-state change actually means, and act on it.
    ///
    /// ## Why the listener's `user` argument is thrown away
    /// Because it is read without synchronisation and can be `nil` on a session
    /// that is perfectly alive. FirebaseAuth loads the Keychain user
    /// ASYNCHRONOUSLY (`Auth.protectedDataInitialization` → `kAuthGlobalWorkQueue.async`),
    /// while the listener's first invocation is `DispatchQueue.main.async { listener(self, self._currentUser) }`
    /// — the raw ivar, off the main queue, with no barrier between the two. At a
    /// cold launch those race, and the listener loses often enough to matter.
    ///
    /// `Auth.currentUser` is the same value read through `kAuthGlobalWorkQueue.sync`,
    /// so it BLOCKS until the restore has landed and cannot report a session
    /// that exists as absent. Safe to call from here: every invocation path runs
    /// on the main queue (the initial `main.async` above, and a notification
    /// observer registered with `queue: .main`), never on Firebase's work queue.
    ///
    /// ## What that nil used to cost
    /// `clearUserInfo()`, unconditionally — which deletes the token and all
    /// eight identity keys. Measured on device: a healthy account came back
    /// from a launch holding a token issued minutes earlier and NOT ONE identity
    /// key, because the wipe landed between `persistUserIdentity` and the token
    /// write inside an in-flight `refreshTokenIfNeeded`. With `firebase_user_uid`
    /// gone, KMP falls back to its `anon` namespace (`UserSettings.NO_USER`) and
    /// the user's boards, layout and per-account settings all resolve to a
    /// stranger's — the app "logging itself out" roughly an hour after last use,
    /// which is how long iOS takes to evict it and force the next cold launch.
    ///
    /// ## Only a sign-out we asked for may clear
    /// The failure directions are not symmetric. Holding stale identity for one
    /// more launch is invisible; discarding a live one takes the user's account
    /// away in front of them. So a `nil` we did not ask for is treated as "not
    /// restored yet" and changed nothing — Firebase fires again the moment it
    /// resolves, including after `protectedDataDidBecomeAvailable` on a device
    /// that was locked when we launched.
    ///
    /// Genuine endings all set [expectingSignOut] first: `logout()`, the KMP
    /// `signOut` command, account deletion, and the confirmed-gone branch of
    /// `refreshTokenIfNeeded`.
    private func settleAuthState() {
        if let user = Auth.auth().currentUser {
            expectingSignOut = false
            // Persist identity SYNCHRONOUSLY, before the async token fetch.
            // Firebase restores sessions from the keychain — which survives
            // an app delete/reinstall while NSUserDefaults does not — so
            // without this re-write the app launched "logged in" but with
            // every identity key missing: the home avatar showed "?" and
            // the profile "User" / "Stationly" / "Since Recently" until
            // the next explicit sign-in.
            persistUserIdentity(user)
            Task { await self.refreshTokenIfNeeded() }
        } else if expectingSignOut {
            expectingSignOut = false
            clearUserInfo()
        } else {
            // ── A nil nobody asked for ──
            //
            // Traced rather than silent: this branch is the whole fix, and a
            // launch that hits it must be distinguishable from one that never
            // saw a nil.
            PushTraceSwift.log("auth nil we didn't ask for — session kept")
            print("[AuthBridge] Unrequested nil auth state — credentials kept")

            // Credentials are NOT deleted here, and the distinction is the
            // point. `currentUser` reading nil through the barrier is good
            // evidence there is no session — but it is not proof, because a
            // Keychain read can fail for reasons that have nothing to do with
            // whether an account exists. Measured during this session: a process
            // launched by `devicectl` cannot read the Keychain at all, so
            // Firebase reported no user seconds after a successful sign-in on a
            // device that was, in fact, signed in.
            //
            // So the two consequences are split by how much it costs to be
            // wrong:
            //
            //  - Blanking the WIDGET is cheap and self-reversing — the next
            //    `persistUserIdentity` lowers the flag again — and it is the
            //    only way a sign-out Firebase performed ITSELF
            //    (`User.signOutIfTokenIsInvalid`, which never reaches our
            //    `logout()`) can reach the home screen. Worth acting on the
            //    weaker signal.
            //  - Deleting the token and identity is not reversible by anything
            //    short of the user signing in again, so it needs the strong
            //    signal below.
            if UIApplication.shared.isProtectedDataAvailable {
                setWidgetSignedOut(true)
                WidgetCenter.shared.reloadAllTimelines()
            }
            discardTornIdentity()
        }
        // Posted on EVERY branch, including the one that changed nothing.
        //
        // `ContentView` re-reads `hasSession` here, and that answer is not the
        // same as "did this method act". A sign-out Firebase performs on its own
        // — `User.signOutIfTokenIsInvalid`, on a revoked or deleted account —
        // arrives as exactly this unrequested nil, and it is a REAL ending that
        // the UI has to be told about. Returning early instead left the app on
        // the home screen of an account that no longer existed, which is the
        // "credentials gone, interface unchanged" failure the `.id` rebuild was
        // added for in the first place.
        //
        // The listener cannot tell that apart from a Keychain that could not be
        // read, and it does not have to: `hasSession` distinguishes them by
        // `isProtectedDataAvailable`, so the unreadable case re-reports `true`
        // from the stored token and nothing moves.
        NotificationCenter.default.post(name: .authStateDidChange, object: nil)
    }

    // MARK: - Command dispatch

    @objc private func handleUserDefaultsChange() {
        let ud = UserDefaults.standard
        guard let command = ud.string(forKey: "auth_pending_command") else { return }

        // Clear immediately to prevent double-processing on repeated notifications
        ud.removeObject(forKey: "auth_pending_command")
        ud.removeObject(forKey: "auth_pending_error")
        ud.removeObject(forKey: "auth_operation_success")
        ud.removeObject(forKey: "auth_command_done")
        ud.synchronize()

        let parts = command.components(separatedBy: "|")
        guard let verb = parts.first else { return }

        // Raised for the whole life of the command, lowered in `markDone()`.
        // Its only reader is `ContentView`, which must not rebuild the Compose
        // host for a sign-in the Compose login flow is in the middle of doing.
        commandInFlight = true
        authCommandTouchedAt = Date()

        Task {
            switch verb {
            case "signIn" where parts.count >= 3:
                let r = await signInWithEmail(email: parts[1], password: parts[2])
                if case .failure(let e) = r { writeError(e.localizedDescription) }

            case "register" where parts.count >= 3:
                let r = await registerWithEmail(email: parts[1], password: parts[2])
                if case .failure(let e) = r { writeError(e.localizedDescription) }

            case "googleSignIn" where parts.count >= 2:
                let r = await signInWithGoogleIdToken(idToken: parts[1])
                if case .failure(let e) = r { writeError(e.localizedDescription) }

            case "googleSignInInteractive":
                guard let rootVC = await rootViewController() else {
                    writeError("No root view controller available")
                    markDone()
                    return
                }
                let r = await signInWithGoogle(presentingViewController: rootVC)
                if case .failure(let e) = r { writeError(e.localizedDescription) }

            case "appleSignInInteractive":
                let r = await signInWithApple()
                if case .failure(let e) = r { writeError(e.localizedDescription) }

            case "resetConfirm" where parts.count >= 3:
                let r = await confirmPasswordReset(oobCode: parts[1], newPassword: parts[2])
                switch r {
                case .success:
                    // No token issued for reset; signal KMP via dedicated key
                    ud.set("1", forKey: "auth_operation_success")
                    ud.synchronize()
                case .failure(let e):
                    writeError(e.localizedDescription)
                }

            case "updateDisplayName" where parts.count >= 2:
                // Name may legitimately contain "|" — rejoin everything after the verb.
                let r = await updateDisplayName(parts.dropFirst().joined(separator: "|"))
                switch r {
                case .success:
                    ud.set("1", forKey: "auth_operation_success")
                    ud.synchronize()
                case .failure(let e):
                    writeError(e.localizedDescription)
                }

            case "signOut":
                await logout()
                ud.set("1", forKey: "auth_operation_success")
                ud.synchronize()

            case "sendEmailVerification":
                guard let user = Auth.auth().currentUser else {
                    writeError("No user signed in")
                    markDone()
                    return
                }
                do {
                    try await user.sendEmailVerification()
                    ud.set("1", forKey: "auth_operation_success")
                    ud.synchronize()
                } catch {
                    writeError(error.localizedDescription)
                }

            case "reloadUser":
                guard let user = Auth.auth().currentUser else {
                    writeError("No user signed in")
                    markDone()
                    return
                }
                do {
                    try await user.reload()
                    persistUserIdentity(Auth.auth().currentUser ?? user)
                    ud.set("1", forKey: "auth_operation_success")
                    ud.synchronize()
                } catch {
                    writeError(error.localizedDescription)
                }

            default:
                writeError("Unknown command: \(verb)")
            }

            // Completion flag — the LAST write for every command. KMP waits on
            // this key, not on the command key (cleared above before the async
            // work even starts). Without it, an interactive Google sign-in
            // "failed" on KMP's first 250 ms poll while the user was still
            // picking an account in the Google sheet.
            markDone()
        }
    }

    private func markDone() {
        commandInFlight = false
        authCommandTouchedAt = Date()
        UserDefaults.standard.set("1", forKey: "auth_command_done")
        UserDefaults.standard.synchronize()
    }

    private func writeError(_ message: String) {
        UserDefaults.standard.set(message, forKey: "auth_pending_error")
        UserDefaults.standard.synchronize()
    }

    @MainActor
    private func rootViewController() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController
    }

    // MARK: - Email / password

    func signInWithEmail(email: String, password: String) async -> Result<String, Error> {
        do {
            let result = try await Auth.auth().signIn(withEmail: email, password: password)
            let token  = try await result.user.getIDToken()
            storeUserInfo(user: result.user, token: token)
            return .success(token)
        } catch { return .failure(error) }
    }

    func registerWithEmail(email: String, password: String) async -> Result<String, Error> {
        do {
            let result = try await Auth.auth().createUser(withEmail: email, password: password)
            let token  = try await result.user.getIDToken()
            storeUserInfo(user: result.user, token: token)
            return .success(token)
        } catch { return .failure(error) }
    }

    // MARK: - Google Sign-In

    /// Exchange an idToken (already obtained by the Compose Google Sign-In layer)
    /// for a Firebase credential and session.
    private func signInWithGoogleIdToken(idToken: String) async -> Result<String, Error> {
        do {
            let accessToken = GIDSignIn.sharedInstance.currentUser?.accessToken.tokenString ?? ""
            let credential  = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: accessToken)
            let authResult  = try await Auth.auth().signIn(with: credential)
            let token       = try await authResult.user.getIDToken()
            storeUserInfo(user: authResult.user, token: token)
            return .success(token)
        } catch { return .failure(error) }
    }

    /// Full interactive Google Sign-In flow; caller provides a presenting view controller.
    /// Called from the native Compose Google Sign-In button handler (iOS only).
    func signInWithGoogle(presentingViewController: UIViewController) async -> Result<String, Error> {
        do {
            let result    = try await GIDSignIn.sharedInstance.signIn(withPresenting: presentingViewController)
            guard let idToken = result.user.idToken?.tokenString else {
                return .failure(AuthBridgeError.missingIDToken)
            }
            return await signInWithGoogleIdToken(idToken: idToken)
        } catch { return .failure(error) }
    }

    // MARK: - Sign in with Apple

    /// Native ASAuthorization flow → Firebase `apple.com` OAuth credential,
    /// mirroring the Google interactive path above.
    ///
    /// LIVE since 2026-07-25. `com.apple.developer.applesignin` was absent
    /// until then because Apple forbids the capability on personal (free)
    /// development teams — the same wall that kept push disabled — so the
    /// sheet failed immediately and `friendlyAppleError` surfaced an "isn't
    /// available" banner. The entitlement shipped with the Stationly Limited
    /// team (7T7D5LLYSL) and this code needed no change. The friendly mapping
    /// stays as the genuine-failure path (cancelled, no network, …).
    ///
    /// Apple only returns `fullName`/`email` on the FIRST authorization for
    /// an Apple ID — `OAuthProvider.appleCredential(fullName:)` forwards the
    /// name so Firebase can seed the user's displayName on that first pass.
    func signInWithApple() async -> Result<String, Error> {
        let rawNonce = AppleSignInCoordinator.randomNonceString()
        let coordinator = AppleSignInCoordinator()
        appleCoordinator = coordinator
        defer { appleCoordinator = nil }
        do {
            let authorization = try await coordinator.authorize(
                hashedNonce: AppleSignInCoordinator.sha256(rawNonce)
            )
            guard
                let appleCredential = authorization.credential as? ASAuthorizationAppleIDCredential,
                let tokenData = appleCredential.identityToken,
                let idToken = String(data: tokenData, encoding: .utf8)
            else {
                return .failure(AuthBridgeError.missingAppleToken)
            }
            let credential = OAuthProvider.appleCredential(
                withIDToken: idToken,
                rawNonce: rawNonce,
                fullName: appleCredential.fullName
            )
            let authResult = try await Auth.auth().signIn(with: credential)
            let token = try await authResult.user.getIDToken()
            storeUserInfo(user: authResult.user, token: token)
            return .success(token)
        } catch {
            return .failure(Self.friendlyAppleError(error))
        }
    }

    /// ASAuthorization errors are opaque codes ("error 1000") — translate the
    /// ones the user can actually hit into copy the KMP error banner can show
    /// verbatim. Firebase errors pass through with their own message.
    private static func friendlyAppleError(_ error: Error) -> Error {
        guard let asError = error as? ASAuthorizationError else { return error }
        switch asError.code {
        case .canceled: return AuthBridgeError.appleCancelled
        // .unknown (1000) = missing entitlement or no iCloud session;
        // .failed/.invalidResponse/.notHandled are equally unactionable.
        default:        return AuthBridgeError.appleUnavailable
        }
    }

    // MARK: - Display name

    func updateDisplayName(_ name: String) async -> Result<Void, Error> {
        guard let user = Auth.auth().currentUser else {
            return .failure(AuthBridgeError.notSignedIn)
        }
        do {
            let change = user.createProfileChangeRequest()
            change.displayName = name
            try await change.commitChanges()
            UserDefaults.standard.set(name, forKey: "firebase_user_display_name")
            UserDefaults.standard.synchronize()
            return .success(())
        } catch { return .failure(error) }
    }

    // MARK: - Password reset

    func confirmPasswordReset(oobCode: String, newPassword: String) async -> Result<Void, Error> {
        do {
            try await Auth.auth().confirmPasswordReset(withCode: oobCode, newPassword: newPassword)
            return .success(())
        } catch { return .failure(error) }
    }

    // MARK: - Sign-out

    func logout() async {
        // BEFORE the Firebase call, because that call is what makes the listener
        // fire with nil — and `settleAuthState` will not clear anything unless
        // this says the ending is ours. Every deliberate teardown reaches
        // Firebase through here, so this one line covers all of them.
        expectingSignOut = true
        // Forget that this device was ever registered for push.
        //
        // FIRST, and unconditionally, because everything below it can fail and
        // this must not be skipped when something does. The backend deletes this
        // device's row as part of the sign-out, so a cached "already registered"
        // signature would suppress the re-registration on the next sign-in and
        // leave the device with no push address at all. See
        // `DevicePushCoordinator.release()` for the full sequence.
        DevicePushCoordinator.release()
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
        clearUserInfo()
        // ── Tell the WIDGET, which is a different process ──
        //
        // It holds its own copy of the board and can refill it without us: its
        // timeline fetches over REST with the API key, for a station named in an
        // AppIntent configuration nothing here can erase. So deleting data is a
        // sign-out it undoes within a couple of minutes — see
        // `AppGroupKeys.widgetSignedOut`.
        //
        // Raised HERE as well as in KMP's `clearWidgetData` because this is the
        // last step of every teardown, whichever order the two halves run in:
        // `ProfileViewModel.signOut` calls this BEFORE `cleanupAll()`, while
        // `signOutForAccountDeletion` calls it AFTER. One of those would
        // otherwise leave the flag lowered by whatever ran last.
        setWidgetSignedOut(true)
        // And reload, so the boards go blank now rather than whenever WidgetKit
        // next gets round to them. Free: reloads requested while the app is
        // foreground are exempt from the timeline budget.
        WidgetCenter.shared.reloadAllTimelines()
        NotificationCenter.default.post(name: .authStateDidChange, object: nil)
    }

    /// Throw away a token that has no identity beside it.
    ///
    /// ## Only this direction, and the asymmetry is the whole correctness
    /// A TOKEN WITH NO UID cannot be produced by any correct ordering. Both
    /// paths that write a token write the identity first or alongside it —
    /// `storeUserInfo` sets the token and then calls [persistUserIdentity], and
    /// `refreshTokenIfNeeded` calls [persistUserIdentity] before it even asks
    /// for one — while [clearUserInfo] removes all nine together. So this shape
    /// is debris, and it is exactly the debris the pre-fix build left behind:
    /// `clearUserInfo` landing inside an in-flight `refreshTokenIfNeeded`, whose
    /// token write then resurrected the one key it had just removed.
    ///
    /// **A UID WITH NO TOKEN is NOT checked, because it is ordinary.**
    /// [persistUserIdentity] writes the uid and does *not* write a token —
    /// `settleAuthState` calls it synchronously and only then hops to
    /// `refreshTokenIfNeeded` for the token. Any moment between those two, and
    /// any refresh that fails offline, leaves a perfectly healthy session in
    /// precisely that shape. Treating the mismatch symmetrically would delete
    /// the credentials of a signed-in user for being briefly half-written,
    /// which is the failure this whole method exists to avoid.
    ///
    /// Safe to act on the weak signal for the one direction that remains, since
    /// a healthy session can never look like it. And worth acting on, because
    /// the torn state is silently sticky: `IosPlatformAuthProvider.isLoggedIn()`
    /// answers from the token alone, so KMP treats the device as signed in while
    /// every identity read comes back nil — the profile renders a skeleton that
    /// resolves to "?" and "User", and `StationlyAuth` attaches a dead bearer to
    /// every `/user/*` call.
    private func discardTornIdentity() {
        let ud = UserDefaults.standard
        guard ud.string(forKey: "firebase_auth_token")?.isEmpty == false,
              ud.string(forKey: "firebase_user_uid")?.isEmpty != false
        else { return }
        PushTraceSwift.log("token with no identity — discarded")
        print("[AuthBridge] Discarding orphaned token (no uid beside it)")
        clearUserInfo()
    }

    /// The App Group half of "is anyone signed in", for the widget extension.
    ///
    /// A separate suite from everything else this class writes: the extension
    /// cannot see `UserDefaults.standard`, which is where the token and the
    /// identity keys live.
    private func setWidgetSignedOut(_ signedOut: Bool) {
        guard let group = UserDefaults(suiteName: AppGroupID.value) else { return }
        if signedOut {
            group.set(true, forKey: AppGroupKeys.widgetSignedOut)
        } else {
            // Removed rather than set to false, so "absent" stays the single
            // meaning of signed-in — a device that has never signed out has no
            // flag either, and the extension must read both the same way.
            group.removeObject(forKey: AppGroupKeys.widgetSignedOut)
        }
        group.synchronize()
    }

    /// Sign out because the account was deleted somewhere else.
    ///
    /// Reached from a `user.sync` push with `reason = "deleted"` — the user
    /// deleted their account on another device, and this one is holding a
    /// session for something that no longer exists. Android does the same via
    /// `UserSyncCoordinator.forceLogout`.
    ///
    /// A session for a deleted account has to be torn down exactly as thoroughly
    /// as one the user ended deliberately — and until now it was not. This used
    /// to be a bare `await logout()`, which is only the SWIFT half: Firebase
    /// out, Google out, identity keys cleared. Everything Kotlin owns — the
    /// boards in SQLite, the widget, the topic subscriptions, the per-account
    /// settings — was left exactly as it was, because on the deliberate path
    /// that work lives in `ProfileViewModel.signOut` and nothing here called the
    /// equivalent.
    ///
    /// What that produced is the confusing part: a device with no credentials
    /// and a completely working app. Browsing, refreshing and the departure
    /// stream all keep going, because they authenticate with the API KEY rather
    /// than the user's token and the boards render from local SQLite. Nothing
    /// looks wrong, so nothing suggests the account is gone.
    ///
    /// Order is load-bearing: the Kotlin teardown reads the uid from storage
    /// that `logout()` wipes.
    func signOutForAccountDeletion() async {
        _ = try? await UserSyncBridge.shared.tearDownDeletedAccount()
        await logout()
    }

    // MARK: - Token refresh

    /// Refresh the ID token on foreground — and, in doing so, find out whether
    /// this account still exists.
    ///
    /// ## This is no longer what keeps requests authenticated
    /// It used to be, by accident, and that was the bug. `Platform.getAuthToken()`
    /// on iOS was one read of `firebase_auth_token`, so the freshness of every
    /// `/user/*` call depended entirely on this method having run recently
    /// enough — and nothing sequenced it before an outbound request. A phone left
    /// alone for an hour sent a dead bearer, the backend answered 401, and the
    /// 401 handler signed the user out. `AppDelegate.handleDidBecomeActive`
    /// starts this and the activity upload in two unordered tasks and the upload
    /// usually won; the 03:00 `BGProcessingTask` never ran this at all.
    ///
    /// The request path now resolves its own token through
    /// `IosAuthTokenAuthority` (see the resolver below), which is the same
    /// arrangement Android has always had. So this method keeps ONE job, and it
    /// is the job the section below describes: asking Google whether the account
    /// is still there. Do not "simplify" it into the resolver — the resolver
    /// deliberately does not force a refresh, and something has to.
    ///
    /// ## Why the refresh is FORCED
    /// `getIDToken()` returns the cached token until it is within ~5 minutes of
    /// expiring, so for most of an hour it answers without asking Firebase
    /// anything. That made this the wrong kind of quiet: an account deleted on
    /// another device left this one running normally for the rest of the token's
    /// life, and it genuinely could not tell — measured, on a real deletion.
    ///
    /// Nothing else was going to notice either. Browsing the app touches
    /// `/stations/*`, `/sdui/*` and the departure stream, all authenticated by
    /// the API KEY rather than the user's token, and the boards render from local
    /// SQLite. A signed-out-but-not-yet-aware device therefore has a complete,
    /// working app and no reason to contact `/user/*` at all — the only route by
    /// which the backend could have told it otherwise.
    ///
    /// `forcingRefresh: true` asks Google directly, on every foreground. It costs
    /// our backend nothing — it is not our endpoint — and it is authoritative:
    /// a deleted user, a disabled one, or one whose tokens were revoked all fail
    /// here immediately.
    ///
    /// ## Only a GONE session signs out
    /// The distinction is the whole safety of doing this. A refresh failing
    /// because the device is on a train with no signal must change nothing;
    /// failing because the user no longer exists must end the session. Treating
    /// the two alike would sign people out every time they went into a tunnel —
    /// which, for this app, is most of the time.
    func refreshTokenIfNeeded() async {
        guard let user = Auth.auth().currentUser else { return }
        // Re-persist identity on every refresh too — this runs on each
        // app foreground (handleDidBecomeActive), making it the ongoing
        // self-heal for any state where the token exists but the identity
        // keys were lost.
        persistUserIdentity(user)
        do {
            let token = try await user.getIDToken(forcingRefresh: true)
            // Dropped rather than written if the session ended while this was in
            // flight — the guard, and the torn state it prevents, are documented
            // once on `persistFetchedToken`.
            guard await persistFetchedToken(token) else { return }
        } catch {
            if Self.isSessionGone(error) {
                PushTraceSwift.log("token refresh → account gone, signing out")
                print("[AuthBridge] Account no longer exists — signing out")
                await logout()
                return
            }
            // Offline, or Firebase having a moment. Keep the session: the stored
            // token is still valid for now, and the next foreground tries again.
            print("[AuthBridge] Token refresh failed (kept session): \(error.localizedDescription)")
        }
    }

    /// Write a freshly fetched token, unless the session ended while it was in
    /// flight.
    ///
    /// ## The race this refuses, in one place instead of two
    /// Fetching a token is a network round trip, and a sign-out landing inside
    /// it finds the token already fetched and about to be written — so the write
    /// resurrects a key `clearUserInfo()` has just removed, and leaves the exact
    /// torn state measured on device: a valid token with no uid, no email and no
    /// provider beside it. Nothing repairs that, because every reader of the
    /// identity is looking at keys that are gone while every check for "is there
    /// a session" sees a token that is there. See `discardTornIdentity`.
    ///
    /// The activity log dated it to the second: the last event stamped with a
    /// uid at 15:24:53, and the token that wrote it issued at 15:24:54 — with no
    /// `auth.logged_out` anywhere in the table.
    ///
    /// Re-checked rather than captured up front: what matters is the state NOW,
    /// after the await, not when the fetch started.
    ///
    /// Extracted because there are now TWO fetchers. `refreshTokenIfNeeded` runs
    /// on foreground; the resolver below runs whenever the shared network layer
    /// needs a bearer, which is on background-task threads the foreground path
    /// never touches. A guard that only one of them honoured would reopen the
    /// race on the other.
    ///
    /// - Returns: whether the token was written. False means it was dropped
    ///   because the session is over, and the caller should treat it as no token.
    /// Not `private`: `AuthTokenResolver` is a separate type and fetches tokens
    /// too, so it needs the same guard. Nothing outside this file should call it.
    ///
    /// `@MainActor` because it now has a genuinely concurrent caller. Every other
    /// reader and writer of [expectingSignOut] runs on the main queue — the
    /// auth-state listener, the command dispatcher, `logout()` — but the resolver
    /// is driven by the shared Ktor client, which calls it from whatever
    /// background thread a request happens to be on. Reading the flag from there
    /// unsynchronised is a data race on the one piece of state that decides
    /// whether a token may be written at all, and getting it wrong reintroduces
    /// exactly the torn identity this guard exists to prevent.
    ///
    /// The hop costs nothing where it is paid: crossings are rare by design (see
    /// `IosAuthTokenAuthority`), and the caller is already suspended awaiting a
    /// network round trip.
    @MainActor
    @discardableResult
    func persistFetchedToken(_ token: String) -> Bool {
        guard !expectingSignOut, Auth.auth().currentUser != nil else {
            print("[AuthBridge] Token arrived after sign-out — dropped")
            return false
        }
        UserDefaults.standard.set(token, forKey: "firebase_auth_token")
        UserDefaults.standard.synchronize()
        return true
    }

    /// Whether an auth error means "this session is over", as opposed to "ask
    /// again later".
    ///
    /// Listed explicitly rather than inverting a network check, so a code that
    /// is not understood is treated as retryable. The failure directions are not
    /// symmetric: missing a deletion for one more foreground is a small bug,
    /// while signing a user out on an unrecognised transient error is a large
    /// one they experience as the app losing their account.
    private static func isSessionGone(_ error: Error) -> Bool {
        guard let code = AuthErrorCode(rawValue: (error as NSError).code) else { return false }
        switch code {
        case .userNotFound,      // deleted, on this or another device
             .userDisabled,      // disabled in the console
             .userTokenExpired,  // refresh tokens revoked — what deletion does first
             .invalidUserToken:
            return true
        default:
            return false
        }
    }

    // MARK: - NSUserDefaults persistence (read by KMP IosPlatformAuthProvider)

    private func storeUserInfo(user: FirebaseAuth.User, token: String) {
        UserDefaults.standard.set(token, forKey: "firebase_auth_token")
        persistUserIdentity(user)
    }

    /// Everything KMP reads about WHO is signed in, minus the token. Written
    /// on explicit sign-in, on every auth-state restore and on every token
    /// refresh — the keys must survive reinstalls that wipe NSUserDefaults
    /// but not the Firebase keychain session.
    private func persistUserIdentity(_ user: FirebaseAuth.User) {
        // Somebody is signed in — Firebase produced an actual user, which is the
        // only evidence that counts. Lowered here rather than on a board write:
        // this runs on sign-in, on every keychain restore and on every token
        // refresh, so it cannot be missed by a user who signs back in with no
        // boards saved, and it cannot be triggered by a stray write from a
        // request still in flight when the session ended.
        setWidgetSignedOut(false)

        let ud = UserDefaults.standard
        ud.set(user.email,                    forKey: "firebase_user_email")
        ud.set(user.displayName,              forKey: "firebase_user_display_name")
        ud.set(user.photoURL?.absoluteString, forKey: "firebase_user_photo_url")
        ud.set(user.uid,                      forKey: "firebase_user_uid")
        ud.set(user.isEmailVerified,          forKey: "firebase_user_email_verified")
        
        let hasPasswordProvider = user.providerData.contains { $0.providerID == "password" }
        ud.set(hasPasswordProvider,           forKey: "firebase_user_is_email_provider")

        // Provider badge label for ProfileScreen
        let rawProvider = user.providerData
            .first(where: { $0.providerID != "firebase" })?.providerID ?? "password"
        ud.set(rawProvider == "google.com" ? "Google" :
               rawProvider == "apple.com"  ? "Apple"  : "Email",
               forKey: "signin_provider")

        // No `member_since` any more — the profile card's "Since …" chip was the
        // only reader and it is gone (2026-08-23). It stays in `clearUserInfo`
        // below so devices that already wrote it are swept on the next sign-out.
        ud.synchronize()

        // ── Re-register for push, because a sign-in is the ONLY moment that
        //    reliably needs it and the only one nothing was watching ──
        //
        // The pair to `DevicePushCoordinator.release()` in `logout()`. Sign-out
        // deletes this device's row on the backend; sign-in recreates it through
        // `startSession` and deliberately writes NO token fields, because only
        // `/device/register` may write those. So between those two events the
        // device is signed in, visible in its account, and completely
        // unreachable — a state that produces no error anywhere, because a
        // token-less device is not a failure, it simply never matches an
        // audience.
        //
        // Nothing was closing that window. `register()` has three call sites —
        // the APNs token callback (launch only), `didBecomeActive`, and an
        // account change inside that same foreground handler — and a sign-out
        // followed by a sign-in without backgrounding the app hits NONE of them.
        // MEASURED on the connected iPhone: after `POST /user/logout` the server
        // log showed the sign-in's `/user/sync/profile` and not one
        // `/device/register`, and the recreated row had no `appToken`,
        // `widgetToken` or `environment`.
        //
        // Clearing the stale signature in `logout()` was necessary and is not
        // sufficient: it makes the NEXT registration go through instead of being
        // elided as unchanged, but something still has to ask for one.
        //
        // Cheap to call from here even though this also runs on every auth-state
        // restore and every token refresh: `register()` skips a POST whose body
        // is unchanged, so after the first one following a session change every
        // other call costs a dictionary comparison. That is exactly the division
        // of labour the signature cache is for — `release()` decides when it is
        // stale, this decides when to ask.
        DevicePushCoordinator.register()
    }

    private func clearUserInfo() {
        ["firebase_auth_token", "firebase_user_email", "firebase_user_display_name",
         "firebase_user_photo_url", "firebase_user_uid", "signin_provider", "member_since",
         "firebase_user_email_verified", "firebase_user_is_email_provider"]
            .forEach { UserDefaults.standard.removeObject(forKey: $0) }
        UserDefaults.standard.synchronize()
        // KMP holds the last token in memory as well, so that the request path
        // does not have to read defaults — see `IosAuthTokenAuthority`. Removing
        // the key without this would leave that copy serving the ended session's
        // bearer to every request until it aged out, and then to whoever signed
        // in next. The nine keys above are only half the state now.
        AuthTokenBridge.shared.invalidate()
    }
}

// MARK: - Test control

#if DEBUG
/// Forces the state the auto-logout bug needed, so it can be reproduced on
/// demand instead of by waiting an hour.
///
/// The bug is only reachable with a token that is SYNTACTICALLY valid and
/// EXPIRED: a malformed one fails a different way (`IosAuthTokenAuthority`
/// treats an unparseable token as "refresh now", and the backend rejects it
/// before the revocation check), and an absent one just sends no header. So the
/// control mints a real three-segment JWT whose payload is a plausible Firebase
/// claim set with `exp` in the past, and overwrites `firebase_auth_token` with
/// it.
///
/// The signature is deliberately GARBAGE. Nothing on the device verifies it —
/// the authority reads the `exp` claim and the backend is the only verifier —
/// and a control that could mint a signable token would be a control that could
/// mint credentials.
///
/// `#if DEBUG` because it exists to break a working session on purpose. It ships
/// in no release build.
enum AuthTestControls {

    /// Overwrite the stored token with an expired one, and drop the in-memory
    /// copy so the next request cannot answer from it.
    ///
    /// Both halves are needed. Writing the key alone reproduces nothing once
    /// `IosAuthTokenAuthority` is holding a live token in memory — which is the
    /// point of that cache, and would make the test silently pass.
    @discardableResult
    static func forceExpiredToken() -> String {
        let uid = UserDefaults.standard.string(forKey: "firebase_user_uid") ?? "test-uid"
        let issued = Int(Date().timeIntervalSince1970) - 7200
        let expired = issued + 3600            // an hour of life, spent an hour ago
        let header = #"{"alg":"RS256","typ":"JWT"}"#
        let payload = """
        {"iss":"https://securetoken.google.com/stationly","aud":"stationly",\
        "auth_time":\(issued),"user_id":"\(uid)","sub":"\(uid)",\
        "iat":\(issued),"exp":\(expired)}
        """
        let token = [base64url(header), base64url(payload), "c3RhbGUtc2lnbmF0dXJl"]
            .joined(separator: ".")
        UserDefaults.standard.set(token, forKey: "firebase_auth_token")
        UserDefaults.standard.synchronize()
        AuthTokenBridge.shared.invalidate()
        PushTraceSwift.log("TEST forced expired token exp=\(expired)")
        return token
    }

    /// The `exp` claim of whatever is stored, for comparing before and after a
    /// refresh. Returns 0 if there is no token or it does not parse.
    static func storedTokenExpiry() -> Int {
        guard let token = UserDefaults.standard.string(forKey: "firebase_auth_token") else { return 0 }
        let parts = token.split(separator: ".")
        guard parts.count == 3 else { return 0 }
        var b64 = String(parts[1]).replacingOccurrences(of: "-", with: "+")
                                  .replacingOccurrences(of: "_", with: "/")
        b64 += String(repeating: "=", count: (4 - b64.count % 4) % 4)
        guard let data = Data(base64Encoded: b64),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let exp = object["exp"] as? Int
        else { return 0 }
        return exp
    }

    private static func base64url(_ s: String) -> String {
        Data(s.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Phase 1: prove a `/user/*` request repairs its own stale bearer.
    ///
    /// ## Why it refreshes FIRST, before expiring anything
    /// Because otherwise the test races the app. Any route that reaches this
    /// control is a foreground — a deep link makes the scene active — so
    /// `handleDidBecomeActive` has already started `refreshTokenIfNeeded()` in a
    /// detached task. Expiring the token straight away would leave two writers
    /// with no ordering between them, and a passing run would not distinguish
    /// "the request refreshed it" from "the foreground refresh happened to land
    /// after". Awaiting the refresh first drains that writer, so the expiry that
    /// follows is the last word and the only thing that can undo it is the
    /// request itself.
    ///
    /// The endpoint is `/user/activity/batch` deliberately: it is the exact call
    /// that both the foreground upload and the 03:00 `BGProcessingTask` make,
    /// and the one the old skip list did not exempt.
    static func staleTokenRequest() async {
        // Both this and `handleDidBecomeActive` call `refreshTokenIfNeeded()`,
        // and awaiting ours says nothing about whether the other has finished.
        // A refresh landing AFTER the expiry below would rewrite a fresh token
        // and the test would pass without the request having done anything.
        // The settle wait closes that; `crossings` closes it properly, since a
        // crossing can only be made by the resolver and the resolver is only
        // called from the Kotlin request path — `refreshTokenIfNeeded` talks to
        // Firebase directly and never touches it.
        await AuthBridge.shared.refreshTokenIfNeeded()
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        await enqueueOneEvent()
        forceExpiredToken()
        let before = storedTokenExpiry()
        let crossingsBefore = AuthTokenBridge.shared.crossings()
        let uploaded = (try? await ActivityBridge.shared.uploadActivity())?.boolValue ?? false
        let after = storedTokenExpiry()
        let crossingsAfter = AuthTokenBridge.shared.crossings()
        PushTraceSwift.log(
            "TEST stale-request before=\(before) after=\(after) " +
            "advanced=\(after > before) uploaded=\(uploaded) " +
            "crossings=\(crossingsBefore)->\(crossingsAfter)")
    }

    /// Run a command left in the App Group container, if there is one.
    ///
    /// ## Why a FILE and not a deep link
    /// The deep link below works, but only from something that will hand a
    /// custom scheme to the system — Safari's address bar treats
    /// `stationly-staging://…` as a search term and Notes will not linkify it,
    /// so on this device it needs the Shortcuts app to fire at all. That makes
    /// every test step depend on the person holding the phone getting a fiddly
    /// bit of UI right.
    ///
    /// A file can be dropped from the Mac with
    /// `devicectl device copy to --domain-type appGroupDataContainer`, which is
    /// the same channel the traces are pulled back through, so the whole test
    /// becomes: drop the command, ask the user to open the app, read the ring.
    ///
    /// Consumed on read — deleted before the work starts, so a command cannot
    /// re-run on the next foreground if the app is killed mid-test.
    static func runPendingCommand() {
        guard let root = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: AppGroupID.value) else { return }
        let file = root.appendingPathComponent("stationly_debug_command")
        guard let raw = try? String(contentsOf: file, encoding: .utf8) else { return }
        try? FileManager.default.removeItem(at: file)
        let action = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        PushTraceSwift.log("TEST command=\(action)")
        Task {
            switch action {
            case "expire-token":  forceExpiredToken()
            case "stale-request": await staleTokenRequest()
            case "fast-path":     await fastPathBurst()
            default:              PushTraceSwift.log("TEST unknown command=\(action)")
            }
        }
    }

    /// Put one row in the activity queue and wait for it to land.
    ///
    /// `ActivityUploader.flush` returns without making any request when the
    /// queue is empty, so without this the "upload" under test would be a no-op
    /// and the test would pass by never issuing the request it is measuring.
    /// `ActivityLog.record` is fire-and-forget onto its own scope, hence the
    /// wait rather than a plain call.
    private static func enqueueOneEvent() async {
        ActivityBridge.shared.appOpened(cold: false)
        try? await Task.sleep(nanoseconds: 400_000_000)
    }

    /// Phase 1, second half: prove the fast path does not cross.
    ///
    /// Ten `/user/*` calls on a token minted moments ago must move the crossing
    /// counter by zero. A cache that refetches per request would look identical
    /// from the outside — same responses, same timings on wifi — and would put a
    /// language boundary and a Firebase round trip in front of every call in the
    /// app. This is the only way to see it.
    static func fastPathBurst() async {
        await AuthBridge.shared.refreshTokenIfNeeded()
        let before = AuthTokenBridge.shared.crossings()
        var posted = 0
        for _ in 0..<10 {
            await enqueueOneEvent()
            if (try? await ActivityBridge.shared.uploadActivity())?.boolValue == true { posted += 1 }
        }
        let after = AuthTokenBridge.shared.crossings()
        PushTraceSwift.log(
            "TEST fast-path posted=\(posted) crossings=\(before)->\(after) delta=\(after - before)")
    }
}
#endif

// MARK: - Token resolver for the shared network layer

/// Answers "what bearer should this request carry?" for KMP.
///
/// ## Why this exists on the Swift side
/// FirebaseAuth is a Swift-only SDK — Kotlin cannot call `Auth.auth()` at all —
/// while the place a token is actually needed is the shared Ktor client, which
/// is Kotlin. Registered once at launch through `AuthTokenBridge`; the Kotlin
/// half and the caching in front of it are documented on
/// `IosAuthTokenAuthority`.
///
/// ## Why `getIDToken()` and not `getIDToken(forcingRefresh: true)`
/// Because the SDK's own judgement is the thing worth having, and forcing
/// overrides it. Unforced, it answers from its cache while the token has
/// comfortable life left and goes to Google only when it does not — which is
/// exactly the contract `Platform.getAuthToken()` now states, and exactly what
/// Android's `getIdToken(false)` has always done. Forcing here would put a
/// network round trip in front of every `/user/*` request in the app, to replace
/// a token that was going to work.
///
/// The one caller that DOES force is the 401 retry, and it says so:
/// `Platform.refreshAuthToken()` arrives here with `forceRefresh = true`.
/// `refreshTokenIfNeeded` forces too, for a different reason — it is not after a
/// token, it is after an answer about whether the account still exists.
///
/// ## What it deliberately does not do
/// It never signs anybody out, on any error. A `getIDToken` failure here is
/// reported as nil and nothing else, even for `userNotFound` — which this can
/// see, and `isSessionGone` would classify as final. Ending a session from a
/// background-task thread, on the strength of one failed call made while nobody
/// is looking at the app, is the exact asymmetry `settleAuthState` argues
/// against: a missed deletion costs one more foreground, and a wrong sign-out
/// costs the user their account in front of them. `refreshTokenIfNeeded` makes
/// that call on the next foreground, deliberately, with the user present.
final class AuthTokenResolver: NSObject, IosAuthTokenResolver {

    func resolveToken(forceRefresh: Bool, completion: @escaping (String?) -> Void) {
        // Firebase may not be configured — a test build with no plist, or a
        // launch that failed configuration. `Auth.auth()` traps in that case, so
        // this is a guard against a crash and not tidiness.
        guard FirebaseApp.app() != nil, let user = Auth.auth().currentUser else {
            completion(nil)
            return
        }
        // The async variant, matching `refreshTokenIfNeeded`. One spelling of
        // "fetch a token" in this file, so the two cannot drift.
        Task {
            guard let token = try? await user.getIDToken(forcingRefresh: forceRefresh) else {
                completion(nil)
                return
            }
            // Written through the same guard as every other token this app
            // fetches, so a sign-out landing inside the fetch cannot be undone
            // by it — see `persistFetchedToken`. The key still has to be written:
            // `IosPlatformAuthProvider.isLoggedIn()`, `hasSession`'s
            // protected-data fallback and `DevicePushCoordinator` all read it,
            // and none of them can see the Kotlin cache.
            //
            // If the write is refused the session is over, so the token is not
            // handed back either — a request authenticated as a user who has
            // just signed out is worse than an unauthenticated one.
            guard await AuthBridge.shared.persistFetchedToken(token) else {
                completion(nil)
                return
            }
            completion(token)
        }
    }
}

// MARK: - Apple Sign-In coordinator

/// One-shot ASAuthorizationController wrapper bridging the delegate callbacks
/// into async/await. NSObject because both controller protocols require it;
/// a fresh instance per attempt (retained by AuthBridge.appleCoordinator)
/// keeps the continuation single-use by construction.
final class AppleSignInCoordinator: NSObject,
                                    ASAuthorizationControllerDelegate,
                                    ASAuthorizationControllerPresentationContextProviding {

    private var continuation: CheckedContinuation<ASAuthorization, Error>?

    @MainActor
    func authorize(hashedNonce: String) async throws -> ASAuthorization {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = hashedNonce
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        return try await withCheckedThrowingContinuation { cont in
            continuation = cont
            controller.performRequests()
        }
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        continuation?.resume(returning: authorization)
        continuation = nil
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithError error: Error) {
        continuation?.resume(throwing: error)
        continuation = nil
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }

    // MARK: Nonce helpers (the standard Firebase Apple-sign-in recipe: send
    // SHA256(nonce) to Apple, hand the raw nonce to Firebase for replay
    // protection)

    static func randomNonceString(length: Int = 32) -> String {
        precondition(length > 0)
        var randomBytes = [UInt8](repeating: 0, count: length)
        let errorCode = SecRandomCopyBytes(kSecRandomDefault, randomBytes.count, &randomBytes)
        precondition(errorCode == errSecSuccess, "Unable to generate nonce. SecRandomCopyBytes failed with \(errorCode)")
        let charset: [Character] = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        return String(randomBytes.map { charset[Int($0) % charset.count] })
    }

    static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

// MARK: - Errors

enum AuthBridgeError: LocalizedError {
    case missingIDToken
    case notSignedIn
    case missingAppleToken
    case appleCancelled
    case appleUnavailable
    var errorDescription: String? {
        switch self {
        case .missingIDToken:    return "Google Sign-In did not return an ID token."
        case .notSignedIn:       return "Not signed in."
        case .missingAppleToken: return "Apple Sign-In did not return an identity token."
        case .appleCancelled:    return "Sign-in was cancelled."
        case .appleUnavailable:  return "Sign in with Apple isn't available right now. Please use Google or email instead."
        }
    }
}

extension Notification.Name {
    static let authStateDidChange = Notification.Name("authStateDidChange")
}
