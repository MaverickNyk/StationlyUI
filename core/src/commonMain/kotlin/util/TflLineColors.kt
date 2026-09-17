package com.stationly.core.util

/**
 * TfL brand colours, for callers that want the BRAND value rather than what a
 * themed surface paints.
 *
 * A thin delegate over [com.stationly.core.config.LinePalette], which owns the
 * table and adds the per-theme legibility overrides this object never had.
 *
 * ## What this used to say, and why it does not any more
 * The header here claimed to be the single source of truth and instructed
 * whoever changed a colour to update "BOTH files in the same commit". There
 * were four: this map, three maps in `Board.kt`, `WidgetTheme.modeColor` in
 * Swift, and `lineIconService.ts` on the backend. A three-way diff found twenty
 * of twenty-one lines identical and one deliberate divergence — `northern`,
 * brand black here and `#888888` on the board, because black is invisible on a
 * near-black panel. That is the case for a base palette plus overrides rather
 * than one flat map, and it is now expressed as one.
 *
 * Kept as a named object rather than folded away because Android's
 * `FcmMessagingService` calls [hexFor], and that app is frozen in production.
 */
object TflLineColors {

    /**
     * The brand colour for a line, or null when TfL publishes none.
     *
     * ## This is now a delegate, and the table moved
     * The palette lives in [com.stationly.core.config.LinePalette], which the
     * backend can retune and which also carries the per-theme legibility
     * overrides this object never had. Four copies of these hex values used to
     * exist — here, in `Board.kt`, in `WidgetTheme.swift` and in the backend's
     * `lineIconService.ts` — with a comment here instructing whoever changed one
     * to change "BOTH files", written when there were two.
     *
     * The signature is unchanged on purpose: Android's `FcmMessagingService`
     * calls this to tint a status-change notification chip, and that app is
     * frozen in production. On Android nothing ever refreshes the store, so this
     * returns the compiled brand palette — the identical values it always did.
     *
     * BRAND rather than themed, deliberately. A notification chip is drawn by
     * the system on its own background and has no idea what theme our board is
     * in; asking for a themed colour here would be asking the wrong question.
     *
     * Bus routes return null: TfL publishes no per-route colour, so callers fall
     * back to the mode tint rather than inventing one.
     */
    fun hexFor(lineId: String?): String? =
        com.stationly.core.config.LinePaletteStore.current.hexFor(lineId)
}
