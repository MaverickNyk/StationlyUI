package com.stationly.mobile.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Measures widget text the way the widget will draw it, so a header can pick
 * the longest wording that FITS instead of wrapping or ellipsising.
 *
 * The Android counterpart of iOS's `HeaderLadder` (WidgetViews.swift), which
 * hands `MultiLineBoardProcessor.headerVariants` to `ViewThatFits` and shows the
 * first rung that fits. A RemoteViews TextView cannot choose for itself, so the
 * provider measures with the same font, size and the user's font scale, and
 * commits to a rung before it builds the view.
 */
object WidgetText {

    /** Width in dp of [text] at [sp], bold or regular, at the user's font scale. */
    fun widthDp(context: Context, text: String, sp: Float, bold: Boolean): Float {
        val metrics = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp * metrics.density * context.resources.configuration.fontScale
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        return paint.measureText(text) / metrics.density
    }

    /**
     * The first of [variants] (widest first) that fits [availableDp]; the last,
     * shortest one when none does, which the view then ellipsises. An unknown
     * width (<= 0) takes the first, so nothing gets shorter than it has to.
     */
    fun firstThatFits(
        context: Context,
        variants: List<String>,
        sp: Float,
        bold: Boolean,
        availableDp: Float,
    ): String {
        if (variants.isEmpty()) return ""
        if (availableDp <= 0f) return variants.first()
        return variants.firstOrNull { widthDp(context, it, sp, bold) <= availableDp } ?: variants.last()
    }

    /** A rung and the size to draw it at. */
    data class Fitted(val text: String, val sp: Float)

    /**
     * [firstThatFits], and when even the shortest rung is too wide, the size
     * that makes it fit, down to [minScale] of [sp], before the view ellipsises.
     *
     * iOS's `HeaderLadder` last resort (`minimumScaleFactor(0.7)`): a header
     * a little smaller beats a header missing its platform number.
     */
    fun fit(
        context: Context,
        variants: List<String>,
        sp: Float,
        bold: Boolean,
        availableDp: Float,
        minScale: Float = 0.7f,
    ): Fitted {
        val text = firstThatFits(context, variants, sp, bold, availableDp)
        if (availableDp <= 0f || text.isEmpty()) return Fitted(text, sp)
        val width = widthDp(context, text, sp, bold)
        if (width <= availableDp) return Fitted(text, sp)
        return Fitted(text, sp * (availableDp / width).coerceIn(minScale, 1f))
    }
}
