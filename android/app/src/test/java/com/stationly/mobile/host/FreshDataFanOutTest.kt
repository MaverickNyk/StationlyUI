package com.stationly.mobile.host

import android.content.Context
import com.stationly.core.util.FreshData
import com.stationly.mobile.util.FreshDataNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every path that writes fresh departures to SQLite must tell the app, and it
 * must say WHICH board changed.
 *
 * ## The bug this exists to stop coming back
 * Until AV2-4.1 the fan-out told the app by writing a `SharedPreferences` key
 * shaped `predictions_<station>_<line>`, which v1's `SummaryViewModel` watched
 * with an `OnSharedPreferenceChangeListener`. AV2-3.5 deleted that view model
 * with the rest of v1's UI, and nothing has registered a preference listener
 * since. The write kept happening, to a key with no reader.
 *
 * The shared `SummaryViewModel` — the app's home screen since the cutover —
 * collects `com.stationly.core.util.FreshDataNotifier.events` instead, and no
 * Android code path emitted to it. So an FCM push wrote fresh departures and the
 * open board did not move: it caught up on its own 30-second poll or on the next
 * resume. Nothing errored, nothing logged, and it is indistinguishable from a
 * slow network. iOS was fine throughout, because iOS reaches the same SQLite via
 * `ProcessPredictionsUseCase`, which emits.
 *
 * ## Why this test is shaped the way it is
 * The fan-out needs a `Context` — it sends a broadcast and pokes the
 * AppWidgetManager — and `Context` is an abstract class, so it cannot be
 * proxied or stubbed without Robolectric. Standing Robolectric up for three
 * methods is a bigger change than the story wants, and the behaviour that
 * actually regressed is not "does the widget redraw" (it always did) but "is
 * there a scoped emit at all, and does it name the right thing".
 *
 * So the shape is asserted here, and the wiring was verified on a Pixel 7 Pro —
 * see the AV2-4.1 handoff note. What this catches is the regression that is easy
 * to make and impossible to see: a fourth call site added later that goes back to
 * one unscoped `notify`, or a scope that names the station where it means the
 * line.
 */
class FreshDataFanOutTest {

    @Test
    fun `the fan-out has one entry point per scope, and no unscoped one`() {
        val methods = FreshDataNotifier::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            "the scoped entry points changed. Each maps to one FreshData variant; " +
                "a caller that cannot name its scope uses notifyAll, which is " +
                "correct and merely expensive.",
            setOf("notifyPredictions", "notifyLineStatus", "notifyAll"),
            methods,
        )
        assertTrue(
            "a single unscoped `notify` is back. It cannot distinguish a station " +
                "push from a line-status push, so it would have to emit " +
                "FreshData.All for everything — reloading every board on the " +
                "phone on every push, which is the cost the shared notifier was " +
                "made precise to avoid.",
            "notify" !in methods,
        )
    }

    @Test
    fun `each entry point takes the id its FreshData variant carries`() {
        // `notifyPredictions(context, stationId)` and not the old
        // `notify(context, stationId, lineId)`: FreshData.Station carries the
        // naptan alone, and the surplus lineId was only ever there to build the
        // SharedPreferences key that no longer has a reader.
        assertEquals(
            listOf(Context::class.java, String::class.java),
            FreshDataNotifier::class.java
                .getDeclaredMethod("notifyPredictions", Context::class.java, String::class.java)
                .parameterTypes.toList(),
        )
        assertEquals(
            listOf(Context::class.java, String::class.java),
            FreshDataNotifier::class.java
                .getDeclaredMethod("notifyLineStatus", Context::class.java, String::class.java)
                .parameterTypes.toList(),
        )
        assertEquals(
            listOf(Context::class.java),
            FreshDataNotifier::class.java
                .getDeclaredMethod("notifyAll", Context::class.java)
                .parameterTypes.toList(),
        )
    }

    @Test
    fun `the shared scopes the app collects still exist and still carry an id`() {
        // The other half of the contract, and the half that lives in a module
        // this one cannot change. If `FreshData.Station` ever loses its
        // `stationId`, the emit above compiles into something the collector
        // cannot match a board to, and the board silently stops reloading again.
        assertEquals("940GZZLUKSX", FreshData.Station("940GZZLUKSX").stationId)
        assertEquals("piccadilly", FreshData.Line("piccadilly").lineId)
        assertTrue(FreshData.All is FreshData)
    }
}
