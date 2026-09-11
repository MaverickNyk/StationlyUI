package com.stationly.core.fcm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stationly.core.model.DirectionPredictions
import com.stationly.core.model.FilterMode
import com.stationly.core.model.LineData
import com.stationly.core.model.PredictionItem
import com.stationly.core.model.PredictionsPayload
import com.stationly.core.model.UserSelection
import com.stationly.core.repository.SqlStorage
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.stationly.db.StationlyDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What one FCM `Station_*` payload writes to SQLite, pinned before AV2-4.1
 * changes anything around it.
 *
 * ## Why this exists, and why it is a characterization test rather than a spec
 * `FcmMessagingService` is marked `DIVERGENT` in the gap analysis, and the
 * story that owns it says to build "a table of cases: v1 payload in, SQL writes
 * out" and make it pass against the CURRENT code first. That is the whole point:
 * these assertions record what the shipped app does today, on a database with
 * real users' rows in it, so a refactor that changes any of it has to say so out
 * loud rather than discover it on someone's phone.
 *
 * A real in-memory SQLite database, not a fake `SqlStorage`. `SqlStorage` is a
 * concrete class over the generated queries, and the behaviour most worth
 * pinning here — that `savePredictions` clears by (station, line, **direction**)
 * — lives in the SQL, not in Kotlin. A fake would assert my own understanding of
 * the delete back at me.
 *
 * ## What is deliberately NOT here
 * `FcmMessagingService` itself: it is an Android `Service` that reaches Firebase
 * in its constructor path, and standing Robolectric up for it is a bigger change
 * than this story wants. The service's own contribution is topic parsing and
 * fan-out; everything below the `syncPredictionsUseCase.execute(payload,
 * selection)` line is this file, and that line is the same one iOS reaches
 * through `ProcessPredictionsUseCase`.
 */
class PredictionSyncCharacterizationTest {

    // ── the table ────────────────────────────────────────────────────────────

    /**
     * **The one that matters.** Two directions of one line at one stop are two
     * independent boards, and a push carrying both must leave both standing.
     *
     * `savePredictions` opens with `clearPredictionsForStation(station, line,
     * direction)`. Drop the direction from either the delete or the insert and
     * the second selection's sync wipes the first's rows — a Piccadilly board at
     * King's Cross would blank its westbound half every time the eastbound half
     * updated, and only on a phone that tracks both.
     */
    @Test
    fun `two directions of one line survive each other's sync`() = withStorage { storage ->
        val payload = payload(
            station = KINGS_CROSS,
            line = "piccadilly",
            dirs = mapOf(
                "outbound" to listOf(pred("Heathrow Terminal 5", "Platform 5", in3min)),
                "inbound" to listOf(pred("Cockfosters", "Platform 6", in5min)),
            ),
        )
        val outbound = selection(KINGS_CROSS, "piccadilly", "outbound")
        val inbound = selection(KINGS_CROSS, "piccadilly", "inbound")

        SyncPredictionsUseCase(storage).let { sync ->
            runBlocking {
                sync.execute(payload, outbound)
                sync.execute(payload, inbound)
            }
        }

        val out = storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound")
        val `in` = storage.getPredictions(KINGS_CROSS, "piccadilly", "inbound")
        assertEquals(1, out.size, "outbound rows were cleared by the inbound sync")
        assertEquals(1, `in`.size, "inbound rows were cleared by the outbound sync")
        assertEquals("Heathrow Terminal 5", out.single().destination)
        assertEquals("Cockfosters", `in`.single().destination)
    }

    /**
     * A second push REPLACES the board rather than appending to it. Without the
     * clear, a station pushing every 30 seconds would grow its row count without
     * bound and the board would render departures that have already left.
     */
    @Test
    fun `a second push for the same board replaces its rows`() = withStorage { storage ->
        val sync = SyncPredictionsUseCase(storage)
        val sel = selection(KINGS_CROSS, "victoria", "inbound")

        runBlocking {
            sync.execute(
                payload(KINGS_CROSS, "victoria", mapOf("inbound" to listOf(pred("Brixton", "Platform 3", in3min)))),
                sel,
            )
            sync.execute(
                payload(KINGS_CROSS, "victoria", mapOf("inbound" to listOf(pred("Walthamstow Central", "Platform 4", in5min)))),
                sel,
            )
        }

        val rows = storage.getPredictions(KINGS_CROSS, "victoria", "inbound")
        assertEquals(1, rows.size, "the second push appended instead of replacing")
        assertEquals("Walthamstow Central", rows.single().destination)
    }

