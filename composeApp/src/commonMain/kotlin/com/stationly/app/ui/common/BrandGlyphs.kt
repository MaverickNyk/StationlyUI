package com.stationly.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Third-party brand glyphs, drawn as Compose [ImageVector]s so they render
 * **independently of composeResources bundling** (which has crashed the app
 * before — see `IOS_BUILD_AND_HANDOFF.md` "⚠️ READ FIRST"). Reusable across the
 * UI (login buttons today; provider icons / widget later) so the path data lives
 * in exactly one place.
 */

private fun svg(d: String): List<PathNode> = PathParser().parsePathString(d).toNodes()

/**
 * The official 4-colour Google "G" — the exact paths from Android's
 * `res/drawable/ic_google_standard.xml`. Multi-colour, so always draw it with
 * `Image` (never tint it).
 */
val GoogleGLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "GoogleG",
        defaultWidth = 48.dp, defaultHeight = 48.dp,
        viewportWidth = 48f, viewportHeight = 48f,
    ).apply {
        addPath(svg("M24,9.5c3.54,0 6.71,1.22 9.21,3.6l6.85,-6.85C35.9,2.38 30.47,0 24,0 14.62,0 6.51,5.38 2.56,13.22l7.98,6.19C12.43,13.72 17.74,9.5 24,9.5z"), fill = SolidColor(Color(0xFFEA4335)))
        addPath(svg("M46.98,24.55c0,-1.57 -0.15,-3.09 -0.38,-4.55H24v9.02h12.94c-0.58,2.96 -2.26,5.48 -4.78,7.18l7.73,6c4.51,-4.18 7.09,-10.36 7.09,-17.65z"), fill = SolidColor(Color(0xFF4285F4)))
        addPath(svg("M10.53,28.59c-0.48,-1.45 -0.76,-2.99 -0.76,-4.59s0.27,-3.14 0.76,-4.59l-7.98,-6.19C0.92,16.46 0,20.12 0,24c0,3.88 0.92,7.54 2.56,10.78l7.97,-6.19z"), fill = SolidColor(Color(0xFFFBBC05)))
        addPath(svg("M24,48c6.48,0 11.93,-2.13 15.89,-5.81l-7.73,-6c-2.15,1.45 -4.92,2.3 -8.16,2.3 -6.26,0 -11.57,-4.22 -13.47,-9.91l-7.98,6.19C6.51,42.62 14.62,48 24,48z"), fill = SolidColor(Color(0xFF34A853)))
    }.build()
}

/**
 * The Apple logo (bitten apple + leaf), single-path so it can be tinted to the
 * button's content colour via `Icon`.
 */
val AppleLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "AppleLogo",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(
            svg("M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.476-4.494 2.585-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.624 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
