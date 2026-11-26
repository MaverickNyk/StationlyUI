package com.stationly.mobile.widget

import com.stationly.core.model.UserSelection
import com.stationly.core.model.user.Board
import com.stationly.core.model.user.BoardSelection
import com.stationly.core.platform.WidgetRestore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AV2-9.7. Does the widget still fit the model the app actually runs on?
 *
 * The user and station model moved during iOS development: `boards` on the
 * profile, `BoardConfig` on the board, `UserSelection` rows keyed by a grouping
 * id, settings device-local per uid. The widget was written against that model
 * and had never been checked against it deliberately. These are the three
 * questions the story asks, in the half that a JVM test can answer.
 *
 * ## What a unit test can and cannot reach here
 * Everything in this package that matters needs a `Context`: a binding is a
 * `SharedPreferences` read, a render needs an `AppWidgetManager`, and the row
 * build needs SQLite. There is no Robolectric on this module and adding one for
 * three tests would be a bigger change than the thing it proves.
 *
 * So each question is split into the part that is pure and the part that is
 * not. The pure part is the RULE — which field a binding is matched against,
 * what a board rebuilt from the cloud carries, what a stale binding resolves to
 * on somebody else's account — and that is what is asserted. The part that is
 * not pure is asserted STRUCTURALLY instead, by reading the source the way
 * `UserFacingCopyTest` reads it: a rule stated once in a test and enforced
 * nowhere is a rule that lasts one session.
 *
 * ## The one thing every test here is really about
 * A departure board that shows the wrong stop is worse than one that shows
 * nothing. Every assertion below is a way of that going wrong quietly: half a
 * bus stop, a board that vanishes for a second during a sync, a widget that
 * outlives the account that placed it.
 */
class WidgetModelFitTest {

    // Smithwood Close, the worked example the rest of the repo uses: one hub,
    // two poles, route 39 leaving from a different one in each direction. On
    // rail the hub and the naptan are the same string and none of this shows.
    private val hub = "490012211N"
    private val poleIn = "490008805N"
    private val poleOut = "490012211N"

    private val kingsCross = "940GZZLUKSX"
    private val hackneyWick = "910GHACKNYW"

    // ── T1: the binding key is the grouping id, buses included ──────────────

    /**
     * A bus widget bound to the stop must show BOTH poles.
     *
     * This is the rule `renderWidget` runs on (`selections.filter { it.groupingId
     * == boundTo }`) stated where it can be seen. The failure it prevents has no
     * error and no empty state: the widget draws a perfectly good board with
     * half the stop on it, and the user finds out by missing the bus that leaves
     * from the other side of the road.
     */
    @Test
    fun `a bus hub is two poles and only the grouping id finds both`() {
        val selections = smithwoodClose()

        val byHub = selections.filter { it.groupingId == hub }
        assertEquals(
            "a binding names the hub, and the hub is both poles",
            listOf(poleIn, poleOut),
            byHub.map { it.station },
        )
    }

    /**
     * The same data, matched the wrong way, to show what the wrong way costs.
     *
     * `station` is the naptan departures are FETCHED from. Matching a binding
     * against it finds one pole on a bus stop and looks entirely correct while
     * doing it.
     */
    @Test
    fun `matching a binding against the fetch naptan shows half the stop`() {
        val selections = smithwoodClose()

        val byNaptan = selections.filter { it.station == hub }
        assertEquals(
            "matching the fetch naptan drops the pole on the other side of the road",
            1,
            byNaptan.size,
        )
        assertEquals(2, selections.filter { it.groupingId == hub }.size)
    }

    /** On rail the two are the same string, which is why this is never seen. */
    @Test
    fun `on rail the hub and the fetch naptan are the same string`() {
        val rail = listOf(sel(kingsCross, kingsCross, "victoria", "inbound"))

        assertEquals(rail, rail.filter { it.groupingId == kingsCross })
        assertEquals(rail, rail.filter { it.station == kingsCross })
    }

