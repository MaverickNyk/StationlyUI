package com.stationly.core.model.user

import com.stationly.core.model.FilterMode
import com.stationly.core.model.UserSelection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One board: a place the user tracks, everything they chose to see there, and
 * how they want it shown.
 *
 * This is THE user entity. `users/{uid}.boards` is a list of these, and a board
 * is the only thing the app has that owns a station-level fact — its name, its
 * arrangement, where it sits on the home screen.
 *
 * ```
 * Board            id = the HUB, name, addedAt
 *   ├ selections   [ naptanId, line, mode, direction, filter ]
 *   └ config       expanded, view, rowsPerPlatform, pin, position
 * ```
 *
 * ## The board is the station
 * [id] is the hub the user picked — `UserSelection.groupingId`. The home screen
 * draws one card per hub, a widget is configured with one hub, and settings are
 * edited one hub at a time. A user says "I track King's Cross", not "I track six
 * things that happen to share a name".
 *
 * ## Why a selection carries its own naptan
 * A hub is a set of stops. On rail every direction of every line departs from
 * the station's own naptan and the distinction never shows; on bus it is the
 * whole problem, because each pole has its own id and which one you want depends
 * on the line AND the way you are going.
 *
 * Smithwood Close is the worked example — hub `490012211N`, route 39 inbound
 * departing from pole `490008805N` and outbound from `490012211N`. A naptan
 * stored on the BOARD would serve inbound departures on the outbound side of the
 * road. So it sits on [BoardSelection], which is the level at which a departure
 * queue — one line, one way, one stop — actually exists. The filter sits beside
 * it for the same reason: "only Heathrow services" narrows one queue and says
 * nothing about the other.
 *
 * ## What is DERIVED and never stored
 * [mode], [lines], [naptanIds] are computed from [selections]. A stored copy of
 * any of them is a second record of one fact, and the two drift the first time a
 * hub gains a line of another mode. There is no `lines` LEVEL in the schema
 * either — grouping the selections by line is one call ([byLine]) and a nesting
 * level whose only content is the key it is nested under earns nothing.
 */