    /**
     * The payload's line and direction keys are matched case-INSENSITIVELY, in
     * both directions.
     *
     * Not defensive coding: TfL's `lineId` is lowercase and its direction keys
     * are not consistently so, and a selection persisted through
     * `saveSelection` is lowercased on the way in while a payload is not. An
     * exact match here reads as "this station has no departures" — an empty
     * board, no error, nothing in the log.
     */
    @Test
    fun `line and direction keys match regardless of case`() = withStorage { storage ->
        val payload = payload(
            station = KINGS_CROSS,
            line = "Piccadilly",
            dirs = mapOf("Outbound" to listOf(pred("Heathrow Terminal 5", "Platform 5", in3min))),
        )

        runBlocking {
            SyncPredictionsUseCase(storage)
                .execute(payload, selection(KINGS_CROSS, "piccadilly", "outbound"))
        }

        assertEquals(1, storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound").size)
    }

    /**
     * A payload with nothing for this board still stamps the sync time.
     *
     * The stamp is written BEFORE the line is looked up, deliberately: the "X
     * ago" counter on the board means "when did we last hear from the backend",
     * not "when did we last get a train". A last-train-of-the-night board that
     * stopped resetting its timer would read as a broken connection.
     */
    @Test
    fun `an empty payload still resets the last-updated stamp`() = withStorage { storage ->
        val before = Clock.System.now().toEpochMilliseconds()

        val written = runBlocking {
            SyncPredictionsUseCase(storage).execute(
                // Same station, a line this board does not ride.
                payload(KINGS_CROSS, "northern", mapOf("inbound" to listOf(pred("Morden", "Platform 1", in3min)))),
                selection(KINGS_CROSS, "piccadilly", "outbound"),
            )
        }

        assertTrue(written.isEmpty(), "a payload with no matching line must write no rows")
        val stamp = storage.getLastUpdatedTimestamp(KINGS_CROSS, "piccadilly", "outbound")
        assertNotNull(stamp, "the sync stamp was skipped when the payload had nothing for this board")
        assertTrue(stamp >= before, "the sync stamp is older than this test")
    }

    /**
     * The board's filter is resolved ONCE per push and applied in SQL, not in
     * the render path.
     *
     * `getPredictionsForStation` carries `AND matchesFilter = 1`, so the normal
     * read returns only the admitted rows. That is the whole performance
     * argument: rows are written once per push and read on every recomposition
     * and every one-second countdown tick.
     */
    @Test
    fun `a filtered board reads back only the rows it admits`() = withStorage { storage ->
        runBlocking { SyncPredictionsUseCase(storage).execute(filteredPayload(), filteredSelection()) }

        val rows = storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound")
        assertEquals(listOf("Heathrow Terminal 5"), rows.map { it.destination })
        assertTrue(rows.single().matchesFilter)
    }

    /**
     * The excluded rows are PERSISTED, not dropped, and a filter that admits
     * nothing right now falls open to the unfiltered list.
     *
     * Both halves are one behaviour. `getPredictions` falls back to
     * `getAllPredictionsForStation` when the filtered query is empty, so a
     * board filtered to a destination that has no service for the next twenty
     * minutes shows the trains that ARE coming with a note, rather than an
     * empty card that reads as a broken board. Keeping the excluded rows is
     * also what lets a filter change re-apply on device (`reapplyFilter`)
     * without waiting for a refetch.
     */
    @Test
    fun `a filter that admits nothing falls open to the whole board`() = withStorage { storage ->
        val sel = filteredSelection().copy(
            // A terminus with no service in this payload at all.
            destinations = listOf("Uxbridge"),
            destinationIds = listOf("940GZZLUUXB"),
        )
        runBlocking { SyncPredictionsUseCase(storage).execute(filteredPayload(), sel) }

        val rows = storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound")
        assertEquals(
            setOf("Heathrow Terminal 5", "Cockfosters"),
            rows.map { it.destination }.toSet(),
            "the excluded rows were dropped at write time, so there was nothing to fail open to",
        )
        assertTrue(rows.none { it.matchesFilter }, "a fail-open read must not claim these matched")
    }

    /**
     * ## DEFECT, pinned rather than asserted as correct
     *
     * Two trains 40 seconds apart both format as `"1 min"`, and **only one of
     * them reached the board**: the rider saw one Cockfosters train where two
     * were coming, and the board was a row short.
     *
     * The Kotlin side already fixed this. `SyncPredictionsUseCase` dedupes on
     * `targetEpochMs` precisely so the second train survives, and its comment
     * says the row is kept "without losing the row from SQL". It is lost from
     * SQL anyway, one layer down:
     *
     * ```sql
     * PRIMARY KEY (stationId, lineId, direction, destination, platform, eta)
     * ```
     *
     * `eta` there is the FORMATTED STRING. `insertPrediction` is
     * `INSERT OR REPLACE`, so the second train has the same key as the first
     * and overwrites it. The schema collapses on exactly the value the Kotlin
     * dedupe was changed to stop using.
     *
     * **FIXED in S016 (Q7), schema version 3.** The key is now
     * `(stationId, lineId, direction, destination, platform, eta, targetEpochMs)`
     * and `2.sqm` rebuilds the table — cheap, because everything in it is a
     * cache that FCM repopulates within seconds. `eta` stayed in the key
     * alongside the new column rather than being swapped out; the `.sq`
     * explains why, and it comes down to what a null `targetEpochMs` leaves to
     * tell two rows apart.
     *
     * **This test was the fix's own tripwire.** It asserted the WRONG behaviour
     * on purpose for three sessions — 1 row, with a message telling whoever
     * fixed the key to come here and change it — so that the schema change
     * could not land silently or half-land. It now asserts what a rider should
     * see: two trains, because two trains are coming.
     */
    @Test
    fun `two trains in the same minute bucket both survive`() = withStorage { storage ->
        val now = Clock.System.now()
        val first = (now + 70.seconds).toString()
        val second = (now + 110.seconds).toString()

        val written = runBlocking {
            SyncPredictionsUseCase(storage).execute(
                payload(
                    KINGS_CROSS, "piccadilly",
                    mapOf(
                        "outbound" to listOf(
                            pred("Cockfosters", "Platform 5", first),
                            pred("Cockfosters", "Platform 5", second),
                        ),
                    ),
                ),
                selection(KINGS_CROSS, "piccadilly", "outbound"),
            )
        }

        // The use case keeps both — the dedupe on targetEpochMs works.
        assertEquals(2, written.size, "the Kotlin dedupe regressed to the formatted eta string")
        // And SQL keeps both now. Before Q7 this read 1: the primary key ended
        // in the formatted `eta`, both trains formatted "1 min", and
        // INSERT OR REPLACE threw the first one away.
        assertEquals(
            2,
            storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound").size,
            "the prediction primary key is collapsing two real trains again — see 2.sqm",
        )
    }

    /**
     * A genuine duplicate — the same train returned twice in one payload — is
     * collapsed. The pair with the test above is the point: same destination,
     * same platform, same *exact* arrival time.
     *
     * ## This test was passing because of the defect the test above pins
     * It wrote `pred(…, in3min)` twice, and `in3min` is a `get()` — it reads the
     * clock on every access, so the two rows were milliseconds apart and were
     * never the same train at all. The old primary key ended in the FORMATTED
     * `eta`, both rounded to the same minute, and `INSERT OR REPLACE` collapsed
     * them. Green for exactly the reason Q7 exists.
     *
     * Fixing the key turned it red, which is the tripwire working: the fixture
     * now captures ONE timestamp and uses it twice, so the test asserts what its
     * name has always claimed.
     *
     * The general shape is worth keeping: **a fixture that re-derives a value
     * per use cannot express "the same thing twice"**, and a test that needs two
     * identical inputs has to be handed one value, not two calls.
     */
    @Test
    fun `the same train twice in one payload is collapsed`() = withStorage { storage ->
        // Once. See the KDoc — two reads of `in3min` are two different trains.
        val sameTrain = in3min

        runBlocking {
            SyncPredictionsUseCase(storage).execute(
                payload(
                    KINGS_CROSS, "piccadilly",
                    mapOf(
                        "outbound" to listOf(
                            pred("Cockfosters", "Platform 5", sameTrain),
                            pred("Cockfosters", "Platform 5", sameTrain),
                        ),
                    ),
                ),
                selection(KINGS_CROSS, "piccadilly", "outbound"),
            )
        }

        assertEquals(1, storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound").size)
    }

    /**
     * Platform is backend-owned and trusted verbatim, with one exception: a
     * legacy `"Unknown"` becomes blank.
     *
     * The rule that used to be here and must not come back is filling a blank
     * platform from a sibling prediction — it masked genuinely unassigned bus
     * stops by inventing a stop letter the rider could not find.
     */
    @Test
    fun `an Unknown platform is stored blank and a real one verbatim`() = withStorage { storage ->
        runBlocking {
            SyncPredictionsUseCase(storage).execute(
                payload(
                    KINGS_CROSS, "piccadilly",
                    mapOf(
                        "outbound" to listOf(
                            pred("Cockfosters", "Unknown", in3min),
                            pred("Heathrow Terminal 5", "Platform not assigned", in5min),
                        ),
                    ),
                ),
                selection(KINGS_CROSS, "piccadilly", "outbound"),
            )
        }

        assertEquals(
            mapOf("Cockfosters" to "", "Heathrow Terminal 5" to "Platform not assigned"),
            storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound")
                .associate { it.destination to it.platform },
        )
    }

    /**
     * The absolute arrival time is persisted alongside the formatted string, so
     * every surface can re-derive the countdown on its own clock between pushes
     * instead of showing "3 min" for the whole gap.
     */
    @Test
    fun `the absolute arrival time is persisted, not just the formatted eta`() = withStorage { storage ->
        // Thirty seconds past the minute, because `formatETA` FLOORS: an eta of
        // exactly +3 minutes is already 179 seconds away by the time it is
        // formatted, and reads "2 min". That is correct — it matches what the
        // platform sign does — and it is why this fixture is not `+ 3.minutes`.
        val eta = (Clock.System.now() + 3.minutes + 30.seconds)

        runBlocking {
            SyncPredictionsUseCase(storage).execute(
                payload(
                    KINGS_CROSS, "piccadilly",
                    mapOf("outbound" to listOf(pred("Cockfosters", "Platform 5", eta.toString()))),
                ),
                selection(KINGS_CROSS, "piccadilly", "outbound"),
            )
        }

        val row = storage.getPredictions(KINGS_CROSS, "piccadilly", "outbound").single()
        assertEquals(eta.toEpochMilliseconds(), row.targetEpochMs)
        assertEquals("3 min", row.eta)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * One database per test, opened and closed around it.
     *
     * Shared state between these would be the exact bug several of them are
     * about — a row surviving something that should have cleared it.
     */
    private fun withStorage(block: (SqlStorage) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        StationlyDatabase.Schema.create(driver).value
        try {
            block(SqlStorage(StationlyDatabase(driver)))
        } finally {
            driver.close()
        }
    }

    /** Half a minute of headroom so `formatETA`'s floor lands where the name says. */
    private val in3min get() = (Clock.System.now() + 3.minutes + 30.seconds).toString()
    private val in5min get() = (Clock.System.now() + 5.minutes + 30.seconds).toString()

    private fun filteredPayload() = payload(
        KINGS_CROSS, "piccadilly",
        mapOf(
            "outbound" to listOf(
                pred("Heathrow Terminal 5", "Platform 5", in3min, destId = HEATHROW),
                pred("Cockfosters", "Platform 5", in5min, destId = COCKFOSTERS),
            ),
        ),
    )

    private fun filteredSelection() = selection(KINGS_CROSS, "piccadilly", "outbound").copy(
        destinations = listOf("Heathrow Terminal 5"),
        destinationIds = listOf(HEATHROW),
        filterMode = FilterMode.DESTINATIONS,
    )

    private fun pred(
        displayName: String,
        platform: String,
        eta: String,
        destId: String = "",
    ) = PredictionItem(
        destId = destId,
        displayName = displayName,
        platform = platform,
        eta = eta,
    )

    private fun payload(
        station: String,
        line: String,
        dirs: Map<String, List<PredictionItem>>,
    ) = PredictionsPayload(
        id = station,
        lines = mapOf(
            line to LineData(
                id = line,
                dirs = dirs.mapValues { (_, preds) -> DirectionPredictions(preds) },
            ),
        ),
    )

    private fun selection(station: String, line: String, direction: String) = UserSelection(
        mode = "tube",
        line = line,
        station = station,
        stationName = "King's Cross St. Pancras",
        direction = direction,
        destinations = emptyList(),
        destinationIds = emptyList(),
    )

    private companion object {
        const val KINGS_CROSS = "940GZZLUKSX"
        const val HEATHROW = "940GZZLUHR5"
        const val COCKFOSTERS = "940GZZLUCKS"
    }
}
