import SwiftUI
import FirebaseCore
import GoogleSignIn
// import ComposeApp  // Uncomment after Xcode framework integration

@main
struct StationlyApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
