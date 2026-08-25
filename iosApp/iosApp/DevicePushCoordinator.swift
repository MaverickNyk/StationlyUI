import Foundation
import UIKit
import WidgetKit
import composeApp
import FirebaseAuth

/// Tells the backend how to reach this device's widgets, and decides what an
/// incoming trigger means.
///
/// ## Two tokens, two jobs
///  - The **widget push token** is issued by WidgetKit to the extension
///    (iOS 26+) and written to the App Group by `StationlyWidgetPushHandler`.
///    A push to it reloads the widget WITHOUT waking the app, on a budget
///    separate from the timeline quota. This is the path worth having.
///  - The **app APNs token** wakes the app silently. It works back to iOS 17,
///    so it is the fallback — and it is the only path that can change stored
///    state, because a widget reload cannot write anything.
///
/// The app owns registration for both: the extension has no auth state, no
/// knowledge of which backend it points at, and a very short execution window.
enum DevicePushCoordinator {

    /// This device's APNs token, captured in `didRegisterForRemoteNotifications`.
    /// Held in memory rather than persisted — iOS re-issues it on every launch,
    /// and a stored copy would only ever be a stale one to get confused by.
    private(set) static var appToken: String?

    

    static func setAppToken(_ data: Data) {
        appToken = data.map { String(format: "%02x", $0) }.joined()
    }

    /// Stable per-install id — the registry key.
    ///
    /// Keyed on this rather than on a token because TOKENS rotate and the
    /// device does not: keying by token would accumulate a new backend record
    /// on every rotation and leave the dead ones to be pushed at forever.
    /// Stored in the App Group so it survives the standard domain being wiped
    /// on sign-out (see the note on the FCM token in AppDelegate).
    private static func deviceId() -> String {
        let d = UserDefaults(suiteName: AppGroupID.value)
        if let existing = d?.string(forKey: AppGroupKeys.deviceId), !existing.isEmpty { return existing }
        let fresh = UUID().uuidString
        d?.set(fresh, forKey: AppGroupKeys.deviceId)
        d?.synchronize()
        return fresh
    }

    /// Which APNs environment this build's tokens belong to.
    ///
    /// A token is valid in exactly ONE environment: a development build's token
    /// is rejected by the production gateway with `BadDeviceToken` and vice
    /// versa. Derived from the same `aps-environment` entitlement that decided
    /// which gateway issued the token, so the two cannot disagree — reading a
    /// build flag instead is how this ends up wrong in a TestFlight build.
    private static func apnsEnvironment() -> String {
        if let cached = cachedEnvironment { return cached }
        let resolved: String = {
            guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
                  let raw = try? Data(contentsOf: url),
                  let text = String(data: raw, encoding: .isoLatin1),
                  let range = text.range(of: "<key>aps-environment</key>")
            else {
                // No profile means the App Store, which is always production.
                return "production"
            }
            let tail = text[range.upperBound...].prefix(200)
            return tail.contains("development") ? "sandbox" : "production"
        }()
        cachedEnvironment = resolved
        return resolved
    }

    /// Read once. The embedded profile cannot change under a running process,
    /// and `register()` runs on every foreground — re-reading and string-scanning
    /// a multi-kilobyte file each time bought nothing.
    private static var cachedEnvironment: String?

    /// Push whatever tokens we have to the backend.
    ///
    /// Idempotent and cheap, so it runs on every foreground: the widget token
    /// may have been written by the extension since we last looked, the app
    /// token is reissued each launch, and the station list changes whenever the
    /// user edits their boards.
    static func register() {
        // Signed out is a SKIP, not a failure.
        //
        // `/device/register` is bearer-gated since P2: the row lives at
        // `users/{uid}/devices/{deviceId}` and its existence IS the session, so
        // there is nowhere to file a registration that names no account and the
        // backend answers 401 `no_session`. A signed-out device is an empty
        // state by design — no boards, no widgets to fill, nothing to push — so
        // retrying would put a 401 in a loop on every foreground of every
        // signed-out install.
        //
        // Synchronous, and deliberately not inside the Task below. `logout()`
        // clears the cache through `release()` and then a later sign-in calls
        // this; if the clear were deferred onto a queue it could land AFTER the
        // sign-in's registration and re-suppress it. Both mutations stay
        // ordered by staying on the caller's thread, under the same lock.
        guard Auth.auth().currentUser != nil else {
            clearSignature()
            PushTraceSwift.log("register skipped — signed out (not a failure)")
            return
        }
        Task { await registerCoalesced() }
    }