@Serializable
data class Board(
    /**
     * The hub — `UserSelection.groupingId`. Equal to the stop's own naptan for
     * any mode without poles, which is most of them.
     *
     * The board's identity: exactly one board per value, everywhere.
     */
    val id: String,
    val name: String = "",
    /** Every departure queue the user tracks here. Order is the user's. */
    val selections: List<BoardSelection> = emptyList(),
    /**
     * How this board is shown — **device-local, never sent**.
     *
     * [Transient] for the same reason [widget] is, and it is the more
     * surprising of the two: appearance is the highest-frequency and
     * lowest-value state in the app, so syncing it put a backend write behind
     * every tap of a toggle and every detent of a slider, on the document every
     * login reads. It is kept per account on the device instead — see
     * `UserSettings`. The board list on the wire carries what the user TRACKS;
     * how it looks is this device's business.
     */
    @Transient
    val config: BoardConfig = BoardConfig(),
    /** First saved, epoch millis. Ties-breaks [BoardConfig.position]. */
    val addedAt: Long = 0L,
    /**
     * Whether this board is showing on a home-screen widget right now.
     *
     * **Observed, device-local, and deliberately not synced** — hence
     * [Transient], which keeps it out of both the wire payload and the local
     * config store. It is a fact about ONE phone: the same board is on a widget
     * here and not on the iPad, so last-write-wins across devices would make it
     * flap rather than converge. It is also cheap to re-derive, and is, on every
     * foreground.
     *
     * Filled in by the platform's widget probe when it can be; see
     * [WidgetPlacement] for what that probe can and cannot see.
     */
    @Transient
    val widget: WidgetPlacement = WidgetPlacement(),
) {
    /**
     * The mode shown on this board's roundel — the first selection's.
     *
     * A mixed hub has no single true answer, so the first is used: it is the one
     * the user added first, and therefore the one they think of the place as.
     */
    val mode: String get() = selections.firstOrNull()?.mode.orEmpty()

    /** The lines tracked here, first-added first. */
    val lines: List<String> get() = selections.map { it.line }.distinct()

    /** Every stop this board fetches from — one per pole on a bus hub. */
    val naptanIds: List<String> get() = selections.map { it.naptanId }.distinct()

    /**
     * Whether this board describes anything fetchable.
     *
     * A board with no selections has a name and nothing to show. Treating it as
     * ABSENT rather than empty is what stops a truncated or superseded payload
     * being destructive: `restoreBoards` falls back instead of restoring
     * nothing, and `reconcileBoards` bails instead of deleting every board on
     * the device. Both would otherwise read "the server sent boards" and act on
     * a list that says nothing.
     */
    val isUsable: Boolean get() = selections.isNotEmpty()

    /** The selections grouped by line, for anything that renders per line. */
    fun byLine(): List<Pair<String, List<BoardSelection>>> =
        selections.groupBy { it.line }.map { (line, forLine) -> line to forLine }

    /**
     * Expand into the flat rows the app runs on.
     *
     * A [UserSelection] is one (stop, line, direction) — the level a topic
     * subscription and a prediction table live at. That flat list is a
     * PROJECTION of this model, not a rival to it: the board is where the truth
     * about a place lives, and the rows are how the fetch pipeline consumes it.
     */
    fun toSelections(): List<UserSelection> = selections.map { it.toUserSelection(id, name) }

    companion object {
        /**
         * Fold the app's flat rows back into one board per station.
         *
         * [config] and [addedAt] are supplied by the caller rather than
         * defaulted, because both must be STABLE for a board that already
         * exists. Recomputing `addedAt` as "now" on every push makes every board
         * look newly added and reshuffles the restore order on each login;
         * defaulting the config drops the user's arrangement on the first sync
         * after they set it.
         *
         * `groupBy` preserves first-seen order, so a board's selections come
         * back in the order the user added them.
         */
        fun fromSelections(
            selections: List<UserSelection>,
            config: (String) -> BoardConfig = { BoardConfig() },
            addedAt: (String) -> Long = { 0L },
        ): List<Board> = selections.groupBy { it.groupingId }.map { (hub, rows) ->
            Board(
                id = hub,
                // Any row's name describes the same place, but the FIRST is the
                // one the user saw when they added it.
                name = rows.first().stationName,
                selections = rows.map(BoardSelection::from),
                config = config(hub),
                addedAt = addedAt(hub),
            )
        }
    }
}

/**
 * One departure queue on a board: a line, a direction, and the stop it leaves
 * from.
 *
 * Flat under the board rather than nested under a line, because this is the unit
 * the user actually picks and the unit everything downstream is keyed on. A line
 * level would hold nothing but the line id.
 */
@Serializable
data class BoardSelection(
    /**
     * The RESOLVED stop departures are fetched from for this exact
     * (line, direction) — not necessarily the hub the user tapped.
     *
     * The reason the schema is shaped this way; see [Board].
     */
    val naptanId: String,
    /** Canonical line id — `victoria`, `hammersmith-city`, or a bus route (`39`). */
    val line: String,
    /**
     * On the SELECTION rather than the board, because a hub can genuinely serve
     * more than one mode (a bus stop outside a tube station) and the mode is a
     * property of the line everywhere else in the app.
     */
    val mode: String = "",
    val direction: String = "",
    val filter: BoardFilter = BoardFilter(),
) {
    /** Identity of one queue — matches `UserSelection.boardKey`. */
    val key: String get() = "${naptanId}_${line}_$direction"

    fun toUserSelection(hubId: String, stationName: String) = UserSelection(
        mode = mode,
        line = line,
        station = naptanId,
        parentStationId = hubId,
        stationName = stationName,
        direction = direction,
        destinations = filter.destinationNames,
        destinationIds = filter.destinationIds,
        filterMode = filter.mode,
        viaStationIds = filter.viaIds,
        viaStationNames = filter.viaNames,
        viaKeys = filter.viaKeys,
        patternIds = filter.patternIds,
        patternNames = filter.patternNames,
        routeResolvedAt = filter.resolvedAt,
    )

    companion object {
        fun from(selection: UserSelection) = BoardSelection(
            naptanId = selection.station,
            line = selection.line,
            mode = selection.mode,
            direction = selection.direction,
            filter = BoardFilter(
                mode = selection.filterMode,
                destinationIds = selection.destinationIds,
                destinationNames = selection.destinations,
                viaIds = selection.viaStationIds,
                viaNames = selection.viaStationNames,
                viaKeys = selection.viaKeys,
                patternIds = selection.patternIds,
                patternNames = selection.patternNames,
                resolvedAt = selection.routeResolvedAt,
            ),
        )
    }
}