    /**
     * The structural half of T1: nobody may compare a binding against `station`.
     *
     * The behavioural tests above pin the rule; this pins every PLACE the rule
     * is applied, which is the half that drifts. There are six of them across
     * three files and they agree only because somebody checked. A seventh
     * written next month against `it.station` would compile, pass every test in
     * this module, and be invisible on any rail station anyone develops against.
     *
     * Deliberately narrow: it fires only on a comparison whose other side is a
     * binding-valued name. Code that matches a PUSHED naptan against `station`
     * is correct and is not touched, which is `WidgetRedrawTargets` and its own
     * test.
     */
    @Test
    fun `every binding comparison in the widget code is against the grouping id`() {
        val offences = mutableListOf<String>()

        BINDING_SITES.forEach { relative ->
            val file = File(repoRootFromTest(), relative)
            assertTrue("source has moved: $relative", file.isFile)
            file.readLines().forEachIndexed { index, line ->
                BINDING_COMPARISON.findAll(line).forEach { match ->
                    val field = match.groupValues.first { it == "station" || it == "groupingId" }
                    if (field == "station") {
                        offences += "$relative:${index + 1}  ${line.trim()}"
                    }
                }
            }
        }

        assertTrue(
            buildString {
                append("A widget binding was compared against UserSelection.station. ")
                append("A binding is a groupingId, which is the HUB the user picked; ")
                append("station is the pole departures are fetched from. On rail they ")
                append("are the same string and this looks fine. On a bus stop it ")
                append("shows one side of the road.\n\n")
                offences.forEach { append(it).append('\n') }
            },
            offences.isEmpty(),
        )
    }

    /** The guard is worth nothing if it matches nothing, so prove it matches. */
    @Test
    fun `the binding comparison guard actually finds the call sites it guards`() {
        val found = BINDING_SITES.sumOf { relative ->
            BINDING_COMPARISON.findAll(File(repoRootFromTest(), relative).readText()).count()
        }
        assertTrue(
            "the guard found $found binding comparisons, so its pattern has gone " +
                "stale and it is no longer guarding anything",
            found >= 4,
        )
    }

    // ── T2: a widget survives a board list reconcile ────────────────────────

    /**
     * `reconcileBoards` discards local rows and sets up cloud ones. The binding
     * is untouched by all of it, so what decides whether a widget survives is
     * whether the rebuilt rows carry the same hub.
     *
     * They do, and by construction: `BoardSelection.toUserSelection` writes the
     * board's own id into `parentStationId`, and `groupingId` reads it back.
     */
    @Test
    fun `a board rebuilt from the cloud carries its hub, so the binding still resolves`() {
        val cloud = listOf(smithwoodBoard(), railBoard(kingsCross, "victoria"))

        val rebuilt = cloud.flatMap { it.toSelections() }

        assertEquals(
            "a widget bound to the bus hub still finds both poles after a reconcile",
            2,
            rebuilt.filter { it.groupingId == hub }.size,
        )
        assertEquals(1, rebuilt.filter { it.groupingId == kingsCross }.size)
    }

    /**
     * Reordering is the ordinary reconcile: the user dragged a card on another
     * device. A hub is identity, not position, so nothing about a binding may
     * depend on where the board sits in the list.
     */
    @Test
    fun `reordering the cloud board list moves no hub`() {
        val cloud = listOf(smithwoodBoard(), railBoard(kingsCross, "victoria"), railBoard(hackneyWick, "mildmay"))

        val before = cloud.flatMap { it.toSelections() }.map { it.groupingId }.toSet()
        val after = cloud.reversed().flatMap { it.toSelections() }.map { it.groupingId }.toSet()

        assertEquals(before, after)
        assertTrue(hub in after)
    }

