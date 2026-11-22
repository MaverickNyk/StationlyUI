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
}
