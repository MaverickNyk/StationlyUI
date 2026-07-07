import SwiftUI
import FirebaseCore
import GoogleSignIn
// import ComposeApp  // Uncomment after Xcode framework integration

@main
struct StationlyApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        // SwiftUI runs the SCENE lifecycle: UIKit never calls the app
        // delegate's applicationDidBecomeActive, so the foreground work that
        // lived there (auth token refresh, FCM queue flush, widget timeline
        // reload) silently never ran — verified via device syslog: foregrounding
        // produced zero chronod activity, and newly added widget instances
        // starved waiting for a first timeline. scenePhase is the supported
        // hook; foreground reloads are also exempt from WidgetKit's refresh
        // budget, so this is the one reload path that's always honoured.
        .onChange(of: scenePhase) { phase in
            guard phase == .active else { return }
            delegate.handleDidBecomeActive()
        }
    }
}
