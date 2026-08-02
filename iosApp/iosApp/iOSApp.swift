import SwiftUI
import FirebaseCore
import GoogleSignIn
import composeApp

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
        //
        // Same hook drives the live departure stream (LiveStreamBridge):
        // .active connects/resubscribes, .background disconnects cleanly.
        // .inactive (Control Center, app switcher) is ignored on purpose —
        // reconnecting on every brief interruption would thrash the socket.
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                delegate.handleDidBecomeActive()
                LiveStreamBridge.shared.notifyForeground()
            } else if phase == .background {
                LiveStreamBridge.shared.notifyBackground()
            }
        }
    }
}
