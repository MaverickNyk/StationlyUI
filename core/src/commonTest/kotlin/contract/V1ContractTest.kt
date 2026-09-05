package contract

import com.stationly.core.model.FilterMode
import com.stationly.core.model.PredictionsPayload
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.toSubscribedStations
import com.stationly.core.model.user.toUserSelections
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a live `versionCode 2` Android phone depends on, pinned.
 *
 * Android v1 shipped and is still installed on real devices. v2 will be on the
 * same accounts and the same FCM topics for as long as the rollout takes, so
 * these are not tests of v2 behaviour — they are tests that v2 has not moved
 * something v1 is still standing on.
 *
 * Two things are locked here:
 *
 *  1. **The flat `stations` list**, which v2 keeps writing alongside `boards`
 *     for the transition. `syncStations` is a full REPLACE, so a list that
 *     omits a board deletes it; the fold has to be total or the dual-write is
 *     worse than not writing at all.
 *  2. **The v1 FCM payload**, which v2's ingest must still decode.
 *
 * Smithwood Close is the worked example throughout, as it is in `BoardTest`:
 * hub `490012211N`, route 39 inbound departing from pole `490008805N` and
 * outbound from `490012211N`. Every fact this file protects is invisible on
 * rail, where one naptan serves every direction of every line.
 */
class V1ContractTest {

    private val hub = "490012211N"
    private val poleIn = "490008805N"
    private val poleOut = "490012211N"

    private fun sel(
        station: String,
        parent: String,
        line: String,
        direction: String,
        mode: String = "bus",
        name: String = "Smithwood Close",
        filterMode: FilterMode = FilterMode.ALL,
        destinationIds: List<String> = emptyList(),
    ) = UserSelection(
        mode = mode,
        line = line,
        station = station,
        parentStationId = parent,
        stationName = name,
        direction = direction,
        destinations = emptyList(),
        destinationIds = destinationIds,
        filterMode = filterMode,
    )

    // ── The flat list, both ways ────────────────────────────────────────────

    @Test
    fun `a board list survives the flat wire form and comes back as the same boards`() {
        val original = Board.fromSelections(
            listOf(
                sel(poleIn, hub, "39", "inbound"),
                sel(poleOut, hub, "39", "outbound"),
                sel("940GZZLUKSX", "940GZZLUKSX", "victoria", "southbound", mode = "tube", name = "King's Cross"),
            )
        )

        val restored = Board.fromSelections(original.toSubscribedStations().toUserSelections())

        assertEquals(original.map { it.id }, restored.map { it.id })
        assertEquals(original.map { it.name }, restored.map { it.name })
        assertEquals(
            original.map { b -> b.selections.map { it.key } },
            restored.map { b -> b.selections.map { it.key } },
        )
        assertEquals(
            original.map { b -> b.selections.map { it.mode } },
            restored.map { b -> b.selections.map { it.mode } },
        )
    }

    @Test
    fun `every queue reaches the wire — the fold is total, not a sample`() {
        // The property that makes the dual-write safe rather than destructive.
        // `syncStations` diffs ids to inc/dec subscription counts, so a fold
        // that quietly drops a queue unsubscribes it on the backend.
        val boards = Board.fromSelections(
            listOf(
                sel(poleIn, hub, "39", "inbound"),
                sel(poleOut, hub, "39", "outbound"),
                sel(poleOut, hub, "85", "outbound"),
                sel("940GZZLUKSX", "940GZZLUKSX", "victoria", "southbound", mode = "tube"),
            )
        )

        assertEquals(
            boards.sumOf { it.selections.size },
            boards.toSubscribedStations().size,
        )
    }

    @Test
    fun `parentStationId is carried, and a hub stays one board because of it`() {
        // The bug this locks: two of the three hand-written conversions in the
        // v1 ViewModels built SubscribedStation WITHOUT parentStationId. A
        // station restored that way groups on its own naptan, so one bus hub
        // comes back as a card per pole, all with the same name.
        val boards = Board.fromSelections(
            listOf(
                sel(poleIn, hub, "39", "inbound"),
                sel(poleOut, hub, "39", "outbound"),
            )
        )
        assertEquals(1, boards.size, "the two poles are one hub to begin with")

        val wire = boards.toSubscribedStations()
        assertTrue(wire.all { it.parentStationId == hub }, "the hub must reach the wire")

        assertEquals(1, Board.fromSelections(wire.toUserSelections()).size)

        // And the counterfactual, so the assertion above is known to be about
        // something. Strip the hub and the same hub becomes two boards.
        val stripped = wire.map { it.copy(parentStationId = null) }
        assertEquals(
            2,
            Board.fromSelections(stripped.toUserSelections()).size,
            "without parentStationId a hub splits per pole — this is the failure being prevented",
        )
    }

    @Test
    fun `a pre-hub row round-trips to the same board it always grouped into`() {
        // v1 rows saved before hubs existed have a blank parentStationId and
        // group on `station`. `Board.id` resolves that fallback, so the wire
        // form carries the resolved value rather than the blank — the same
        // board either way, which is the only thing that has to hold.
        val boards = Board.fromSelections(listOf(sel("940GZZLUASL", "", "victoria", "northbound", mode = "tube")))
        assertEquals("940GZZLUASL", boards.single().id)

        val wire = boards.toSubscribedStations().single()
        assertEquals("940GZZLUASL", wire.parentStationId)
        assertEquals("940GZZLUASL", wire.id)

        assertEquals("940GZZLUASL", Board.fromSelections(listOf(wire).toUserSelections()).single().id)
    }