    /// One registration at a time, with a late request folded into the current one.
    ///
    /// ## Why coalescing, and why it is not simply "drop the duplicate"
    /// Three registrations fire per sign-in — one each from the sign-in itself,
    /// the auth-state restore and the token refresh, all of which land within a
    /// few hundred milliseconds. Measured on the connected iPhone: three
    /// `POST /device/register` between the logout and the profile sync, where
    /// one would have done.
    ///
    /// The body signature cannot dedupe them, because it is only recorded once a
    /// response comes back and all three are in flight before the first reply
    /// arrives. So the guard has to be in-flight state, not body state.
    ///
    /// Dropping a concurrent request outright would be wrong, though: the second
    /// caller may be registering a body the first one does not have — an APNs
    /// token that arrived mid-flight, or a board edit. `requestedWhileInFlight`
    /// remembers that somebody asked and runs exactly one more pass afterwards,
    /// which collapses any number of concurrent callers into at most two POSTs
    /// while never losing a change.
    ///
    /// Bounded to one repeat on purpose: each pass re-reads the current body, so
    /// a second pass already reflects every request that arrived during the
    /// first, and looping while the flag keeps being set would let a busy
    /// foreground spin.
    private static func registerCoalesced() async {
        guard beginRegistration() else {
            PushTraceSwift.log("register coalesced — one already in flight")
            return
        }
        var runAgain = await performRegister()
        if runAgain { runAgain = await performRegister() }
        _ = runAgain
        endRegistrationCycle()
    }

    /// One registration pass. Returns true when another was requested meanwhile.
    ///
    /// The bearer is minted here rather than read from `firebase_auth_token`.
    /// That key is a CACHE — Firebase ID tokens live about an hour, it is written
    /// opportunistically, and it is routinely empty right after launch, which is
    /// exactly when this runs. Reading it sent a dead bearer, and once the
    /// endpoint became bearer-gated it made registration skip itself entirely so
    /// the device never got its push tokens back after a sign-out (measured).
    /// `getIDToken()` serves the cached token until it is within ~5 minutes of
    /// expiry and refreshes transparently after that.
    @discardableResult
    private static func performRegister() async -> Bool {
        guard let user = Auth.auth().currentUser else { return false }

        // One suite handle for the whole pass. Three separate
        // `UserDefaults(suiteName:)` constructions read the same container and
        // gave three chances to typo the identifier.
        guard let group = UserDefaults(suiteName: AppGroupID.value) else { return false }
        let widgetToken = group.string(forKey: AppGroupKeys.widgetPushToken)

        guard widgetToken != nil || appToken != nil else {
            // Traced because this is the silent failure mode: nothing to
            // register looks exactly like registration working, and the only
            // symptom is an empty registry on a server you may not be watching.
            PushTraceSwift.log("register skipped — no tokens yet")
            return false
        }

        // ── `stations` and `lines` are NOT sent any more ──
        //
        // They used to be, and the comment here used to explain that the lines
        // were load-bearing for disruption because `DisruptionTriggerService`
        // scoped its pushes with an `array-contains-any` over the device row.
        // That was true, and P2 ended it: §3.1 took both arrays OFF the row
        // because they were named like device data and held ACCOUNT data, and
        // the audience is now resolved from the backend's `user_watch` index,
        // which re-derives from the synced boards.
        //
        // So the backend has been ignoring them — `devicePushController` did not
        // even forward them to the upsert. Two costs for nothing: a payload of
        // every station and line on every registration, and, because the body
        // signature covers them, a POST triggered by EVERY BOARD EDIT that then
        // wrote nothing new. Removing the per-device rewrite on a board edit was
        // one of §3.1's stated wins; the server side of it landed and this half
        // did not.
        //
        // Observed in the trace as `stations=0 lines=0` on a sign-in
        // registration that fires before the boards are restored — harmless
        // precisely because nothing reads them, which is the tell that they
        // should not be here.
        var body: [String: Any] = [
            "deviceId": deviceId(),
            "environment": apnsEnvironment(),
            // ⚠️ The SAME shape `DeviceIdentity` sends as `deviceInfo.osVersion`
            // on the login path ("iOS 26.3", matching Android's
            // "Android 14 (SDK 34)"). Both write the one `osVersion` field on the
            // device row, so a bare "26.3" here meant the stored value flipped
            // format depending on which path wrote last — a field that means two
            // things is a field that gets read wrong.
            "iosVersion": "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)",
        ]
        if let widgetToken { body["widgetToken"] = widgetToken }
        if let appToken { body["appToken"] = appToken }
        if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
            body["appVersion"] = version
        }

