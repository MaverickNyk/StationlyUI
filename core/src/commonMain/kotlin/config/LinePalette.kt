package com.stationly.core.config

/**
 * Every TfL colour the app paints with: brand hex per line, per-theme legibility
 * overrides, and the roundel tint per transport mode.
 *
 * ## Why this exists
 * The same values had four independent homes — `TflLineColors` here in `core`,
 * three maps in `Board.kt`, `WidgetTheme.modeColor` in Swift, and
 * `lineIconService.ts` on the backend. `TflLineColors`' own doc comment said
 * "update BOTH files in the same commit", written when there were two.
 *
 * ## Brand and overrides are NOT the same question
 * A three-way diff found twenty of twenty-one lines identical and one that was
 * not:
 *
 * ```
 * northern     brand #000000     iOS board #888888
 * ```
 *
 * That is not drift. Pure black is the correct brand colour and it is invisible
 * on a near-black departure board. Android's notification chip wants the brand
 * value; the board wants the legible one. Both are right, so this carries a base
 * palette PLUS override layers rather than one flat map — collapsing them would
 * have to be wrong on one surface.
 *
 * ## Fallback, not failure
 * Every map here ships populated. A served palette REPLACES an entry, and an
 * absent key leaves the compiled colour standing — there is no state in which a
 * line renders colourless because the backend was unreachable.
 */
