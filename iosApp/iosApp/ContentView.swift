import SwiftUI
import FirebaseCore
import FirebaseAuth
import composeApp

struct ContentView: View {
    @State private var isLoggedIn: Bool = (FirebaseApp.app() != nil) ? (Auth.auth().currentUser != nil) : false
    @State private var deepLinkOobCode: String? = nil

    var body: some View {
        ComposeHostView(startLoggedIn: isLoggedIn, deepLinkOobCode: deepLinkOobCode)
            .ignoresSafeArea()
            .onReceive(NotificationCenter.default.publisher(for: .authStateDidChange)) { _ in
                isLoggedIn = (FirebaseApp.app() != nil) ? (Auth.auth().currentUser != nil) : false
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
