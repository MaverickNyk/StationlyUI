package contract

import com.stationly.core.model.PredictionItem
import com.stationly.core.model.UserSelection
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.model.deeplink.DeepLinkRoute
import com.stationly.core.model.deeplink.parseDeepLink
import com.stationly.core.model.user.Board
import com.stationly.core.usecase.FormatDeparturesUseCase
import java.io.File
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v1's behaviour, read back from the fixtures that recorded it.
 *
 * Written while a v1 build still exists to compare against. AV2-3.5 deletes the
 * v1 UI, and after that the only source for "what did v1 do here?" is git
 * history — which nobody consults before changing a behaviour they did not know
 * was load-bearing.
 *
 * The fixtures live in `docs/android-v2/fixtures/v1/` rather than in test
 * resources on purpose: they are read by a person deciding whether a change is
 * intended at least as often as they are read by this file. See the README
 * beside them for the rule about changing one.
 *
 * In `androidUnitTest` rather than `commonTest` only because it needs to read
 * files. Nothing here is Android-specific.
 */
class V1GoldenTest {

    private val fixtures = File(
        System.getProperty("stationly.repoRoot")
            ?: error("stationly.repoRoot is not set — see core/build.gradle.kts testOptions"),
        "docs/android-v2/fixtures/v1",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Exactly the configuration `AndroidStorageManager` reads the blob with. */
    private val storageJson = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String) =
        json.parseToJsonElement(File(fixtures, name).readText()).jsonObject

    // ── (a) the SharedPreferences blob ──────────────────────────────────────

    @Test
    fun `a v1 selections blob still decodes into the v2 model`() {
        // The SharedPreferences analogue of the database migration, and the same
        // failure mode: it is only ever exercised on a device that has been
        // running the app since before the change.
        val f = fixture("selections.json")
        val blob = f.getValue("blob").jsonPrimitive.content
        val expect = f.getValue("expect").jsonObject

        val decoded = storageJson.decodeFromString(ListSerializer(UserSelection.serializer()), blob)

        assertEquals(expect.getValue("count").jsonPrimitive.content.toInt(), decoded.size)

        // Every field v2 added must land on its default. If one of them were
        // ever made non-optional, this is where the upgrade would break.
        assertTrue(decoded.all { it.parentStationId.isBlank() })
        assertTrue(decoded.all { it.viaKeys.isEmpty() })
        assertTrue(decoded.all { it.patternIds.isEmpty() })
        assertTrue(decoded.all { it.routeResolvedAt == 0L })
        assertTrue(decoded.all { it.isUnfiltered })
    }

    @Test
    fun `a v1 blob folds to a board per POLE, and that is v1's own behaviour`() {
        // Not data loss, and AV2-4.3's fold-up must not treat it as such. A v1
        // row has no parentStationId, so groupingId falls back to `station` and
        // a bus hub's two poles are two boards — which is exactly what the user
        // sees on their phone today. The hub becomes one board only once a v2
        // client re-resolves it.
        val f = fixture("selections.json")
        val decoded = storageJson.decodeFromString(
            ListSerializer(UserSelection.serializer()),
            f.getValue("blob").jsonPrimitive.content,
        )
        assertEquals(
            f.getValue("expect").jsonObject.getValue("boardsAfterFolding").jsonPrimitive.content.toInt(),
            Board.fromSelections(decoded).size,
        )
    }

    // ── (b) the topic set ───────────────────────────────────────────────────

    // Both rules are now asserted against the SHIPPING code rather than against
    // a copy of it kept here. They used to be re-implemented in this file, which
    // meant these fixtures pinned my reading of the rule and would have gone on
    // passing if the app's own version drifted away from it. AV2-4.2 moved the
    // topic vocabulary into one place precisely so a test could point at it.
    private fun subscribeTopics(rows: List<UserSelection>): Set<String> =
        StationLifecycleUseCase.topicsFor(rows).toSet()

    private fun unsubscribeTopics(removed: UserSelection, remaining: List<UserSelection>): Set<String> =
        StationLifecycleUseCase.topicsToRelease(removed, remaining).toSet()

    private fun row(o: kotlinx.serialization.json.JsonObject) = UserSelection(
        mode = o.getValue("mode").jsonPrimitive.content,
        line = o.getValue("line").jsonPrimitive.content,
        station = o.getValue("station").jsonPrimitive.content,
        stationName = "",
        direction = o.getValue("direction").jsonPrimitive.content,
        destinations = emptyList(),
        destinationIds = emptyList(),
    )

    @Test
    fun `topic shapes are exactly what v1 emitted`() {
        fixture("topics.json").getValue("cases").jsonArray.forEach { case ->
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val rows = c.getValue("selections").jsonArray.map { row(it.jsonObject) }
            val expected = c.getValue("subscribe").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(expected, subscribeTopics(rows), "topic set changed for: $name")
        }
    }

    @Test
    fun `removing a board never unsubscribes a topic another board still needs`() {
        // The invariant. Getting this wrong silently stops departures on a board
        // the user never touched, and nothing on screen says why.
        fixture("topics.json").getValue("removalRules").jsonArray.forEach { rule ->
            val r = rule.jsonObject
            val name = r.getValue("name").jsonPrimitive.content
            val removed = row(r.getValue("removing").jsonObject)
            val remaining = r.getValue("remaining").jsonArray.map { row(it.jsonObject) }
            val expected = r.getValue("unsubscribe").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(expected, unsubscribeTopics(removed, remaining), "unsubscribe set changed for: $name")
        }
    }