    /**
     * The board really is gone, and the widget says so rather than showing the
     * next one along.
     *
     * The assertion that matters is the second one: the list is NOT empty. A
     * `firstOrNull` returning null on an empty list proves nothing. Returning
     * null while two perfectly renderable boards sit beside it is the whole
     * rule, and it is the behaviour this package was built to replace.
     */
    @Test
    fun `a board that disappears leaves the widget unbound, never another station`() {
        val afterReconcile = listOf(
            sel(kingsCross, kingsCross, "victoria", "inbound"),
            sel(hackneyWick, hackneyWick, "mildmay", "inbound"),
        )

        assertNull(
            "a binding to a deleted board must resolve to nothing",
            afterReconcile.firstOrNull { it.groupingId == hub },
        )
        assertTrue(
            "and it must do so with other boards available to fall back to, or " +
                "this test is asserting nothing",
            afterReconcile.isNotEmpty(),
        )
    }

    /**
     * The gap the flag exists for, and the one way it could make things worse.
     *
     * `renderWidget` returns WITHOUT DRAWING while `WidgetRestore.inProgress` is
     * raised, which is right: a reconcile empties the table before it refills
     * it, and a redraw landing in that gap drew a bound widget as "Choose a
     * station" on a Pixel 7 Pro. The cost of getting it wrong in the other
     * direction is total, though. A flag left raised freezes every widget on the
     * phone at whatever it last drew, permanently, with no error anywhere.
     *
     * So the property worth pinning is not that it goes up. It is that it comes
     * back down when the rewrite throws, which is the case nobody exercises by
     * hand.
     */
    @Test
    fun `the mid-rewrite flag is lowered even when the rewrite throws`() = runBlocking {
        assertFalse("nothing should be restoring before this test starts", WidgetRestore.inProgress)

        var sawItRaised = false
        WidgetRestore.during { sawItRaised = WidgetRestore.inProgress }
        assertTrue("the flag has to be readable from inside the block", sawItRaised)
        assertFalse(WidgetRestore.inProgress)

        val failed = runCatching {
            WidgetRestore.during { throw IllegalStateException("the cloud read failed") }
        }
        assertTrue(failed.isFailure)
        assertFalse(
            "a reconcile that throws must not leave every widget frozen on its " +
                "last render",
            WidgetRestore.inProgress,
        )
    }

    // ── T3: sign out, then sign in as somebody else ────────────────────────

    /**
     * Characterisation, not approval.
     *
     * `UserSettings` namespaces every row it writes as `base::uid`, so signing
     * in as somebody else lands in a different namespace and reads their own
     * defaults. The widget's three stores do not do that and no sign-out path
     * clears `widget_prefs`, so `binding_7` still names the previous account's
     * station when the next person signs in on this phone.
     *
     * This test does not say that is right. It says it is the case, so that a
     * change to it is a deliberate one and shows up here rather than on a
     * stranger's home screen. What actually keeps the widget honest is the
     * resolution rule, which is the next test.
     */
    @Test
    fun `nothing in the widget prefs file is namespaced by a uid`() {
        val keys = listOf(
            WidgetBindingStore.KEY_PREFIX + 7,
            WidgetPageStore.keyFor(7),
            WidgetSettings.pinKeyFor(7),
            WidgetSettings.navKeyFor(7),
        )

        assertEquals(listOf("binding_7", "page_7", "wpin_7", "wnav_7"), keys)
        keys.forEach { key ->
            assertFalse(
                "$key carries a uid, so this test is out of date and the finding " +
                    "in AV2-9.7 needs rewriting",
                key.contains("::"),
            )
        }
    }

