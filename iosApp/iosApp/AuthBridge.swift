import Foundation
import FirebaseAuth
import GoogleSignIn
// import ComposeApp  // Uncomment after Xcode integration

/// AuthBridge is the Swift-side adapter between Firebase Auth / Google Sign-In
/// and the KMP `AuthCallbackBridge`. It also writes auth state into
/// NSUserDefaults so that the KMP IosPlatformAuthProvider can read it.
class AuthBridge {
    static let shared = AuthBridge()
    private init() {}

    // MARK: - KMP wiring

    func wireToKMP() {
        // After Xcode integration, uncomment and implement each closure to
        // forward calls from the KMP ViewModel layer to the Swift implementations
        // below. Example pattern:
        //
        // AuthCallbackBridge.shared.signInWithEmailImpl = { [weak self] email, password in
        //     return await self?.signInWithEmail(email: email, password: password) ?? .failure(makeError("Bridge deallocated"))
        // }
        // AuthCallbackBridge.shared.registerWithEmailImpl = { [weak self] email, password in
        //     return await self?.registerWithEmail(email: email, password: password) ?? .failure(makeError("Bridge deallocated"))
        // }
        // AuthCallbackBridge.shared.signInWithGoogleImpl = { [weak self] vc in
        //     return await self?.signInWithGoogle(presentingViewController: vc) ?? .failure(makeError("Bridge deallocated"))
        // }
        // AuthCallbackBridge.shared.logoutImpl = { [weak self] in
        //     await self?.logout()
        // }
        // AuthCallbackBridge.shared.refreshTokenImpl = { [weak self] in
        //     await self?.refreshTokenIfNeeded()
        // }
        // AuthCallbackBridge.shared.confirmPasswordResetImpl = { [weak self] oobCode, newPassword in
        //     return await self?.confirmPasswordReset(oobCode: oobCode, newPassword: newPassword) ?? .failure(makeError("Bridge deallocated"))
        // }

        // Observe Firebase auth state changes and broadcast to the rest of the app
        Auth.auth().addStateDidChangeListener { [weak self] _, user in
            if let user = user {
                Task {
                    await self?.refreshTokenIfNeeded()
                }
            } else {
                self?.clearUserInfo()
            }
            NotificationCenter.default.post(name: .authStateDidChange, object: nil)
        }
    }

    // MARK: - Email/Password auth

    func signInWithEmail(email: String, password: String) async -> Result<String, Error> {
        do {
            let result = try await Auth.auth().signIn(withEmail: email, password: password)
            let token = try await result.user.getIDToken()
            storeUserInfo(user: result.user, token: token)
            return .success(token)
        } catch {
            return .failure(error)
        }
    }

    func registerWithEmail(email: String, password: String) async -> Result<String, Error> {
        do {
            let result = try await Auth.auth().createUser(withEmail: email, password: password)
            let token = try await result.user.getIDToken()
            storeUserInfo(user: result.user, token: token)
            return .success(token)
        } catch {
            return .failure(error)
        }
    }

    // MARK: - Google Sign-In

    func signInWithGoogle(presentingViewController: UIViewController) async -> Result<String, Error> {
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presentingViewController)
            guard let idToken = result.user.idToken?.tokenString else {
                return .failure(AuthBridgeError.missingIDToken)
            }
            let credential = GoogleAuthProvider.credential(
                withIDToken: idToken,
                accessToken: result.user.accessToken.tokenString
            )
            let authResult = try await Auth.auth().signIn(with: credential)
            let token = try await authResult.user.getIDToken()
            storeUserInfo(user: authResult.user, token: token)
            return .success(token)
        } catch {
            return .failure(error)
        }
    }

    // MARK: - Password reset

    func confirmPasswordReset(oobCode: String, newPassword: String) async -> Result<Void, Error> {
        do {
            try await Auth.auth().confirmPasswordReset(withCode: oobCode, newPassword: newPassword)
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    // MARK: - Sign-out

    func logout() async {
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
        clearUserInfo()
        // KMP layer reads the absence of firebase_auth_token to treat user as logged out
        UserDefaults.standard.removeObject(forKey: "firebase_auth_token")
        NotificationCenter.default.post(name: .authStateDidChange, object: nil)
    }

    // MARK: - Token refresh

    func refreshTokenIfNeeded() async {
        guard let user = Auth.auth().currentUser else { return }
        do {
            let token = try await user.getIDToken(forcingRefresh: false)
            UserDefaults.standard.set(token, forKey: "firebase_auth_token")
        } catch {
            print("[AuthBridge] Token refresh failed: \(error.localizedDescription)")
        }
    }

    // MARK: - UserDefaults persistence (read by KMP IosPlatformAuthProvider)

    private func storeUserInfo(user: FirebaseAuth.User, token: String) {
        let defaults = UserDefaults.standard
        defaults.set(token,                          forKey: "firebase_auth_token")
        defaults.set(user.email,                     forKey: "firebase_user_email")
        defaults.set(user.displayName,               forKey: "firebase_user_display_name")
        defaults.set(user.photoURL?.absoluteString,  forKey: "firebase_user_photo_url")
        defaults.set(user.uid,                       forKey: "firebase_user_uid")
        defaults.synchronize()
    }

    private func clearUserInfo() {
        let keys = [
            "firebase_auth_token",
            "firebase_user_email",
            "firebase_user_display_name",
            "firebase_user_photo_url",
            "firebase_user_uid"
        ]
        keys.forEach { UserDefaults.standard.removeObject(forKey: $0) }
        UserDefaults.standard.synchronize()
    }
}

// MARK: - Errors

enum AuthBridgeError: LocalizedError {
    case missingIDToken

    var errorDescription: String? {
        switch self {
        case .missingIDToken:
            return "Google Sign-In did not return an ID token."
        }
    }
}