/**
 * How one queue is narrowed — the "passing through" and "ending at" choices.
 *
 * Both the user's INTENT and its RESOLUTION are kept, and both are needed.
 * [destinationIds] is what the render path checks, and it goes stale: engineering
 * works and branch closures change which services reach a stop. [viaIds] is the
 * question the user asked, which survives so it can be re-resolved without asking
 * them again.
 */
@Serializable
data class BoardFilter(
    /**
     * Typed rather than stringly, safely: the app's `Json` sets
     * `coerceInputValues`, so a filter kind a future build invents decodes here
     * as [FilterMode.ALL] on an older one. An unrecognised filter must show
     * everything rather than hide trains the user needed.
     */
    val mode: FilterMode = FilterMode.ALL,
    /**
     * RESOLVED allow-list: a departure's destination id must be one of these.
     * Empty means no filtering, whatever [mode] says.
     */
    val destinationIds: List<String> = emptyList(),
    /** Display names for the card subtitle, index-aligned with the ids. */
    val destinationNames: List<String> = emptyList(),
    /**
     * The stops the user asked to travel THROUGH. A list because a junction
     * gives a genuine multi-choice — both the Heathrow and Uxbridge branches at
     * Acton Town is two downstream sets unioned, not one.
     *
     * Lists on the wire, not a joined string: the device's own SQLite column
     * stores these comma-separated, which is lossy for stop names containing
     * commas, and bus stops routinely contain them.
     */
    @SerialName("viaIds")
    val viaIds: List<String> = emptyList(),
    @SerialName("viaNames")
    val viaNames: List<String> = emptyList(),
    /**
     * Branch tokens the resolution produced — the "via Bank" half of the check.
     *
     * Part of the RESOLUTION, not the intent, so it is re-derived whenever the
     * board is re-resolved. Empty means "do not narrow by branch".
     */
    @SerialName("viaKeys")
    val viaKeys: List<String> = emptyList(),
    /** Whole services taken, by pattern id. Intent, like [viaIds]. */
    @SerialName("patternIds")
    val patternIds: List<String> = emptyList(),
    /** Display names for [patternIds], index-aligned. */
    @SerialName("patternNames")
    val patternNames: List<String> = emptyList(),
    /** When [destinationIds] was last resolved from route data, epoch millis. */
    val resolvedAt: Long = 0L,
) {
    /** True when this queue is actually narrowed. */
    val isActive: Boolean get() = mode != FilterMode.ALL && destinationIds.isNotEmpty()
}

/**
 * How one board is shown — the settings the user edits on its settings screen.
 *
 * ## On the board, not in a settings map beside it
 * These used to live in `preferences.stations[hubId]`, a second structure keyed
 * by the same id as the board list. Two records of one entity can disagree, and
 * did: deleting a board left its settings behind (hence a `forget()` call that
 * every delete path had to remember), and restoring one restored a card with
 * default arrangement until a separate blob arrived. On the board, a board's
 * settings are created, restored and deleted with it, atomically, for free.
 *
 * ## Only the pieces that decide LAYOUT
 * Nothing here changes the platform GROUPING — see [BoardPin] and
 * `MultiLineBoardProcessor` for why that is the one thing the settings screen
 * does not offer. [pin] promotes a whole block; [rowsPerPlatform] bounds how
 * deep each goes; neither can reorder rows across blocks.
 */
