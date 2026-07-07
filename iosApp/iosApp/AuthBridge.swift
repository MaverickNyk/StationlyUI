import Foundation
import UIKit
import FirebaseCore
import FirebaseAuth
import FirebaseMessaging
import GoogleSignIn
// import ComposeApp  // Uncomment after Xcode framework integration

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
///   resetConfirm|<oobCode>|<newPassword>
class AuthBridge {
    static let shared = AuthBridge()
    private init() {}

    private var observerAdded = false

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
                // Unsubscribe from all active FCM topics directly — cannot rely on the
                // pending-unsubscribe queue because KMP clears UserDefaults immediately after.
                if let topics = ud.array(forKey: "fcm_topics") as? [String] {
                    topics.forEach { Messaging.messaging().unsubscribe(fromTopic: $0) { _ in } }
                }
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

    // MARK: - Token refresh

    func refreshTokenIfNeeded() async {
        guard let user = Auth.auth().currentUser else { return }
        // Re-persist identity on every refresh too — this runs on each
        // app foreground (handleDidBecomeActive), making it the ongoing
        // self-heal for any state where the token exists but the identity
        // keys were lost.
        persistUserIdentity(user)
        do {
            let token = try await user.getIDToken()
            UserDefaults.standard.set(token, forKey: "firebase_auth_token")
            UserDefaults.standard.synchronize()
        } catch {
            print("[AuthBridge] Token refresh failed: \(error.localizedDescription)")
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

// MARK: - Errors

enum AuthBridgeError: LocalizedError {
    case missingIDToken
    case notSignedIn
    var errorDescription: String? {
        switch self {
        case .missingIDToken: return "Google Sign-In did not return an ID token."
        case .notSignedIn:    return "Not signed in."
        }
    }
}

extension Notification.Name {
    static let authStateDidChange = Notification.Name("authStateDidChange")
}
