import Foundation

/// Swift half of the push trace — Kotlin's `PushTrace` (Platform.ios.kt)
/// appends to the same App Group key, so both sides of a push interleave in
/// one chronological log.
///
/// Answers the one question the Kotlin traces can't: did APNs hand us the push
/// at all? A silent push that Apple throttles or drops is indistinguishable
/// from one we received and mishandled, unless the very first hop is recorded.
///
/// Pull it off a device with:
/// ```
/// xcrun devicectl device copy from --device <UDID> \
///   --domain-type appGroupDataContainer \
///   --domain-identifier group.com.stationly.shared --source / --destination /tmp/pull
/// ```
///
/// ⚠️ Keep the bound in lockstep with Kotlin's `PushTrace` — they share the
/// key, so the smaller cap would silently truncate the other side.
///
/// Raised from 40 to 120 when Kotlin moved its `stream:` chatter into a ring of
/// its own (`push_trace_stream`). Those lines fire once per station and once per
/// line on every socket attach, so they filled all forty in about two seconds
/// and evicted every auth and registration line before it could be read — which
/// is exactly the half this file exists to record. Nothing Swift writes here is
/// high-rate, so this side stays on the quiet ring.
enum PushTraceSwift {
    private static let appGroupID = AppGroupID.value
    private static let key = "push_trace"
    private static let maxEntries = 120

    static func log(_ msg: String) {
        guard let d = UserDefaults(suiteName: appGroupID) else { return }
        var entries = (d.array(forKey: key) as? [String]) ?? []
        entries.append("\(Int(Date().timeIntervalSince1970)) \(msg)")
        if entries.count > maxEntries { entries = Array(entries.suffix(maxEntries)) }
        d.set(entries, forKey: key)
    }
}
