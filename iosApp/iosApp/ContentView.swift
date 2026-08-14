import SwiftUI
import FirebaseCore
import FirebaseAuth
import composeApp
import os

/// Deep-link arrivals — see the `.onOpenURL` handler below.
private let deepLinkLog = Logger(subsystem: "com.stationly.mobile", category: "deeplink")

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
            // ── Widget taps ──
            //
            // `.onOpenURL` and NOT `AppDelegate.application(_:open:)`. Under the
            // SwiftUI App lifecycle UIKit does not call the delegate method —
            // the same reason `applicationDidBecomeActive` had to move to
            // `scenePhase`, recorded in `StationlyApp`. This is the hook that
            // actually fires.
            //
            // Handed to `BoardFocus` rather than to `ComposeHostView` above,
            // because the constructor route only reaches a COLD START:
            // `makeUIViewController` reads its parameters once and
            // `updateUIViewController` is a no-op. A widget tap almost always
            // finds the app already alive, so it has to arrive through
            // something the running composition is collecting.
            .onOpenURL { url in
                // Not ours — Google Sign-In's redirect scheme, most likely.
                // Returned before any logging so a foreign URL cannot spend
                // entries in `PushTraceSwift`'s 40-deep ring, which is shared
                // with the push trace and is the only diagnostic either side has.
                guard url.scheme == "stationly" else { return }
                guard url.host == "home",
                      let station = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                          .queryItems?.first(where: { $0.name == "station" })?.value,
                      !station.isEmpty
                else {
                    // Ours, and unhandled. Today that is `stationly://…?mode=
                    // resetPassword` — see the note in `AppDelegate`: those links
                    // arrive HERE now that the scheme is registered, and the
                    // delegate's `handleFirebaseActionURL` is not reachable under
                    // the SwiftUI scene lifecycle. Deliberately not wired up in
                    // this change: `deepLinkOobCode` reaches Compose only through
                    // `makeUIViewController`, which has already run by the time
                    // this fires, so routing it here would look fixed and not be.
                    deepLinkLog.notice("open url unhandled host=\(url.host ?? "-", privacy: .public)")
                    PushTraceSwift.log("deeplink unhandled host=\(url.host ?? "-")")
                    return
                }
                // ── Logged TWICE, and both are needed on this project ──
                //
                // This is the only hop where "the widget tap never arrived" can
                // be told apart from "it arrived and the home screen ignored
                // it", so it has to be visible from outside.
                //
                // `os_log` is the right tool and is readable in Xcode — but
                // `idevicesyslog` returns NOTHING on the iOS 26 device this is
                // tested on (measured: 8 seconds of capture, zero lines), so on
                // that phone it is write-only. `PushTraceSwift` writes to the
                // App Group, which `devicectl device copy from` can pull back,
                // and that is the channel that actually works here.
                deepLinkLog.notice("open url station=\(station, privacy: .public)")
                PushTraceSwift.log("deeplink station=\(station)")
                BoardFocus.shared.request(stationId: station)
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
