package com.stationly.core.model.sdui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Field-level validation rules carried in the SDUI model.
 * Clients evaluate these on submit — no platform-specific validation logic needed.
 */
@Serializable
data class SduiValidation(
    val required: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,     // regex — clients apply via their platform regex engine
    val errorMessage: String? = null // shown to the user when the rule fails
)

/**
 * Conditional visibility rule.
 * A component with a condition is hidden until the condition is satisfied.
 *
 * ## Two kinds of thing a condition can depend on
 * [dependsOn] originally named a FORM FIELD ("show the submit button once the
 * email input is not empty"), and that is still what the auth screens evaluate
 * it against. The widget guide needs the same rule against DEVICE FACTS
 * instead ("only show the add-a-widget steps on an OS that has the widget"),
 * so the evaluator takes a plain string map and the caller decides what fills
 * it: form inputs on the auth screens, `SduiFacts` on the guide.
 *
 * That is why this type gained no new field. A second condition type would have
 * meant a second evaluator, and the two would have drifted the moment one grew
 * an operator the other lacked.
 *
 * operator: "not_empty" | "empty" | "equals" | "not_equals" | "gte" | "lte"
 *
 * The numeric operators compare as numbers and are FALSE when either side is
 * not a number, so a fact the client does not publish hides its block rather
 * than showing it. An unknown OS version must not be told to add a widget it
 * may not be able to see.
 */
@Serializable
data class SduiCondition(
    val dependsOn: String,
    val operator: String = "not_empty",
    val value: String? = null
)

@Serializable
sealed class SduiAppComponent {
    abstract val id: String?