    /**
     * What the SECOND account sees, which is the question the story asks.
     *
     * A binding is a key into the account that is signed in RIGHT NOW, never a
     * cached station name. That single fact is what stops the worst outcome: the
     * widget cannot print the previous person's station, because the name it
     * draws is read off the row it resolved, and there is no row to read.
     *
     * So there are exactly two outcomes, both asserted here. Either the new
     * account does not track that hub and the widget is honestly unbound, or it
     * does and the widget shows THAT account's board at it. Nothing in between
     * and nothing borrowed.
     */
    @Test
    fun `a binding that outlives its account resolves against the new account or not at all`() {
        val staleBinding = kingsCross

        // The new account does not track it. Unbound, with boards to spare.
        val strangerWithoutIt = listOf(sel(hackneyWick, hackneyWick, "mildmay", "inbound"))
        assertNull(
            strangerWithoutIt.firstOrNull { it.groupingId == staleBinding },
        )

        // The new account does track it. The station ids are TfL naptans and are
        // shared by everybody, so two Londoners sharing a hub is ordinary rather
        // than a coincidence.
        val strangerWithIt = listOf(
            sel(kingsCross, kingsCross, "northern", "southbound").copy(stationName = "King's Cross"),
            sel(hackneyWick, hackneyWick, "mildmay", "inbound"),
        )
        val resolved = strangerWithIt.firstOrNull { it.groupingId == staleBinding }
        assertNotNull(resolved)
        assertEquals(
            "the name on the widget comes off the resolved row, so it is the new " +
                "account's station and never a remembered one",
            "King's Cross",
            resolved!!.stationName,
        )
        assertEquals(
            "and the line is theirs too, not the line the widget was placed for",
            "northern",
            resolved.line,
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun smithwoodClose() = listOf(
        sel(poleIn, hub, "39", "inbound", mode = "bus"),
        sel(poleOut, hub, "39", "outbound", mode = "bus"),
    )

    private fun smithwoodBoard() = Board(
        id = hub,
        name = "Smithwood Close",
        selections = listOf(
            BoardSelection(naptanId = poleIn, line = "39", mode = "bus", direction = "inbound"),
            BoardSelection(naptanId = poleOut, line = "39", mode = "bus", direction = "outbound"),
        ),
    )

    private fun railBoard(naptan: String, line: String) = Board(
        id = naptan,
        name = naptan,
        selections = listOf(
            BoardSelection(naptanId = naptan, line = line, mode = "tube", direction = "inbound"),
        ),
    )

    private fun sel(
        station: String,
        parent: String,
        line: String,
        direction: String,
        mode: String = "tube",
    ) = UserSelection(
        mode = mode,
        line = line,
        station = station,
        parentStationId = parent,
        stationName = station,
        direction = direction,
        destinations = emptyList(),
        destinationIds = emptyList(),
    )
}

/**
 * Every file that resolves a widget binding against the selection table.
 *
 * A file added to this package that does the same thing and is not listed here
 * is unguarded, which is the one weakness of a source-reading test. It is still
 * a far smaller hole than the one it closes.
 */
private val BINDING_SITES = listOf(
    "android/app/src/main/java/com/stationly/mobile/widget/DepartureWidgetProvider.kt",
    "android/app/src/main/java/com/stationly/mobile/widget/WidgetConfigureActivity.kt",
    "android/app/src/main/java/com/stationly/mobile/widget/WidgetRedrawTargets.kt",
    "android/app/src/main/java/com/stationly/mobile/MainActivity.kt",
)

/**
 * A comparison between a selection field and a binding-valued name, either way
 * round.
 *
 * The binding names are the vocabulary this package already uses for "the thing
 * a widget is pointed at": `boundTo` in the provider, `bound` in the
 * configuration screen, `wasBoundTo` in the rebind. Anything compared against
 * one of those is a binding comparison and has to be a grouping id.
 */
private val BINDING_COMPARISON = Regex(
    """\.(station|groupingId)\s*[!=]=\s*(?:boundTo|bound|wasBoundTo)\b""" +
        """|\b(?:boundTo|bound|wasBoundTo)\s*[!=]=\s*[\w.]*\.(station|groupingId)\b"""
)

/**
 * The Gradle test task runs in the module directory and an IDE may run it from
 * the repo root, so neither is safe to assume. Walk up to the one file only the
 * root has, exactly as `UserFacingCopyTest` does.
 */
private fun repoRootFromTest(): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("repo root not found above ${System.getProperty("user.dir")}")
}
