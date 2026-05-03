import SwiftUI
import FirebaseAuth
// import ComposeApp  // Uncomment after Xcode framework integration

struct ContentView: View {
    @State private var isLoggedIn: Bool = Auth.auth().currentUser != nil
    @State private var deepLinkOobCode: String? = nil

    var body: some View {
        ComposeHostView(startLoggedIn: isLoggedIn, deepLinkOobCode: deepLinkOobCode)
            .ignoresSafeArea()
            .onReceive(NotificationCenter.default.publisher(for: .authStateDidChange)) { _ in
                isLoggedIn = Auth.auth().currentUser != nil
            }
            // Password reset deep link: store oobCode so KMP can navigate to reset-confirm
            .onReceive(NotificationCenter.default.publisher(for: .passwordResetLink)) { notification in
                deepLinkOobCode = notification.object as? String
            }
    }
}

/// Wraps the KMP Compose UI inside a UIViewControllerRepresentable.
///
/// After Xcode framework integration:
///   1. Uncomment `import ComposeApp` at the top.
///   2. Replace the placeholder UIViewController body in makeUIViewController with:
///        return MainViewControllerKt.MainViewController(
///            startLoggedIn: startLoggedIn,
///            deepLinkOobCode: deepLinkOobCode
///        )
///   3. In updateUIViewController, no action needed — KMP NavHost handles navigation internally.
struct ComposeHostView: UIViewControllerRepresentable {
    let startLoggedIn: Bool
    let deepLinkOobCode: String?

    func makeUIViewController(context: Context) -> UIViewController {
        // ─── REPLACE THIS with the real KMP entry point after framework integration ───
        // return MainViewControllerKt.MainViewController(
        //     startLoggedIn: startLoggedIn,
        //     deepLinkOobCode: deepLinkOobCode
        // )
        let vc = UIViewController()
        vc.view.backgroundColor = .black
        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
