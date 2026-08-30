package com.stationly.core.config

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.util.BoardTicker
import com.stationly.core.util.MultiLineBoardProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The end of the wire: a served config actually changing what the board says.
 *
 * [BoardPolicyTest] proves the map resolves and clamps. This proves the
 * resolved value reaches the tick — which is the only thing that makes the
 * whole exercise worth anything, and the part a signature change can quietly
 * break without failing a single existing test (every call site keeps
 * compiling, because the policy parameter is defaulted).
 */
class PolicyDrivenTickTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun departure(name: String, minutes: Long) = PredictionDisplay(
        destination = name,
        platform = "Platform 1",
        eta = "$minutes min",
        isDue = false,
        targetEpochMs = t0 + minutes * minute,
    )

    private fun feed(vararg preds: PredictionDisplay) = listOf(
        MultiLineBoardProcessor.Feed(
            stationId = "940GZZLUKSX",
            line = "circle",
            direction = "Eastbound",
            predictions = preds.toList(),
        ),
    )

    private fun labelsAt(
        nowMs: Long,
        policy: BoardPolicy,
        payloadWrittenAt: Long = t0,
        vararg preds: PredictionDisplay,
    ): List<String> {
        val prefs = BoardConfig(rowsPerPlatform = 3)
        return BoardTicker.tick(
            groups = MultiLineBoardProcessor.buildGroups(
                feed(*preds), isBus = false, prefs = prefs,
                rowCap = policy.rowReserve, nowMs = nowMs, policy = policy,
            ),
            nowMs = nowMs,
            payloadAgeMs = nowMs - payloadWrittenAt,
            displayRows = prefs.rowCap,
            policy = policy,
        ).flatMap { g -> g.departures.map { it.prediction.eta } }
    }

    @Test
    fun `a served grace period holds a departed train the app would have shed`() {
        // 70 seconds after its arrival: past the compiled 30s grace, inside a
        // served 120s one. The same board, the same instant, two answers.
        val now = t0 + 70_000L
        val train = departure("Edgware Road", 0)

        val compiled = labelsAt(now, BoardPolicy.DEFAULT, preds = arrayOf(train))
        assertTrue(
            compiled.isEmpty() || compiled.all { it == BoardPolicy.DEFAULT.departedLabel },
            "expected the compiled board to have shed or held it as departed, got $compiled",
        )

        val served = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_GRACE to "120000"))
        assertEquals(listOf("Due"), labelsAt(now, served, preds = arrayOf(train)))
    }

    @Test
    fun `a served label is what a retained row reads`() {
        val served = BoardPolicyStore.resolve(
            mapOf(
                BoardPolicy.KEY_LABEL to "Left",
                // Retention only engages on a payload old enough to mean it.
                BoardPolicy.KEY_RETENTION to "60000",
            ),
        )
        val now = t0 + 5 * minute
        val labels = labelsAt(now, served, payloadWrittenAt = t0, preds = arrayOf(departure("Aldgate", 0)))
        assertEquals(listOf("Left"), labels)
    }

    @Test
    fun `isGone reads the served label, so a relabelled row is still recognised`() {
        // The trap this closes: `isGone` compared against a hardcoded "Gone", so
        // a board relabelled by config reported every retained row as LIVE and
        // handed it to the hero as a train you could still catch.
        val served = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_LABEL to "Left"))
        val row = departure("Aldgate", 0).copy(eta = "Left")

        assertTrue(BoardTicker.isGone(row, served))
        assertTrue(!BoardTicker.isGone(row, BoardPolicy.DEFAULT))
    }

    @Test
    fun `the reserve the backend serves is the depth the board is built at`() {
        val served = BoardPolicyStore.resolve(mapOf(BoardPolicy.KEY_RESERVE to "6"))
        assertEquals(6, served.rowReserve)

        val preds = (1..12).map { departure("Dep $it", it.toLong()) }.toTypedArray()
        assertEquals(6, labelsAt(t0, served, preds = preds).size)
    }
}