@Serializable
data class BoardConfig(
    /**
     * Whether this board opens expanded on the home screen, every time — not
     * just the first.
     *
     * **Defaults to true, so a station the user has just added shows its
     * board.** It used to default to collapsed, and the home screen patched over
     * that by force-opening whichever station happened to sort first — a rule
     * nothing on screen expressed and no setting could undo. Adding a second
     * station then silently collapsed it, which reads as a station that failed
     * to load rather than as a layout decision.
     *
     * Several boards may set this, and with this default they all do. The height
     * budget shares the viewport between them, so the honest outcome of four
     * open boards is a page that scrolls, not four unreadable boards.
     *
     * Means nothing in [HomeLayout.CAROUSEL], where every board has a page to
     * itself and there is nothing to collapse.
     */
    val expanded: Boolean = true,
    /** Which parts of the card this board draws — see [BoardView]. */
    val view: BoardView = BoardView.FULL,
    /**
     * Ceiling on the departures shown under each platform header.
     *
     * A CEILING, not a promise: TfL sends what it sends, and a quiet platform
     * with two trains due shows two however high this is set.
     *
     * Per board rather than app-wide because the answer differs per station and
     * not per person. At the one you commute from you know your platform and
     * want it first with five trains on it; at the interchange you pass through
     * twice a year you want whatever is leaving soonest.
     */
    val rowsPerPlatform: Int = DEFAULT_ROWS_PER_PLATFORM,
    /** One block promoted to the top — see [BoardPin]. */
    val pin: BoardPin? = null,
    /**
     * Where this board sits on the home screen, as set by dragging.
     *
     * An integer rank on the board rather than a separate list of ids beside it.
     * The list form had to be reconciled with reality on every read — it could
     * name boards that no longer exist and miss ones that do — and a rank cannot:
     * it is created, moved and deleted with the board it ranks.
     *
     * [UNPOSITIONED] means "never dragged", and sorts AFTER everything that has
     * been, so a board added since the last reorder appears at the bottom rather
     * than jumping to the top. Ties fall back to [Board.addedAt].
     */
    val position: Int = UNPOSITIONED,
) {
    /**
     * [rowsPerPlatform] as the board will actually apply it.
     *
     * Clamped on READ rather than on write. A stored value can arrive from a
     * build whose limits differed, and a board that renders twelve rows because
     * an old preference said so is a worse failure than one that quietly honours
     * today's range.
     */
    val rowCap: Int get() = rowsPerPlatform.coerceIn(MIN_ROWS_PER_PLATFORM, MAX_ROWS_PER_PLATFORM)

    /** [position], with "never dragged" expressed as "last". */
    val sortKey: Int get() = if (position < 0) Int.MAX_VALUE else position

    companion object {
        /**
         * Two is the floor because one departure is not a board — it answers
         * "when is the next one" (which the hero already does) and says nothing
         * about the one after, which is what you need to decide whether to run.
         *
         * Five is the ceiling because the panel is height-capped: past five per
         * platform a two-platform station is scrolling before it has said
         * anything, and the rows the user came for are the ones off-screen.
         */
        const val MIN_ROWS_PER_PLATFORM = 2
        const val MAX_ROWS_PER_PLATFORM = 5
        const val DEFAULT_ROWS_PER_PLATFORM = 3

        /** Sorts last; see [BoardConfig.position]. */
        const val UNPOSITIONED = -1
    }
}

/**
 * Whether a board's card draws the hero above its board.
 *
 * The card is two things stacked: the HERO — one big next departure, answering
 * "what do I run for" — and the dot-matrix BOARD, answering "what else is
 * coming". The board is always drawn; the hero is the choice.
 *
 * ## Why an enum for what is currently a boolean
 * It is what the settings picker iterates ([entries]), so a view added here
 * cannot be missing from the UI, and it is what the stored config serialises —
 * a name survives reordering where an ordinal does not. Both remain true whether
 * this has two cases or four.
 *
 * ## A third case was removed
 * `NEXT_ONLY` — hero only, no board — was offered and dropped. A stored config
 * still naming it decodes to [FULL]: `UserSettings` reads with
 * `coerceInputValues`, which falls back to the property default when an enum
 * member is unknown. That is the right direction for a card whose whole purpose
 * is departures — the fallback shows more than was asked, never less.
 */
