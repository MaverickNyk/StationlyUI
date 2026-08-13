import Foundation
import UIKit
import AuthenticationServices
import CryptoKit
import Security
import FirebaseCore
import FirebaseAuth
import GoogleSignIn
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
        Auth.auth().addStateDidChangeListener { [weak self] _, user in
            guard let self else { return }
            if let user {
                // Persist identity SYNCHRONOUSLY, before the async token fetch.
                // Firebase restores sessions from the keychain — which survives
                // an app delete/reinstall while NSUserDefaults does not — so
                // without this re-write the app launched "logged in" but with
                // every identity key missing: the home avatar showed "?" and
                // the profile "User" / "Stationly" / "Since Recently" until
                // the next explicit sign-in.
                self.persistUserIdentity(user)
                Task { await self.refreshTokenIfNeeded() }
            } else {
                self.clearUserInfo()
            }
            NotificationCenter.default.post(name: .authStateDidChange, object: nil)
        }
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
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
        clearUserInfo()
        NotificationCenter.default.post(name: .authStateDidChange, object: nil)
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
            UserDefaults.standard.set(token, forKey: "firebase_auth_token")
            UserDefaults.standard.synchronize()
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

        // "Member since Month YYYY" label
        if let created = user.metadata.creationDate {
            let fmt = DateFormatter()
            fmt.dateFormat = "MMMM yyyy"
            fmt.locale = Locale(identifier: "en_GB")
            ud.set(fmt.string(from: created), forKey: "member_since")
        }
        ud.synchronize()
    }

    private func clearUserInfo() {
        ["firebase_auth_token", "firebase_user_email", "firebase_user_display_name",
         "firebase_user_photo_url", "firebase_user_uid", "signin_provider", "member_since",
         "firebase_user_email_verified", "firebase_user_is_email_provider"]
            .forEach { UserDefaults.standard.removeObject(forKey: $0) }
        UserDefaults.standard.synchronize()
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
