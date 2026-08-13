import SwiftUI
import FirebaseCore
import FirebaseAuth
import composeApp

struct ContentView: View {
    @AppStorage("app_theme") private var appTheme: String = "system"
    @State private var isLoggedIn: Bool = (FirebaseApp.app() != nil) ? (Auth.auth().currentUser != nil) : false
    @State private var deepLinkOobCode: String? = nil
    /// Bumped on every logged-in → logged-out transition, to force a fresh
    /// Compose host. See the `.id` on the body.
    @State private var signedOutGeneration: Int = 0

    private var colorScheme: ColorScheme? {
        switch appTheme {
        case "dark": return .dark
        case "light": return .light
        default: return nil
        }
    }

    var body: some View {
        ComposeHostView(startLoggedIn: isLoggedIn, deepLinkOobCode: deepLinkOobCode)
            // ── Rebuild the Compose host when the session ENDS ──
            //
            // `startLoggedIn` is read once, in `makeUIViewController`, and
            // `updateUIViewController` is a no-op — so a sign-out that did not
            // originate inside Compose never reached the UI. The app kept showing
            // the home screen of an account that no longer existed, which is
            // exactly how a force-logout looked on device: credentials gone,
            // interface unchanged.
            //
            // ── The counter ALONE, never `isLoggedIn` ──
            //
            // This must be stable across a sign-IN. An earlier version read
            // `isLoggedIn ? "session" : "signed-out-\(n)"`, which changes on BOTH
            // transitions — so signing in tore down and rebuilt the whole Compose
            // host mid-login. That threw away the login flow's own navigation,
            // re-ran the tree against state the loader had just finished
            // populating, and crashed outright once there was more than one board
            // to rebuild.
            //
            // The counter only moves on logout, so login leaves the id untouched
            // and Compose keeps handling that direction itself — which it always
            // did correctly.
            .id(signedOutGeneration)
            .ignoresSafeArea()
            .preferredColorScheme(colorScheme)
            .onReceive(NotificationCenter.default.publisher(for: .authStateDidChange)) { _ in
                let nowLoggedIn = (FirebaseApp.app() != nil) ? (Auth.auth().currentUser != nil) : false
                // Bump BEFORE flipping the flag, so the rebuilt host is a fresh
                // instance rather than one SwiftUI can match to the old id.
                if isLoggedIn && !nowLoggedIn { signedOutGeneration += 1 }
                isLoggedIn = nowLoggedIn
            }
            .onReceive(NotificationCenter.default.publisher(for: .passwordResetLink)) { notification in
                deepLinkOobCode = notification.object as? String
            }
    }
}

struct ComposeHostView: UIViewControllerRepresentable {
    let startLoggedIn: Bool
    let deepLinkOobCode: String?

    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController(
            startLoggedIn: startLoggedIn,
            deepLinkOobCode: deepLinkOobCode
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