    // ── (c) the widget state ────────────────────────────────────────────────

    private val format = FormatDeparturesUseCase()

    private fun selection(destinationIds: List<String> = emptyList()) = UserSelection(
        mode = "tube",
        line = "victoria",
        station = "940GZZLUKSX",
        stationName = "King's Cross St. Pancras",
        direction = "southbound",
        destinations = emptyList(),
        destinationIds = destinationIds,
    )

    private fun pred(
        minutesFromNow: Long = 5,
        displayName: String = "Brixton",
        destId: String = "940GZZLUBXN",
    ) = PredictionItem(
        destId = destId,
        displayName = displayName,
        platform = "Southbound - Platform 1",
        // +2s so a whole-minute offset does not round down across the boundary
        // while the test runs.
        eta = (Clock.System.now() + kotlin.time.Duration.parse("${minutesFromNow * 60 + 2}s")).toString(),
    )

    @Test
    fun `departures are ordered by arrival time and capped at three`() {
        // Sorting on the formatted eta STRING orders lexicographically —
        // "1 min" < "10 min" < "2 min" < "Due" — so with take(3) the widget
        // showed the wrong three trains in the wrong order.
        val state = format(listOf(pred(10), pred(2), pred(1), pred(0)), selection())
        assertEquals(listOf("Due", "1 min", "2 min"), state.predictions.map { it.eta })
        assertEquals(3, state.predictions.size)
    }

    @Test
    fun `destinations are cleaned and truncated as v1 cleaned and truncated them`() {
        val f = fixture("widget-state.json").getValue("cases").jsonArray
            .map { it.jsonObject }
            .filter { it.containsKey("destinationsIn") }

        f.forEach { case ->
            val input = case.getValue("destinationsIn").jsonArray.map { it.jsonPrimitive.content }
            val expected = case.getValue("destinationsOut").jsonArray.map { it.jsonPrimitive.content }
            val state = format(input.map { pred(displayName = it) }, selection())
            // take(3) applies, so compare only as far as the widget shows.
            assertEquals(
                expected.take(3),
                state.predictions.map { it.destination },
                case.getValue("name").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `a filter matching nothing fails open rather than blanking the widget`() {
        val state = format(
            listOf(pred(destId = "940GZZLUMDN"), pred(destId = "940GZZLUKNG")),
            selection(destinationIds = listOf("940GZZLUNOTHING")),
        )
        assertEquals(2, state.predictions.size)
    }

    @Test
    fun `a blank destId is admitted by any filter`() {
        // TfL sends "Check Front of Train" and depot moves that map to no
        // station. A plain `in` test drops them from the widget while the board
        // still shows them.
        val state = format(
            listOf(pred(destId = ""), pred(destId = "940GZZLUMDN")),
            selection(destinationIds = listOf("940GZZLUMDN")),
        )
        assertEquals(2, state.predictions.size)
    }

    @Test
    fun `the selection supplies the context, not the payload`() {
        val state = format(listOf(pred()), selection())
        assertEquals("King's Cross St. Pancras", state.stationName)
        assertEquals("victoria", state.lineName)
        assertEquals("southbound", state.direction)
        assertEquals("tube", state.mode)
        assertEquals(null, state.status)
    }

    // ── (d) the deep-link table ─────────────────────────────────────────────

    @Test
    fun `every v1 deep link routes where v1 routed it`() {
        val f = fixture("deeplinks.json")
        val scheme = f.getValue("v1Scheme").jsonPrimitive.content

        f.getValue("routes").jsonArray.forEach { entry ->
            val e = entry.jsonObject
            val url = e.getValue("url").jsonPrimitive.content
            val expected = e.getValue("route").jsonPrimitive.content
            val route = parseDeepLink(url, scheme)

            val actual = when (route) {
                is DeepLinkRoute.Home -> "Home"
                is DeepLinkRoute.PasswordResetComplete -> "PasswordResetComplete"
                is DeepLinkRoute.VerifyEmail -> "VerifyEmail"
                is DeepLinkRoute.ResetPassword -> "ResetPassword"
                is DeepLinkRoute.Unhandled -> "Unhandled"
            }
            assertEquals(expected, actual, "routing changed for $url")

            e["oobCode"]?.jsonPrimitive?.content?.let { code ->
                val got = when (route) {
                    is DeepLinkRoute.VerifyEmail -> route.oobCode
                    is DeepLinkRoute.ResetPassword -> route.oobCode
                    else -> null
                }
                assertEquals(code, got, "oobCode lost for $url")
            }
        }
    }

    @Test
    fun `the staging scheme is answered only by a staging build`() {
        // The bug iOS shipped: a hardcoded scheme meant every link into the
        // staging app was silently ignored. Nothing errored; taps did nothing.
        val f = fixture("deeplinks.json").getValue("v2Schemes").jsonObject
        val prod = f.getValue("prod").jsonPrimitive.content
        val staging = f.getValue("staging").jsonPrimitive.content

        assertEquals(DeepLinkRoute.Home, parseDeepLink("$staging://home", staging))
        assertEquals(DeepLinkRoute.Unhandled, parseDeepLink("$staging://home", prod))
        assertEquals(DeepLinkRoute.Unhandled, parseDeepLink("$prod://home", staging))
        assertEquals(DeepLinkRoute.Home, parseDeepLink("$prod://home", prod))
    }
}
