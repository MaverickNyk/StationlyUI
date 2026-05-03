import Foundation
import UIKit
import FirebaseAuth
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

        // Refresh token whenever Firebase auth state changes
        Auth.auth().addStateDidChangeListener { [weak self] _, user in
            guard let self else { return }
            if user != nil {
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

            case "signOut":
                await logout()
                ud.set("1", forKey: "auth_operation_success")
                ud.synchronize()

            default:
                writeError("Unknown command: \(verb)")
            }
        }
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
        do {
            let token = try await user.getIDToken(forcingRefresh: false)
            UserDefaults.standard.set(token, forKey: "firebase_auth_token")
            UserDefaults.standard.synchronize()
        } catch {
            print("[AuthBridge] Token refresh failed: \(error.localizedDescription)")
        }
    }

    // MARK: - NSUserDefaults persistence (read by KMP IosPlatformAuthProvider)

    private func storeUserInfo(user: FirebaseAuth.User, token: String) {
        let ud = UserDefaults.standard
        ud.set(token,                         forKey: "firebase_auth_token")
        ud.set(user.email,                    forKey: "firebase_user_email")
        ud.set(user.displayName,              forKey: "firebase_user_display_name")
        ud.set(user.photoURL?.absoluteString, forKey: "firebase_user_photo_url")
        ud.set(user.uid,                      forKey: "firebase_user_uid")

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
         "firebase_user_photo_url", "firebase_user_uid", "signin_provider", "member_since"]
            .forEach { UserDefaults.standard.removeObject(forKey: $0) }
        UserDefaults.standard.synchronize()
    }
}

// MARK: - Errors

enum AuthBridgeError: LocalizedError {
    case missingIDToken
    var errorDescription: String? { "Google Sign-In did not return an ID token." }
}

extension Notification.Name {
    static let authStateDidChange = Notification.Name("authStateDidChange")
}