        // Skip a POST that would change nothing.
        //
        // `register()` runs on every foreground, every APNs token callback, on
        // sign-in and after every account reconcile, but the payload only
        // actually changes when a token rotates or the user edits their boards.
        // The signature covers the whole body, so anything that genuinely
        // changes still goes.
        //
        // In-memory only: a fresh process re-registers once, which is the
        // correct behaviour after an update or a crash.
        let signature = String(describing: body.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" })
        guard !matchesLastAccepted(signature) else { return false }

        guard let baseUrl = group.string(forKey: AppGroupKeys.apiBaseURL),
              let apiKey = group.string(forKey: AppGroupKeys.apiKey),
              // NOT under /user/* — that prefix picks up the `/user` middleware,
              // and this route gates the bearer inside its own handler so it can
              // answer `no_session` distinctly. Measured: the first on-device
              // registration 401'd for exactly that reason.
              let url = URL(string: "\(baseUrl)/api/v1/device/register"),
              let payload = try? JSONSerialization.data(withJSONObject: body)
        else { return false }

        let idToken: String
        do {
            idToken = try await user.getIDToken()
        } catch {
            // A token we could not mint is a transient failure, not a signed-out
            // device. Leave the cached signature alone so the next trigger retries.
            PushTraceSwift.log("register deferred — no token (\(error.localizedDescription))")
            return false
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "X-Stationly-Key")
        // REQUIRED now, not optional. The backend derives the account from this
        // token and never from a body field, so a device cannot claim someone
        // else's account by asking for it.
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = payload

        // `await` rather than a completion handler, and that is a correctness
        // change rather than a style one: the callback ran on a URLSession queue
        // and mutated the signature cache that `register()` and `release()` also
        // touch, with no synchronisation at all. Every mutation now happens
        // through the lock below, on a thread this function controls.
        var status = -1
        var failure: Error?
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            status = (response as? HTTPURLResponse)?.statusCode ?? -1
        } catch {
            failure = error
        }

        // Remembered only on success, so a failed attempt is retried on the next
        // trigger rather than being suppressed by its own signature. A 401 means
        // the session went away between the guard and this request landing — a
        // sign-out mid-flight, or an expired token — so the cache is dropped and
        // the next trigger genuinely retries.
        recordOutcome(signature: signature, status: status)

        PushTraceSwift.log(
            "widgetpush register status=\(status) widget=\(widgetToken != nil) " +
            "app=\(appToken != nil)" +
            (failure.map { " err=\($0.localizedDescription)" } ?? ""))

