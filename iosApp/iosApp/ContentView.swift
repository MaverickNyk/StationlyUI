import SwiftUI
import FirebaseCore
import FirebaseAuth
import composeApp
import os

/// Deep-link arrivals — see the `.onOpenURL` handler below.
private let deepLinkLog = Logger(subsystem: "com.stationly.mobile", category: "deeplink")

/// The scheme THIS build answers to, read from its own Info.plist.
///
/// Not a literal, and the reason is a bug this spent a release with: staging and
/// production register different schemes (`stationly-staging` / `stationly`, from
/// `STATIONLY_URL_SCHEME`) so that a tap in the staging widget cannot be handed
/// to the production app. The widget was parameterised for the split;
/// `.onOpenURL` was left comparing against the production literal, so on staging
/// every widget tap was registered by iOS, delivered here, and dropped one line
/// later. The user saw the app open on whatever it was last showing, which is
/// indistinguishable from the feature never having been built.
///
/// `$(STATIONLY_URL_SCHEME)` populates both this key and `CFBundleURLSchemes`
/// from one build setting, so what we compare against and what iOS routes to us
/// cannot drift apart again.
///
/// Falls back rather than trapping, unlike the widget's copy of this: an app
/// extension can afford `fatalError` on a broken build contract, and the app
/// refusing to launch over a deep-link scheme cannot be the right trade.
private let stationlyUrlScheme: String = {
    guard let scheme = Bundle.main.object(forInfoDictionaryKey: "StationlyUrlScheme") as? String,
          !scheme.isEmpty
    else {
        deepLinkLog.error("StationlyUrlScheme missing from Info.plist — widget taps will not focus a station")
        return "stationly"
    }
    return scheme
}()

struct ContentView: View {
    @AppStorage("app_theme") private var appTheme: String = "system"
    /// `AuthBridge.hasSession` rather than `Auth.auth().currentUser != nil`.
    ///
    /// Both answer "is there a session", and they disagree in one situation that
    /// costs the user their app: Firebase could not read the Keychain at launch
    /// — a device launched locked, or an iOS prewarm — so `currentUser` is nil
    /// and stays nil until `protectedDataDidBecomeAvailable` lets it try again.
    /// This value is read ONCE, into `startLoggedIn`, and `AppNavigation` turns
    /// it into a start destination that never changes for the life of the host.
    /// So a nil here is not a moment of uncertainty, it is the login screen for
    /// the whole session.
    ///
    /// The stored token behind `hasSession` cannot say that wrongly: it is
    /// removed only by `clearUserInfo`, which now runs only on a sign-out we
    /// asked for.
    @State private var isLoggedIn: Bool = AuthBridge.shared.hasSession
    @State private var deepLinkOobCode: String? = nil
    /// Bumped when the session ENDS, and when one arrives under a login screen
    /// we should never have shown. See the `.id` on the body.
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
            // This must be stable across a sign-IN THE LOGIN FLOW PERFORMED. An
            // earlier version read `isLoggedIn ? "session" : "signed-out-\(n)"`,
            // which changes on BOTH transitions — so signing in tore down and
            // rebuilt the whole Compose host mid-login. That threw away the login
            // flow's own navigation, re-ran the tree against state the loader had
            // just finished populating, and crashed outright once there was more
            // than one board to rebuild.
            //
            // A RESTORE is the opposite case and needs the opposite treatment —
            // see the guard below.
            .id(signedOutGeneration)
            .ignoresSafeArea()
            .preferredColorScheme(colorScheme)
            .onReceive(NotificationCenter.default.publisher(for: .authStateDidChange)) { _ in
                let nowLoggedIn = AuthBridge.shared.hasSession
                // Bump BEFORE flipping the flag, so the rebuilt host is a fresh
                // instance rather than one SwiftUI can match to the old id.
                if isLoggedIn && !nowLoggedIn {
                    signedOutGeneration += 1
                } else if !isLoggedIn && nowLoggedIn && !AuthBridge.shared.isHandlingAuthCommand {
                    // ── A session arrived under a login screen nobody chose ──
                    //
                    // Compose reads `startLoggedIn` once, in
                    // `makeUIViewController`, and `updateUIViewController` is a
                    // no-op — so a host that booted signed-out stays signed-out
                    // however the session resolves afterwards. Without this, a
                    // Firebase restore that lands a moment late leaves the user
                    // looking at a sign-in form with their own valid session
                    // behind it, and the only way out is to sign in again.
                    //
                    // `isHandlingAuthCommand` is what keeps this off the login
                    // flow's own path: that sign-in comes from a KMP command, and
                    // Compose is already navigating itself out of the login
                    // screen. This branch is for the sign-in nobody asked for —
                    // a keychain restore, or protected data becoming readable.
                    signedOutGeneration += 1
                }
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
                guard url.scheme == stationlyUrlScheme else { return }
                #if DEBUG
                // ── The only way to reach the auth test controls on device ──
                //
                // Auth cannot be verified through `devicectl device process
                // launch`: a CLI-launched process reads no Keychain, so
                // FirebaseAuth reports no user on a phone that is signed in
                // (recorded on `AuthBridge.settleAuthState`). The app has to be
                // opened by hand, which leaves no channel for a test command —
                // so this is one. Tapping
                // `stationly-staging://debug?action=expire-token` in Notes
                // forces the exact state the auto-logout needed.
                //
                // DEBUG only, and it writes nothing a release build could reach.
                if url.host == "debug" {
                    let action = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                        .queryItems?.first(where: { $0.name == "action" })?.value
                    switch action {
                    case "expire-token":   AuthTestControls.forceExpiredToken()
                    case "stale-request":  Task { await AuthTestControls.staleTokenRequest() }
                    case "fast-path":      Task { await AuthTestControls.fastPathBurst() }
                    default:               PushTraceSwift.log("TEST unknown action=\(action ?? "-")")
                    }
                    return
                }
                #endif
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
