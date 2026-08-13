package com.stationly.app

import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.sync.UserStateSync
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.activity.ActivityUploader
import com.stationly.core.service.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Swift's entry point to the activity trail and the account sync.
 *
 * Exported from `composeApp` rather than `core` for the same reason
 * [RefreshScheduleBridge] is: Kotlin/Native only generates the
 * completionHandler/async bridge for suspend functions in the ROOT framework
 * module, so `core`'s own suspend functions never appear in the ObjC header.
 * These are thin delegates.
 *
 * Every call here starts on the main thread (K/N's launch-thread restriction
 * for exported suspend functions) and hops off it immediately.
 */
object ActivityBridge {

    /**
     * Wire the settings stores to the backend. Called once, at launch.
     *
     * Separate from the first event because it must happen before ANY settings
     * write, and the app can be launched straight into a settings screen by a
     * widget tap.
     */
    fun start() = UserStateSync.start()

    // ── App lifecycle ───────────────────────────────────────────────────────

    /**
     * The app came to the foreground.
     *
     * [cold] distinguishes a launch from a resume, which is the difference
     * between "opened the app" and "came back to it" — two very different
     * things to count, and indistinguishable once they are in the same bucket.
     */
    fun appOpened(cold: Boolean) {
        foregroundedAt = Clock.System.now().toEpochMilliseconds()
        ActivityLog.record(ActivityEvents.APP_OPENED, "cold", cold.toString())
    }

    /**
     * The app went to the background.
     *
     * Awaited (a suspend function on the Swift side) because the process may be
     * suspended immediately after: a detached write here is not guaranteed to
     * reach SQLite, and this is also where pending settings are flushed.
     *
     * Session length is computed here rather than stored as a pair of events,
     * so a consumer does not have to reassemble sessions by matching opens to
     * closes — an exercise that fails on the sessions that end with a crash.
     */
    suspend fun appBackgrounded(): Boolean = withContext(Dispatchers.Default) {
        val since = foregroundedAt
        val seconds = if (since > 0) (Clock.System.now().toEpochMilliseconds() - since) / 1000 else 0
        foregroundedAt = 0
        runCatching {
            ActivityLog.recordBlocking(ActivityEvents.APP_BACKGROUNDED, mapOf("seconds" to seconds.toString()))
        }
        // Backgrounding is one of the two moments the debounced sync must not
        // be allowed to lose (the other is logout) — the timer may never fire
        // once the process is suspended.
        runCatching { UserStateSync.flushNow() }.isSuccess
    }

    private var foregroundedAt: Long = 0

    // ── Widgets ─────────────────────────────────────────────────────────────

    /**
     * Report the widgets currently on the home screen, as WidgetKit sees them.
     *
     * ## Add and remove are DERIVED, and cannot be observed
     * WidgetKit gives no callback when a widget is placed or removed. The only
     * thing available is a snapshot of the current configurations, so the Swift
     * host reads that on every foreground and hands it here, and this diffs it
     * against the last one it saw.
     *
     * Two consequences worth knowing when reading the data, both unavoidable:
     * a widget added AND removed between two app opens leaves no trace at all,
     * and every event carries the timestamp of the app open that noticed rather
     * than of the user's action.
     *
     * [descriptors] is one string per widget instance, `family|stationId`. A
     * flat list of strings rather than a structured type because this crosses
     * the ObjC bridge, where a list of strings is the cheapest thing that
     * survives it intact.
     */
    fun widgetsObserved(descriptors: List<String>) {
        val current = descriptors.toSet()
        val previous = lastWidgets

        // The board-level answer, which is a different question from the events
        // below: those record a CHANGE, this records the current state, and the
        // settings screen needs the second even when nothing has changed since
        // the app opened. Published on every observation, including the first
        // and including a no-op, because a board's own view of it must be right
        // from the first frame rather than from the first change.
        UserStateSync.widgetsObserved(
            descriptors.mapNotNull { descriptor ->
                val parts = descriptor.split('|')
                val station = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                station to (parts.getOrNull(0).orEmpty())
            }.groupBy({ it.first }, { it.second }),
        )

        // First observation of a session establishes the baseline. Treating it
        // as "everything was just added" would report a fresh install's widgets
        // as added on every launch — and there is no way to tell a genuine
        // first placement from a widget that has been there for a month.
        if (previous == null) {
            lastWidgets = current
            return
        }
        if (previous == current) return

        (current - previous).forEach { emitWidgetEvent(ActivityEvents.WIDGET_ADDED, it) }
        (previous - current).forEach { emitWidgetEvent(ActivityEvents.WIDGET_REMOVED, it) }
        ActivityLog.record(ActivityEvents.WIDGET_COUNT, "count", current.size.toString())
        lastWidgets = current
    }

    private fun emitWidgetEvent(name: String, descriptor: String) {
        val parts = descriptor.split('|')
        ActivityLog.record(
            name,
            mapOf(
                "family" to (parts.getOrNull(0) ?: ""),
                "station" to (parts.getOrNull(1) ?: ""),
            ),
        )
    }

    /**
     * Null until the first observation of this process — see [widgetsObserved].
     *
     * In memory rather than persisted, deliberately. A persisted baseline would
     * survive a reinstall's App Group data and report every widget as removed
     * when the app was simply deleted, which is the one event it could never
     * legitimately observe.
     */
    private var lastWidgets: Set<String>? = null

    // ── Upload ──────────────────────────────────────────────────────────────

    /**
     * Drain the queue — the nightly background task.
     *
     * Returns whether anything was uploaded, which the caller reports as its
     * background-task result: a task that reports "no data" too often gets
     * scheduled less generously by iOS.
     */
    suspend fun uploadActivity(): Boolean = withContext(Dispatchers.Default) {
        // The nightly safety net for the board list, riding on a wake the app
        // already has.
        //
        // Every board change pushes within seconds, so this is normally a no-op
        // — but "normally" excludes the case it exists for: the app killed
        // inside the debounce window, or a push that failed while offline. The
        // local list is right and the account's is stale, and nothing else would
        // ever notice, because the next push only fires on the next EDIT. One
        // write a night, only when something is actually pending.
        runCatching { UserStateSync.flushNow() }

        val info = DeviceIdentity.deviceInfo()
        ActivityUploader.flush(
            api = NetworkModule.sduiApi,
            deviceId = DeviceIdentity.deviceId(),
            platform = info.platform ?: "ios",
            appVersion = info.appVersion,
        )
    }

    /**
     * Drain the queue only if it has gone stale — the foreground safety net for
     * a device whose background task never runs.
     *
     * Cheap enough for every foreground: on a healthy device it reads one
     * integer out of SQLite and returns.
     */
    suspend fun uploadActivityIfStale(): Boolean = withContext(Dispatchers.Default) {
        val info = DeviceIdentity.deviceInfo()
        ActivityUploader.flushIfStale(
            api = NetworkModule.sduiApi,
            deviceId = DeviceIdentity.deviceId(),
            platform = info.platform ?: "ios",
            appVersion = info.appVersion,
        )
    }
}