        return consumePendingRequest()
    }

    // ── Registration state, and why it is behind a lock ──────────────────────
    //
    // These three are read and written from at least three threads: `register()`
    // on whatever thread AuthBridge or AppDelegate happens to be on, `release()`
    // from the sign-out path, and — before this refactor — a URLSession
    // completion handler on its own queue. Nothing synchronised them, which is a
    // data race in the ordinary sense and a hard error under Swift strict
    // concurrency.
    //
    // A lock rather than an actor because the two ordering-sensitive entry
    // points (`release()` at sign-out, `register()` at the sign-in that follows)
    // must stay SYNCHRONOUS. Hopping them onto an actor would make the clear and
    // the register two independently scheduled jobs, and a clear that landed
    // second would re-suppress the registration it was supposed to enable —
    // which is the exact bug this cache caused in the first place.
    private static let stateLock = NSLock()

    /// Body of the last registration the backend accepted. See `performRegister()`.
    private static var lastRegisteredSignature: String?
    /// A pass is running; further callers coalesce into it.
    private static var registrationInFlight = false
    /// Somebody asked while a pass was running, so one more pass is owed.
    private static var pendingRequest = false

    private static func withState<T>(_ body: () -> T) -> T {
        stateLock.lock()
        defer { stateLock.unlock() }
        return body()
    }

    /// Claim the single in-flight slot, or record that another pass is owed.
    private static func beginRegistration() -> Bool {
        withState {
            if registrationInFlight { pendingRequest = true; return false }
            registrationInFlight = true
            return true
        }
    }

    private static func endRegistrationCycle() {
        withState { registrationInFlight = false; pendingRequest = false }
    }

    private static func consumePendingRequest() -> Bool {
        withState {
            let owed = pendingRequest
            pendingRequest = false
            return owed
        }
    }

    private static func matchesLastAccepted(_ signature: String) -> Bool {
        withState { signature == lastRegisteredSignature }
    }

    private static func recordOutcome(signature: String, status: Int) {
        withState {
            if status == 200 { lastRegisteredSignature = signature }
            if status == 401 { lastRegisteredSignature = nil }
        }
    }

    private static func clearSignature() {
        withState { lastRegisteredSignature = nil }
    }

    /// This device's session has ended — forget that it was ever registered.
    ///
    /// ## The trap this closes, which the signature cache creates
    /// `register()` skips a POST whose body is byte-identical to the last one it
    /// got a 200 for. That is a good optimisation and a correct one while a
    /// session lasts, because the body genuinely describes the device.
    ///
    /// It stops being correct across a session boundary, and the reason is that
    /// the body says nothing about WHO is signed in — the account comes from the
    /// bearer, server-side. So:
    ///
    ///   1. signed in as A, `register()` POSTs, signature = S
    ///   2. sign out. The backend DELETES `users/A/devices/{id}` — the row is the
    ///      session now, so ending one removes it
    ///   3. sign back in as A. Login recreates the row and deliberately writes NO
    ///      token fields; only `/device/register` may write those
    ///   4. next `register()` builds the same body, computes S, and skips
    ///
    /// The device now sits in its own account's push audience holding no address
    /// at all. Nothing errors, and a zero-token device is not an error condition
    /// anywhere — it is simply never reachable. `register()` already clears the
    /// signature when it finds no signed-in user, but that only helps if it
    /// happens to RUN while signed out, and its three call sites (the APNs token
    /// callback, foreground, and an account change) mean it usually does not:
    /// signing out and straight back in without backgrounding the app never
    /// touches any of them.
    ///
    /// So the clear belongs on the session boundary itself. Called from
    /// `AuthBridge.logout()`, which every deliberate teardown routes through.
    ///
    /// This is the `PushRegistrar.release()` in §8 of the design — the one iOS
    /// has never had.
    static func release() {
        clearSignature()
        PushTraceSwift.log("register signature cleared — session ended")
    }

    // [trackedBoards] was deleted here.
    //
    // It read the widget station/line directory out of the App Group so
    // `/device/register` could send both arrays. §3.1 took those arrays off the
    // device row — they were named like device data and held ACCOUNT data — and
    // the disruption audience now comes from the backend's `user_watch` index,
    // re-derived from the synced boards. Nothing consumed the arrays after that,
    // on either side of the wire.
    //
    // Deleted rather than left unused: it decoded a payload, lower-cased line
    // ids and de-duplicated them "so the backend's `array-contains-any` query
    // matches", which is a precise description of a query that no longer exists.
    // Dead code that documents a live-sounding contract is worse than none.

    // MARK: - Incoming triggers

    /// What the backend asked us to do.
    ///
    /// Mirrors `PushSignalKind` on the server and `PushSignal` in commonMain —
    /// one vocabulary, three implementations, so a new signal is added in one
    /// place and spelled the same everywhere.
    enum Trigger: String {
        /// The account changed on another device. **Not a widget concern** —
        /// this is the cross-device consistency signal, and the only one here
        /// that also exists on Android.
        case userSync = "user.sync"
        case refresh = "widget.refresh"
        case policyUpdate = "policy.update"
        case boostStart = "boost.start"
        case boostStop = "boost.stop"
    }

    /// Act on a silent push.
    ///
    /// Returns whether anything changed, which `didReceiveRemoteNotification`
    /// reports to iOS — a background wake that honestly reports `.noData` is
    /// scheduled more generously in future than one that always claims new data.
    ///
    /// Unknown or missing types are treated as a plain refresh rather than
    /// ignored: a push we do not recognise still means the backend thought
    /// something was worth telling us about, and refreshing is both harmless and
    /// the most likely intent.
    static func handle(_ userInfo: [AnyHashable: Any]) async -> Bool {
        let envelope = userInfo["stationly"] as? [String: Any]
        let kind = (envelope?["type"] as? String).flatMap(Trigger.init(rawValue:)) ?? .refresh
        let reason = envelope?["reason"] as? String ?? ""
        PushTraceSwift.log("widgetpush recv kind=\(kind.rawValue) reason=\(reason)")

        switch kind {
        case .userSync:
            // The uid check, the station diff and the profile reconcile all live
            // in shared Kotlin (`UserSyncBridge`), so iOS and Android cannot
            // drift on what "the cloud is the truth" means.
            let pushUid = envelope?["uid"] as? String
            if UserSyncBridge.shared.isAccountDeleted(reason: reason) {
                // ── The uid check has to happen HERE ──
                //
                // `UserSyncBridge.handle` performs it for every other reason,
                // but this branch returns before reaching it — so the one push
                // that can END A SESSION was the only one acting without
                // checking who it was for. A device token outlives a session, so
                // a "deleted" push can land on a phone that has since signed in
                // as somebody else, and acting on it would sign out a user whose
                // account is perfectly fine.
                //
                // A push with no uid is still honoured: older senders omit it,
                // and refusing those would leave real deletions unapplied.
                //
                // ── An UNREADABLE local uid drops it too ──
                //
                // This used to require BOTH sides to resolve (`if let pushUid,
                // let currentUid`), so a missing `firebase_user_uid` did not
                // fail the check — it skipped it, and a push minted for
                // somebody else's account signed this one out. That is not a
                // hypothetical state: the identity keys were being wiped on
                // ordinary cold launches by the auth-listener race (see
                // `AuthBridge.settleAuthState`), which left this guard disarmed
                // on exactly the push that can end a session.
                //
                // Refusing costs a real deletion nothing. `refreshTokenIfNeeded`
                // asks Google directly on every foreground and ends the session
                // on `.userNotFound` — authoritative, and no more than one
                // foreground later.
                let currentUid = UserSyncBridge.shared.currentUidOrNull()
                if let pushUid, pushUid != currentUid {
                    PushTraceSwift.log(
                        "user.sync deleted → DROPPED (for \(pushUid), signed in as \(currentUid ?? "<unknown>"))")
                    return false
                }
                // Forcing a sign-out touches the keychain, the Firebase session
                // and the UI — none of which shared code owns, and a
                // half-applied logout is worse than a late one. Android routes
                // this to its own `forceLogout` for the same reason.
                PushTraceSwift.log("user.sync deleted → forcing sign-out")
                await AuthBridge.shared.signOutForAccountDeletion()
                return true
            }
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                UserSyncBridge.shared.handle(reason: reason, pushUid: pushUid) { value, _ in
                    continuation.resume(returning: value?.boolValue ?? false)
                }
            }
            if changed {
                // The station list may have changed, so the widget's boards and
                // the backend's idea of what this device shows both need
                // updating.
                _ = await BackgroundRefreshScheduler.refreshNow(reason: "user.sync")
                register()
            }
            return changed

        case .refresh:
            return await BackgroundRefreshScheduler.refreshNow(reason: "push:\(reason)")

        case .policyUpdate:
            // Force a refetch regardless of TTL — the backend is telling us the
            // cached copy is stale ahead of when we would have asked.
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                RefreshScheduleBridge.shared.forcePolicyRefresh { value, _ in
                    continuation.resume(returning: value?.boolValue ?? false)
                }
            }
            // The cadence changing only takes effect on a new timeline, and the
            // background task interval only on a resubmission.
            if changed { reloadAndReschedule() }
            return changed

        case .boostStart:
            let tierId = envelope?["tierId"] as? String ?? ""
            let minutes = envelope?["minutes"] as? Int ?? 0
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                RefreshScheduleBridge.shared.startBoost(
                    tierId: tierId, requestedMinutes: Int32(minutes), reason: reason
                ) { value, _ in continuation.resume(returning: value?.boolValue ?? false) }
            }
            // A boost is worth fetching for immediately — its whole purpose is
            // that the next few minutes matter more than usual.
            _ = await BackgroundRefreshScheduler.refreshNow(reason: "boost:\(reason)")
            reloadAndReschedule()
            return changed

        case .boostStop:
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                RefreshScheduleBridge.shared.stopBoost { value, _ in
                    continuation.resume(returning: value?.boolValue ?? false)
                }
            }
            if changed { reloadAndReschedule() }
            return changed
        }
    }

    /// A cadence change is inert until the widget rebuilds its timeline and the
    /// background task is resubmitted at the new interval.
    private static func reloadAndReschedule() {
        WidgetCenter.shared.reloadAllTimelines()
        BackgroundRefreshScheduler.schedule()
    }
}