@Serializable
enum class BoardView(val label: String) {
    /**
     * Hero and board. The default, and what most stations want.
     *
     * Named "Next departure" and not "Next departure + board": this label sits
     * under a drawing of the layout that already shows the board beneath the
     * hero, so the second half restates the picture. It is also the app's own
     * phrase — the hero itself reads NEXT DEPARTURE — which is what stops this
     * screen inventing a third word ("countdown") for a thing already named.
     */
    FULL("Next departure"),

    /**
     * Board only.
     *
     * For the station you pass through rather than commute from, where the hero
     * is a large box saying something you did not ask, and the rows it costs are
     * the ones you wanted.
     */
    BOARD_ONLY("Board only");

    val showsHero: Boolean get() = this != BOARD_ONLY
}

/**
 * One block promoted to the top of the board — "show this one first".
 *
 * ## Why this promotes a BLOCK and never extracts rows
 * The obvious reading of "pin my line to the top" is a block of that line's
 * departures above everything else. That cannot be built without breaking the
 * board: a line at an interchange calls at several platforms, so its rows would
 * have to be lifted out of the platform blocks they belong to and shown under a
 * header naming no place you can stand. The rows would be in arrival order and
 * still be unactionable.
 *
 * So a pinned LINE promotes every block that carries it, in their usual order
 * among themselves, and a pinned PLATFORM or STOP promotes that one block. In
 * every case the board is still a set of places with a queue at each, which is
 * the one thing about it that must not change.
 *
 * ## One pin, not a set
 * Deliberately a single nullable value. A rank every platform can claim ranks
 * nothing, and here it has a sharper edge: pin every block and the board is
 * exactly the board you started with.
 *
 * A pin matching nothing on today's board (a platform TfL is not using this
 * evening, a line whose trains have stopped) simply does nothing. It is not
 * pruned: the platform will be back tomorrow, and silently forgetting a setting
 * the user made is worse than a setting that waits.
 */
@Serializable
data class BoardPin(
    val kind: Kind,
    /**
     * The platform LABEL verbatim ("Platform 4", "Stop C"), the pole's naptan, or
     * the canonical line id ("victoria"), depending on [kind].
     */
    val id: String,
) {
    @Serializable
    enum class Kind {
        /**
         * Matched on the platform LABEL rather than on the group key, because the
         * label is what the user picked off their own board and on rail the two
         * are the same string anyway.
         */
        PLATFORM,

        /**
         * One bus POLE, matched on its naptan — the board's own group key for
         * buses.
         *
         * A pole cannot be pinned by label the way a platform can. TfL letters
         * stops only at multi-stop interchanges, so at the ordinary stops most
         * people use every pole's label is blank, and a blank pin would match all
         * of them at once. The naptan is the only thing that tells two poles
         * apart.
         *
         * It is not a thing any user has seen, so the picker must label these
         * with something a person recognises — where the buses from that pole are
         * going. That is also the ONLY pin worth having at a hub: both sides of
         * the road usually run the same routes, so a pinned LINE there promotes
         * every pole and changes nothing.
         */
        STOP,

        LINE,
    }
}

/**
 * Whether a board is on a home-screen widget, as far as the platform will say.
 *
 * ## This is an observation, not a setting
 * A widget's station is chosen in the SYSTEM's widget editor, not in the app, so
 * nothing here is authoritative and nothing here is writable by the user. The
 * app finds out by asking the widget host, which on iOS answers with the placed
 * widgets and their sizes.
 *
 * Consequences worth knowing before trusting it: a widget added and removed
 * between two app opens leaves no trace, and [observedAt] is the moment the app
 * looked rather than the moment the user acted. A failed probe reports nothing
 * at all rather than an empty list, because "no widgets" and "could not ask"
 * must not render the same way.
 */
@Serializable
data class WidgetPlacement(
    val placed: Boolean = false,
    /** Widget sizes showing this board — iOS family names, `systemSmall` etc. */
    val families: List<String> = emptyList(),
    /** When the app last asked the widget host, epoch millis. */
    val observedAt: Long = 0L,
)