    @Serializable
    @SerialName("dropdown")
    data class Dropdown(
        override val id: String,
        val label: String,
        val dependsOn: String? = null,
        val dataSourceUrl: String,
        val style: String? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("button")
    data class Button(
        override val id: String,
        val label: String,
        val action: String,
        val color: String? = null,
        val enabled: Boolean = true,
        val variant: String = "primary",  // primary | secondary | ghost | danger
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String,
        val imageUrl: String,
        val contentDescription: String? = null,
        val style: String? = null,
        val textAlign: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        /**
         * Width ÷ height. When set, the box is reserved at this ratio before the
         * image arrives and nothing on the screen jumps when it lands. Null lets
         * the image size itself, which is right only for something small and
         * inline.
         */
        val aspectRatio: Float? = null,
        /** Corner radius in dp. */
        val corner: Int = 0,
        /** `fit` (whole image, letterboxed) or `fill` (cropped to the box). */
        val fit: String = "fit",
        val condition: SduiCondition? = null
    ) : SduiAppComponent()
    
    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val text: String,
        val style: String = "body",
        val textAlign: String? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("input")
    data class Input(
        override val id: String,
        val label: String,
        val placeholder: String? = null,
        val text: String? = null,
        val style: String = "text",
        val helpText: String? = null,
        val validation: SduiValidation? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("location")
    data class Location(
        override val id: String,
        val label: String,
        val icon: String? = null,
        val action: String? = null
    ) : SduiAppComponent()
    @Serializable
    @SerialName("flow_picker")
    data class FlowPicker(
        override val id: String,
        val label: String? = null,
        val dependsOn: String? = null,
        val options: List<FlowOption>
    ) : SduiAppComponent()

    @Serializable
    data class FlowOption(
        val id: String,
        val label: String,
        val icon: String? = null,
        val description: String? = null
    )

    /** A card surface wrapping child components */
    @Serializable
    @SerialName("card")
    data class Card(
        override val id: String,
        val title: String? = null,
        val body: String? = null,
        val style: String? = null,
        val components: List<SduiAppComponent> = emptyList(),
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    /** A named section grouping child components */
    @Serializable
    @SerialName("section")
    data class Section(
        override val id: String,
        val title: String? = null,
        val components: List<SduiAppComponent> = emptyList(),
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    /** A tappable row that opens a URL */
    @Serializable
    @SerialName("link_row")
    data class LinkRow(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val url: String,
        val icon: String? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("divider")
    data class Divider(
        override val id: String? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    @Serializable
    @SerialName("spacer")
    data class Spacer(
        override val id: String? = null,
        val size: Int = 8,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    /** A dismissible announcement banner shown on the home screen */
    @Serializable
    @SerialName("announcement")
    data class Announcement(
        override val id: String,
        val title: String,
        val body: String,
        val variant: String = "info",   // info | warning | tip
        val dismissKey: String? = null,
        val url: String? = null
    ) : SduiAppComponent()

    // ── Native slots ────────────────────────────────────────────────────────
    //
    // Two components the server places but cannot write as prose: a recording
    // of the gesture, and a count of what the reader already has on their home
    // screen. Everything else on the guide is text the backend fully authors.
    //
    // The alternative considered and rejected was an HTML block in a WKWebView.
    // It does not follow the theme tokens, cannot read the widget count the app
    // already holds, and shows nothing at all on the offline launch where
    // somebody is most likely to be looking for help. `PlatformWebView` remains
    // available if long-tail help copy ever wants a page of its own; the guide
    // itself is not that.

    /**
     * A picture or a short looping recording of the gesture being performed.
     *
     * ## Why frames and not a .gif
     * Coil decodes GIFs on Android only; on iOS `coil-gif` has no decoder, so a
     * `.gif` URL would arrive as a still first frame or nothing at all. Shipping
     * one would have meant bridging ImageIO from Kotlin/Native to build an
     * animated `UIImage`, for a file format that also compresses worse than the
     * frames it is made of.
     *
     * So the wire carries [frames], an ordered list of ordinary image URLs that
     * every platform can already load, advanced by the client at [frameMs]. The
     * authoring flow is unchanged: record a GIF, run `scripts/demo_frames.py`,
     * paste the JSON it prints.
     *
     * Set [url] alone for a still. Set [frames] for a demo. If both are set the
     * frames win and [url] is the poster shown until they load, which is what
     * keeps the block from being a blank rectangle on a slow connection.
     *
     * [aspectRatio] is width/height and is REQUIRED to be right: the box is
     * reserved before the first image arrives, and a wrong ratio makes the whole
     * screen jump when it does.
     */
    @Serializable
    @SerialName("demo")
    data class Demo(
        override val id: String,
        val url: String? = null,
        val frames: List<String> = emptyList(),
        val frameMs: Int = 120,
        val loop: Boolean = true,
        val aspectRatio: Float = 1f,
        val caption: String? = null,
        /**
         * Corner radius in dp. A screenshot that already has rounded corners
         * baked into its pixels needs this to MATCH, or the box clips a second
         * curve inside the first and the result reads as a mistake. 14 is the
         * app's own card radius and the right default for a bare recording.
         */
        val corner: Int = 14,
        /** `fit` (whole frame, letterboxed) or `fill` (cropped to the box). */
        val fit: String = "fit",
        /**
         * Hex fill behind the media, e.g. `#000000`. A widget screenshot is a
         * black panel, so letterboxing it against the default translucent grey
         * puts a visible frame around something that should look like it is
         * simply sitting there. Blank keeps the neutral loading ground.
         */
        val background: String? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    // ── Layout containers ───────────────────────────────────────────────────
    //
    // `Card` and `Section` group things but only ever stack them. These two are
    // what make the payload's LAYOUT server-driven rather than just its
    // contents: the backend can put three shots side by side, or two cards in a
    // grid, and change that arrangement without a release.

    /**
     * Children laid out horizontally.
     *
     * A child with a [weight] above zero takes that share of the leftover
     * width; children with no weight size themselves first. All-unweighted is
     * therefore a plain row, and equal weights are equal columns.
     */
    @Serializable
    @SerialName("row")
    data class Row(
        override val id: String,
        val components: List<SduiAppComponent> = emptyList(),
        val weights: List<Float> = emptyList(),
        val gap: Int = 12,
        /** `top` | `center` | `bottom` */
        val align: String = "top",
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    /**
     * Children in a fixed number of equal columns, wrapping onto as many rows as
     * it takes.
     *
     * Not a lazy grid: a guide screen has a handful of items and already sits in
     * a scrolling column, and nesting a lazy grid inside one is the classic
     * infinite-height crash.
     */
    @Serializable
    @SerialName("grid")
    data class Grid(
        override val id: String,
        val columns: Int = 2,
        val components: List<SduiAppComponent> = emptyList(),
        val gap: Int = 12,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    /**
     * Two or more panes behind a segmented control, one visible at a time.
     *
     * The reason this exists rather than more sections down one page: the guide
     * answers two unrelated questions, "how do I get one" and "how do I get
     * several", and only one of them is ever the reader's. Stacked as sections,
     * the second is a wall of text below the answer somebody came for. As tabs,
     * the wrong one costs nothing.
     *
     * Tab state is client-side and resets on leaving the screen. A remembered
     * tab would mean somebody who once read about stacking lands there next time
     * with the add instructions hidden.
     */
    @Serializable
    @SerialName("tabs")
    data class Tabs(
        override val id: String,
        val tabs: List<SduiTab> = emptyList(),
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    /**
     * A numbered walkthrough, each step optionally carrying its own picture.
     *
     * Not a [Card] of [Text]s: the numbering is the content. A gesture performed
     * on the HOME SCREEN, with the app closed, has to be memorised in order
     * before the reader leaves, and an unordered paragraph does not survive the
     * trip out of the app.
     */
    @Serializable
    @SerialName("steps")
    data class Steps(
        override val id: String,
        val title: String? = null,
        val steps: List<SduiStep> = emptyList(),
        val condition: SduiCondition? = null
    ) : SduiAppComponent()

    /**
     * One line of the reader's current state, e.g. "2 widgets on your Home
     * Screen".
     *
     * The client substitutes `{count}` into whichever of the three templates
     * matches, so the backend controls the wording and the grammar without
     * knowing the number. This is what keeps the guide from reading as an
     * advert: it opens by telling the user what they already have rather than by
     * asking them for something.
     *
     * [fact] names an entry in the same map the conditions resolve against.
     * see `SduiFacts`.
     */
    @Serializable
    @SerialName("stat_row")
    data class StatRow(
        override val id: String,
        val fact: String,
        val zero: String,
        val one: String,
        val many: String,
        val icon: String? = null,
        val condition: SduiCondition? = null
    ) : SduiAppComponent()
}

/**
 * One numbered step of an [SduiAppComponent.Steps] walkthrough.
 *
 * The media fields are the same contract as [SduiAppComponent.Demo] and for the
 * same reason. See its docstring for why frames rather than a `.gif`.
 */
@Serializable
data class SduiStep(
    val title: String,
    val body: String? = null,
    /**
     * An icon name from `SduiIcons`, replacing the step number.
     *
     * A number says "third of four"; an icon says what the step IS, and the two
     * carry different halves of the same instruction. The icon wins here because
     * the reader performs these steps on the Home Screen with the app closed,
     * where a remembered shape survives better than a remembered ordinal. The
     * connecting rule still carries the sequence. Unset falls back to the
     * number, so a payload that names no icons is still a numbered list.
     */
    val icon: String? = null,
    /** Hex tint for the marker, e.g. `#4CAF50`. Defaults to the board amber. */
    val tint: String? = null,
    val url: String? = null,
    val frames: List<String> = emptyList(),
    val frameMs: Int = 120,
    val aspectRatio: Float = 1f,
    val corner: Int = 14,
    val fit: String = "fit",
    val background: String? = null
)

/** One pane of an [SduiAppComponent.Tabs]. */
@Serializable
data class SduiTab(
    val title: String,
    val icon: String? = null,
    val components: List<SduiAppComponent> = emptyList()
)

/**
 * Flat string-map returned by /sdui/app/home-config.
 * Lets the server override any hardcoded label in the home / explore / empty-state UI.
 */
@Serializable
data class SduiStrings(
    val id: String,
    val strings: Map<String, String> = emptyMap()
)

@Serializable
data class SduiAppTheme(
    val primaryColor: String? = null,
    val backgroundColor: String? = null
)

/**
 * App-wide theme tokens returned by `GET /sdui/app/theme-tokens`. Each value
 * is an optional hex string; missing keys fall back to the app's hardcoded
 * defaults. The Android side caches the last successful response in
 * SharedPrefs so the app boots with the latest known palette even offline.
 *
 * Three buckets:
 *   - [light]     overrides applied when the app is in light theme
 *   - [dark]      overrides applied when the app is in dark theme
 *   - [constants] theme-independent tokens (logo red, the dot-matrix amber)
 *
 * Per-bucket keys (all optional, all hex `#RRGGBB`):
 *   canvas, card, cardElevated, scrim,
 *   textPrimary, textMuted, textSubtle,
 *   borderSubtle, borderStrong,
 *   primary, onPrimary, primaryContainer, onPrimaryContainer,
 *   success, warning, error, info, due, live
 *
 * Constants bucket keys:
 *   brandSignage, roundelRed
 */
@Serializable
data class SduiThemeTokens(
    val id: String = "app_theme_tokens",
    val version: Int = 1,
    val light: Map<String, String> = emptyMap(),
    val dark: Map<String, String> = emptyMap(),
    val constants: Map<String, String> = emptyMap(),
)

@Serializable
data class SduiAppScreen(
    val id: String,
    val title: String,
    val theme: SduiAppTheme? = null,
    val components: List<SduiAppComponent>,
    val loadingMessage: String? = null,
    val successMessage: String? = null
)

@Serializable
data class SduiDropdownOption(
    val id: String,
    val label: String,
    val iconUrl: String? = null,
    val secondaryLabel: String? = null,
    val color: String? = null,
    val tags: List<String>? = null,  // TfL line brand colors (hex) for the lines serving this station
    // Mode-only fields — populated when /modes is the data source.
    // tintHex backs the widget + dream station-row roundel tint when no
    // cached icon is present yet. iconVersion is used by ModeIconCache
    // to invalidate stale icons when the backend bumps its asset bundle.
    val tintHex: String? = null,
    val iconVersion: String? = null,
    /**
     * Line-only: the short display form ("Picc.", "H&C"), served by the lines
     * API and cached into [com.stationly.core.util.LineNameStore].
     *
     * Same shape of contract as [tintHex] above — display metadata the backend
     * owns, learned when the option list is fetched and kept for the surfaces
     * that need it later. Null for every non-line dropdown, for bus routes
     * (whose id is already the shortest true label), and for any backend that
     * predates the field; all three fall back to
     * [com.stationly.core.util.LineShortNames]' local table.
     */
    val shortName: String? = null,
    val upcomingStations: List<String>? = null,
    /**
     * The same stops as [upcomingStations] — same order, same length — carrying
     * the naptan id as well as the name.
     *
     * Filters match on `id`, never on the name: the route sequence calls a stop
     * "Hammersmith (Dist&Picc Line)" where a live prediction says "Hammersmith",
     * so name comparison fails on exactly the short-terminating services a
     * "via" filter has to catch.
     *
     * Nullable because a cached 24h payload (or an older backend) predates the
     * field; callers fall back to [upcomingStations] for display and simply
     * cannot offer id-accurate filtering until it arrives.
     */
    val upcomingStops: List<SduiRouteStop>? = null,
    val directionName: String? = null,
    val towards: String? = null,
    val destinations: List<SduiDropdownOption>? = null,
    /**
     * Every DISTINCT service pattern from the origin, none collapsed.
     *
     * [destinations] is keyed on terminus, so two routes ending at the same
     * place become one chip and the shorter is deleted — which is how "Morden
     * via Charing Cross" disappeared, and how Chigwell, Grange Hill and Roding
     * Valley became unreachable on the Central line map. This list keeps them.
     *
     * Nullable because it is additive: an older backend, or a payload cached
     * before it existed, has none. Callers fall back to [destinations], which
     * behaves exactly as it always did.
     */
    val patterns: List<SduiRoutePattern>? = null,
    /** Station this route sequence is relative to; every stop list starts AFTER it. */
    val originStationId: String? = null,
)

/**
 * One way a train can actually run from the origin, in this direction.
 *
 * The unit the MAP is built from and the FILTER resolves against. Distinct from
 * a destination chip in exactly one way that matters: two patterns may share a
 * [terminusId] and differ in [viaKey], which is the case a terminus-keyed model
 * cannot express and the reason branch filtering was wrong.
 */
@Serializable
data class SduiRoutePattern(
    /** Unique within one direction. Terminus, plus the branch where there is one. */
    val id: String,
    val terminusId: String,
    val terminusName: String,
    /** TfL's own words — "Bank", "Charing Cross" — or null when unbranched. */
    val via: String? = null,
    /**
     * The comparable form of [via], matched against a departure's
     * [com.stationly.core.model.PredictionItem.viaKey].
     *
     * Null means TfL published nothing to tell this pattern apart from its
     * siblings — four Metropolitan runs to Aldgate differ only in whether they
     * call at Willesden Green. Read it as "cannot narrow", never as "no match".
     */
    val viaKey: String? = null,
    /** "Morden via Bank" — how the branch is named on the map. */
    val label: String,
    /** Stops after the origin, in order. */
    val stops: List<SduiRouteStop> = emptyList(),
)

/** One stop on a route sequence: the naptan id to match on, plus a display name. */
@Serializable
data class SduiRouteStop(
    val id: String,
    val name: String,
)

@Serializable
data class SubscribedStation(
    val id: String, // the RESOLVED naptanId departures are fetched from
    val name: String,
    val line: String,
    val mode: String,
    val direction: String,
    /**
     * The hub this board belongs to, so a restore rebuilds one card per stop
     * rather than one per pole. Nullable: the backend may not persist it yet, in
     * which case restore falls back to grouping on [id] — the pre-hub behaviour.
     */
    val parentStationId: String? = null,
)

@Serializable
data class DeviceInfo(
    val platform: String? = null,    // "android" | "ios" | "web"
    val osVersion: String? = null,   // e.g. "Android 14 (SDK 34)"
    val model: String? = null,       // e.g. "Google Pixel 8"
    val appVersion: String? = null   // e.g. "1.0-staging"
)

@Serializable
data class SyncProfileRequest(
    val uid: String,
    val email: String,
    val displayName: String? = null,
    val photoURL: String? = null,
    val signInProvider: String? = null,
    // Stable per-install device id — lets the backend track active device
    // sessions so subscription counts only release on the last device's logout.
    val deviceId: String? = null,
    // Optional device metadata stored alongside the session for that deviceId.
    val deviceInfo: DeviceInfo? = null
)

@Serializable
data class SyncStationsRequest(
    val uid: String,
    val stations: List<SubscribedStation>,
    /** This device, so the server's fan-out skips it. See [SyncBoardsRequest.deviceId]. */
    val deviceId: String? = null,
)

@Serializable
data class UserProfileResponse(
    val uid: String,
    val email: String,
    val displayName: String,
    val photoURL: String? = null,
    val address: String? = null,
    /**
     * LEGACY board list — what Android reads and writes.
     *
     * iOS must NOT reconcile against this: Android replaces it wholesale on
     * every board setup, so a shared account's iOS boards are not in it. Use
     * [boards]. See [com.stationly.core.model.user.Board] for the full account
     * of why the two lists exist.
     */
    val stations: List<SubscribedStation>,
    /**
     * The real board list — what iOS reads and writes.
     *
     * The backend guarantees this is present on read, deriving it from
     * [stations] for an account that has only ever used Android, so a user's
     * first iOS login restores the board they already had. Defaulted here as
     * well, because a backend that predates the field simply omits it and an
     * older response must not fail to decode.
     */
    val boards: List<com.stationly.core.model.user.Board> = emptyList(),
    /** LWW guard for [boards] — the device clock of the last accepted write. */
    val boardsUpdatedAt: Long = 0L,
    /**
     * The account's revision — bumped by the server on every CONTENT write and
     * never on session or device churn.
     *
     * This is the value a client stores as its `localRev` after applying a
     * profile, and compares against on every foreground. When it has not moved
     * there is nothing to fetch, which is what takes an app open on an unchanged
     * account from one Firestore read to zero.
     *
     * Defaulted, because a backend that predates the field omits it and an older
     * response must still decode. Zero then reads as "no revision known", and a
     * client holding zero simply fetches once — the safe direction.
     */
    val stateRev: Long = 0L,
    /**
     * Voluntary-contribution status, or null for an account that has never
     * contributed.
     *
     * ## Why this lives in `core` at all
     * The support feature was built to avoid this module on purpose: its whole
     * payload rides `home-config` as a JSON string precisely so the module the
     * frozen Android app depends on would not have to change. That held while
     * the badge was device-local. It stops holding the moment the badge has to
     * be the same on every device the account is signed in to, because the only
     * thing every device already fetches and trusts is this response.
     *
     * The change is additive and costs Android nothing: it is one optional
     * field with a default, referenced by no Android code, so that module
     * compiles unchanged and an older client simply ignores a key it has never
     * heard of.
     */
    val supportMoney: SupportMoneyView? = null,
)

/**
 * What the server says about this account's contributions.
 *
 * ## One boolean, decided once, by the side that owns the number
 * [isActiveSupporter] is not derived here and must not be: the badge window is
 * a server setting (`SUPPORT_MONEY_BADGE_DURATION_DAYS`), and a client applying
 * its own copy of it would disagree with the server the moment an operator moved
 * it. So the window is never sent. The client renders the boolean.
 *
 * ## There is no history here and there is not going to be
 * [entries] carries the single most recent contribution, or nothing. The server
 * keeps every row so payments can be reconciled against Stripe; the client is
 * given one, because one is all the badge is measured from. An array rather than
 * a nullable object so the shape does not change when it is empty.
 */
@Serializable
data class SupportMoneyView(
    /** Is the Supporter mark showing right now? The only field the UI branches on. */
    val isActiveSupporter: Boolean = false,
    /** Lifetime contributions. Powers "you've done this {n} times" copy only. */
    val count: Int = 0,
    /** The most recent contribution, or empty. Never more than one. */
    val entries: List<SupportMoneyEntry> = emptyList(),
)

/** One contribution, as the server serves it. */
@Serializable
data class SupportMoneyEntry(
    /** The Stripe Checkout Session id. Present so a support query can be traced; never displayed. */
    val txnId: String = "",
    /** Epoch ms the contribution landed. */
    val atMs: Long = 0L,
    /** Minor units. */
    val amountMinor: Int = 0,
    val currency: String = "GBP",
)

/** Body of `POST /user/sync/boards`. */
@Serializable
data class SyncBoardsRequest(
    val boards: List<com.stationly.core.model.user.Board>,
    /** Device clock, epoch millis. The server drops a write older than stored. */
    val updatedAt: Long,
    /**
     * Permission to replace a stored board list with an EMPTY one.
     *
     * The endpoint is a full replacement, so `boards: []` is indistinguishable
     * from "delete everything this account has" — and both platforms have a
     * window where their local list is legitimately empty but not authoritative
     * (the login path wipes local SQL before restoring from the cloud). Without
     * an explicit signal the server has to guess, and guessing wrong once costs
     * the user every board on every device.
     *
     * Defaults to false, so a client that has never heard of this field cannot
     * clear an account by omission. Ignored entirely when [boards] is non-empty.
     */
    val allowEmpty: Boolean = false,
    /**
     * This device, so the server's `user.sync` fan-out skips it.
     *
     * Advisory and never authorising: the server takes the account from the
     * bearer token and uses this only to decide who NOT to wake. The worst a
     * wrong value can do is wake this device or fail to wake it — one redundant
     * reconcile either way, and reconciles are idempotent.
     *
     * The server has accepted this since before any client sent it, so shipping
     * it changes only who gets pushed, never whether the write lands.
     */
    val deviceId: String? = null,
)

/**
 * Response to either sync-write.
 *
 * [applied] false with a 200 is the normal, expected outcome of a stale write,
 * NOT an error: the server is telling the client its copy is behind. A client
 * that treats it as a failure would retry the same stale payload forever.
 */
@Serializable
data class SyncStateResponse(
    val success: Boolean = false,
    val applied: Boolean = true,
    val reason: String? = null,
    /**
     * The account revision this write produced, for the writer's own use.
     *
     * Stamped into `localRev` so this device does not turn round on its next
     * foreground and fetch the profile it just wrote. `excludeDeviceId` keeps the
     * PUSH away, but nothing else would stop the rev check noticing that the
     * account moved and going to look.
     *
     * The server sends an optimistic value: at least the true revision minus the
     * effect of any write racing this one, never more. Undershooting only ever
     * causes one extra fetch, and if a concurrent write really did land there is
     * something to fetch. Null from a backend that predates the field.
     */
    val rev: Long? = null,
)

/** Body of `GET /user/state/rev` — the rev gate's server half. */
@Serializable
data class UserStateRevResponse(
    val uid: String,
    val rev: Long = 0L,
)