    @Test
    fun `filters are dropped by the flat form, deliberately and completely`() {
        // Not a regression. A v1 client cannot render a filtered board, so a
        // filter it cannot honour is better dropped than half-described. What
        // must NOT happen is a filter smuggled into a field v1 reads as
        // something else.
        val boards = Board.fromSelections(
            listOf(
                sel(
                    "940GZZLUASL", "940GZZLUASL", "piccadilly", "westbound",
                    mode = "tube",
                    filterMode = FilterMode.DESTINATIONS,
                    destinationIds = listOf("940GZZLUHR123", "940GZZLUUXB"),
                )
            )
        )
        assertTrue(boards.single().selections.single().filter.isActive, "the filter is real to begin with")

        val wire = boards.toSubscribedStations().single()
        assertTrue(
            listOf(wire.id, wire.name, wire.line, wire.mode, wire.direction, wire.parentStationId.orEmpty())
                .none { it.contains("940GZZLUHR123") || it.contains("940GZZLUUXB") },
            "no filter id may be smuggled into a field v1 reads as something else",
        )

        // And it comes back as ALL — "show everything" — rather than as an
        // empty filter that hides every train.
        val restored = Board.fromSelections(listOf(wire).toUserSelections()).single().selections.single()
        assertEquals(FilterMode.ALL, restored.filter.mode)
        assertTrue(restored.filter.destinationIds.isEmpty())
    }

    // ── The v1 FCM payload ──────────────────────────────────────────────────

    /** The same configuration `NetworkModule` and the stream decode with. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `a v1 FCM predictions payload still decodes`() {
        val body = """
            {"id":"940GZZLUASL","name":"Angel","lut":"2026-09-05T09:12:00Z",
             "lines":{"northern":{"id":"northern","name":"Northern",
               "dirs":{"southbound":{"preds":[
                 {"destId":"940GZZLUMDN","displayName":"Morden","platform":"Southbound - Platform 2","eta":"2026-09-05T09:14:00Z","stopLetter":null,"viaKey":"bank"},
                 {"destId":"940GZZLUKNG","displayName":"Kennington","platform":"Southbound - Platform 2","eta":"2026-09-05T09:17:00Z"}
               ]}}}}}
        """.trimIndent()

        val payload = json.decodeFromString<PredictionsPayload>(body)

        assertEquals("940GZZLUASL", payload.id)
        val preds = payload.lines.getValue("northern").dirs.getValue("southbound").preds
        assertEquals(2, preds.size)
        assertEquals("bank", preds[0].viaKey)
        // Absent on the second, and absent must mean "cannot narrow" rather than
        // a decode failure — the filter fails OPEN on it.
        assertEquals(null, preds[1].viaKey)
    }

    @Test
    fun `a payload with a null name still decodes, and does not blank the board`() {
        // Observed on device for All Saints DLR, which sends {"lines":{},"name":null}
        // when it has nothing to report. Before `name` was defaulted, every frame
        // for it failed to decode and burned the full six-second stream timeout —
        // and because REST deserialises the same model, the hedge could not
        // rescue it either. One station in this state added six seconds to every
        // pull-to-refresh.
        val payload = json.decodeFromString<PredictionsPayload>(
            """{"id":"940GZZDLALL","name":null,"lines":{}}"""
        )
        assertEquals("940GZZDLALL", payload.id)
        assertEquals("", payload.name)
        assertTrue(payload.lines.isEmpty())
    }

    @Test
    fun `a payload from a backend that predates viaKey still decodes`() {
        // v1's backend did not send viaKey. The field must be optional in the
        // direction that matters here: an old payload reaching a new client.
        val payload = json.decodeFromString<PredictionsPayload>(
            """{"id":"940GZZLUASL","lines":{"victoria":{"id":"victoria",
               "dirs":{"southbound":{"preds":[
                 {"destId":"940GZZLUBXN","displayName":"Brixton","platform":"Platform 1","eta":"2026-09-05T09:14:00Z"}
               ]}}}}}"""
        )
        val pred = payload.lines.getValue("victoria").dirs.getValue("southbound").preds.single()
        assertEquals(null, pred.viaKey)
        assertEquals("", payload.lut)
    }

    @Test
    fun `an unknown field from a newer backend does not reject the payload`() {
        // The other direction, and the one a v1 phone lives in for the whole
        // rollout: v2's backend may add a field, and a v1 client must ignore it
        // rather than drop the frame. `ignoreUnknownKeys` is what guarantees it,
        // and it is a Json setting somebody could plausibly "tidy" away.
        val payload = json.decodeFromString<PredictionsPayload>(
            """{"id":"940GZZLUASL","somethingNew":42,"lines":{"victoria":{"id":"victoria",
               "brandNew":"x","dirs":{"southbound":{"preds":[
                 {"destId":"940GZZLUBXN","displayName":"Brixton","platform":"P1","eta":"t","futureField":true}
               ]}}}}}"""
        )
        assertEquals("940GZZLUASL", payload.id)
        assertEquals("Brixton", payload.lines.getValue("victoria").dirs.getValue("southbound").preds.single().displayName)
    }
}
