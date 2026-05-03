import SwiftUI
import FirebaseAuth
// import ComposeApp  // Uncomment after Xcode framework integration

struct ContentView: View {
    @State private var isLoggedIn: Bool = Auth.auth().currentUser != nil

    var body: some View {
        ComposeHostView(startLoggedIn: isLoggedIn)
            .ignoresSafeArea()
            // When AuthBridge fires authStateDidChange, re-evaluate login status.
            // The KMP NavHost already handles the transition internally; this just
            // ensures the root wrapper stays in sync for future cold-start checks.
            .onReceive(NotificationCenter.default.publisher(for: .authStateDidChange)) { _ in
                isLoggedIn = Auth.auth().currentUser != nil
            }
    }
}

/// Wraps the KMP Compose UI inside a UIViewControllerRepresentable.
///
/// After Xcode framework integration:
///   1. Uncomment `import ComposeApp` at the top.
///   2. Replace the placeholder body with:
///        return MainViewControllerKt.MainViewController(startLoggedIn: startLoggedIn)
struct ComposeHostView: UIViewControllerRepresentable {
    let startLoggedIn: Bool

    func makeUIViewController(context: Context) -> UIViewController {
        // ─── REPLACE THIS with the real KMP entry point after framework integration ───
        // return MainViewControllerKt.MainViewController(startLoggedIn: startLoggedIn)
        let vc = UIViewController()
        vc.view.backgroundColor = .black
        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
