import SwiftUI
import FirebaseAuth
// import ComposeApp  // Uncomment after Xcode framework integration

struct ContentView: View {
    @State private var isLoggedIn: Bool = Auth.auth().currentUser != nil

    var body: some View {
        // ComposeView wraps the KMP Compose Multiplatform UI.
        // After Xcode framework integration replace ComposeHostView with:
        //
        //   ComposeViewController(startLoggedIn: isLoggedIn)
        //     .ignoresSafeArea()
        //
        ComposeHostView(startLoggedIn: isLoggedIn)
            .ignoresSafeArea()
            .onReceive(NotificationCenter.default.publisher(for: .authStateDidChange)) { _ in
                isLoggedIn = Auth.auth().currentUser != nil
            }
    }
}

// MARK: - ComposeHostView
// UIViewControllerRepresentable that wraps MainViewController from the KMP
// composeApp framework. Until the Xcode project is fully configured this
// returns a plain black UIViewController as a placeholder.

struct ComposeHostView: UIViewControllerRepresentable {
    let startLoggedIn: Bool

    func makeUIViewController(context: Context) -> UIViewController {
        // After Xcode integration, replace the line below with:
        // return MainViewController(startLoggedIn: startLoggedIn)
        let vc = UIViewController()
        vc.view.backgroundColor = .black
        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // No-op until KMP integration is active
    }
}

// MARK: - Notification name used to propagate auth state changes from AuthBridge
extension Notification.Name {
    static let authStateDidChange = Notification.Name("StationlyAuthStateDidChange")
}