data class LinePalette(
    /** TfL's published brand colour per line id, as `#RRGGBB`. */
    val brand: Map<String, String> = DEFAULT_BRAND,
    /** Overrides applied on a DARK surface. Only lines that need one appear. */
    val dark: Map<String, String> = DEFAULT_DARK,
    /** Overrides applied on a LIGHT surface. */
    val light: Map<String, String> = DEFAULT_LIGHT,
    /** Roundel tint per transport mode, for surfaces showing a station not a line. */
    val modes: Map<String, String> = DEFAULT_MODES,
    /** The tint for a mode nobody has mapped. TfL corporate red. */
    val modeDefault: String = DEFAULT_MODE_FALLBACK,
) {
    /**
     * The BRAND colour, ignoring every theme override.
     *
     * This is what a notification chip and a server-rendered icon want: they are
     * not drawn on our board and have no idea what theme it is in. Android's
     * `FcmMessagingService` reads it through `TflLineColors.hexFor`.
     *
     * Null for bus routes and anything unmapped — TfL publishes no per-route bus
     * colour, so callers fall back to the mode tint rather than inventing one.
     */
    fun hexFor(lineId: String?): String? =
        lineId?.lowercase()?.let { brand[it] }

    /**
     * The colour to actually PAINT this line in, for a surface that knows its
     * own theme.
     *
     * Override first, brand second, null last. The order is the whole design:
     * an override exists precisely because the brand value fails on that
     * background.
     */
    fun hexForTheme(lineId: String?, isDark: Boolean): String? {
        val key = lineId?.lowercase() ?: return null
        (if (isDark) dark else light)[key]?.let { return it }
        return brand[key]
    }

    /** The roundel tint for a mode, never null — an unmapped mode gets the red. */
    fun modeHex(mode: String?): String =
        mode?.lowercase()?.let { modes[it] } ?: modeDefault

    companion object {
        // ── Keys ──
        //
        // Flat, in the home-config map, because that payload is already fetched
        // on launch, cached for cold start and offline, and republished into the
        // iOS App Group — which is the only way the widget, a process that makes
        // no network calls, can see any of this.
        const val PREFIX_LINE = "line.color."
        const val PREFIX_DARK = "line.color.dark."
        const val PREFIX_LIGHT = "line.color.light."
        const val PREFIX_MODE = "mode.color."
        const val KEY_MODE_DEFAULT = "mode.color.default"

        val DEFAULT_BRAND: Map<String, String> = mapOf(
            "bakerloo" to "#B36305",
            "central" to "#E32017",
            "circle" to "#FFD300",
            "district" to "#00782A",
            "hammersmith-city" to "#F3A9BB",
            "jubilee" to "#A0A5A9",
            "metropolitan" to "#9B0056",
            "northern" to "#000000",
            "piccadilly" to "#003688",
            "victoria" to "#0098D4",
            "waterloo-city" to "#95CDBA",
            "dlr" to "#00A4A7",
            "elizabeth" to "#6950A1",
            "lioness" to "#E2A12B",
            "mildmay" to "#1A6DB4",
            "windrush" to "#E2231A",
            "weaver" to "#7B2D8B",
            "suffragette" to "#00843D",
            "liberty" to "#6B717E",
            "tram" to "#84B817",
            "cable-car" to "#E21836",
        )

        /** See the class note: `northern` is the entry that explains the layer. */
        val DEFAULT_DARK: Map<String, String> = mapOf(
            "northern" to "#888888",
            "piccadilly" to "#3B7AE0",
            "suffragette" to "#1FB54E",
            "metropolitan" to "#D14990",
            "weaver" to "#B069BE",
            "mildmay" to "#4C95D8",
            "district" to "#2BB55D",
            "bakerloo" to "#D17F2A",
            "elizabeth" to "#9482D0",
        )

        /** The greys, which wash out on white. */
        val DEFAULT_LIGHT: Map<String, String> = mapOf(
            "northern" to "#6E6A66",
            "jubilee" to "#7A7E83",
            "liberty" to "#5A6068",
        )

        val DEFAULT_MODES: Map<String, String> = mapOf(
            "tube" to "#DC241F",
            "underground" to "#DC241F",
            "bus" to "#DC241F",
            "dlr" to "#00A4A7",
            "overground" to "#EE7C0E",
            "elizabeth" to "#6950A1",
            "elizabeth-line" to "#6950A1",
            "tram" to "#84B817",
        )

        const val DEFAULT_MODE_FALLBACK = "#DC241F"

        val DEFAULT = LinePalette()

        /**
         * A hex value the client is willing to paint with.
         *
         * Enforced because these strings go straight into a colour parser, and
         * an unparseable one is not a wrong colour but a crash or a transparent
         * pill. Anything that fails is dropped and the compiled entry stands.
         */
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        /**
         * The palette a served config describes, layered over the compiled one.
         *
         * ## The prefix trap
         * `line.color.dark.northern` also starts with `line.color.`, so a naive
         * scan of the base prefix would file "dark.northern" as a line id and
         * invent a colour for a line that does not exist. The longer prefixes are
         * therefore matched FIRST and their keys excluded from the base sweep.
         *
         * Served entries are merged over the defaults rather than replacing the
         * map, so a backend that sends one corrected line does not blank the
         * other twenty.
         */
        fun resolve(strings: Map<String, String>): LinePalette {
            if (strings.isEmpty()) return DEFAULT

            fun sweep(prefix: String, exclude: List<String> = emptyList()): Map<String, String> =
                strings.asSequence()
                    .filter { (k, _) -> k.startsWith(prefix) }
                    .filterNot { (k, _) -> exclude.any { k.startsWith(it) } }
                    .mapNotNull { (k, v) ->
                        val id = k.removePrefix(prefix).lowercase()
                        val hex = v.trim().uppercase()
                        if (id.isEmpty() || !HEX.matches(hex)) null else id to hex
                    }
                    .toMap()

            return LinePalette(
                brand = DEFAULT_BRAND + sweep(PREFIX_LINE, listOf(PREFIX_DARK, PREFIX_LIGHT)),
                dark = DEFAULT_DARK + sweep(PREFIX_DARK),
                light = DEFAULT_LIGHT + sweep(PREFIX_LIGHT),
                // `mode.color.default` is the fallback, not a mode — excluded so
                // it cannot be looked up as one.
                modes = DEFAULT_MODES + sweep(PREFIX_MODE, listOf(KEY_MODE_DEFAULT)),
                modeDefault = strings[KEY_MODE_DEFAULT]?.trim()?.uppercase()
                    ?.takeIf { HEX.matches(it) } ?: DEFAULT_MODE_FALLBACK,
            )
        }
    }
}
