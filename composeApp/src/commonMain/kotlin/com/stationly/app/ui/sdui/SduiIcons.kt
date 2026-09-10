package com.stationly.app.ui.sdui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Train
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon names a payload may use, resolved to vectors.
 *
 * ## Why a fixed table and not a font or a URL
 * The alternative is serving an icon URL, which is a network round trip for a
 * 20dp glyph on a help screen that must work offline, and which cannot be tinted
 * to the theme. A closed table means the backend can only name icons this build
 * ships. The cost is that a new icon needs a release, which for a set this
 * small is the right trade.
 *
 * Unknown names fall back rather than throwing: a payload written against a
 * newer table must degrade to a generic marker, never blank the row it labels.
 */
object SduiIcons {
    fun of(name: String?): ImageVector = when (name) {
        // Gestures, for the add-a-widget walkthrough
        "touch"     -> Icons.Rounded.TouchApp
        "edit"      -> Icons.Rounded.Edit
        "add"       -> Icons.Rounded.Add
        "search"    -> Icons.Rounded.Search
        "drag"      -> Icons.Rounded.DragIndicator
        // Concepts
        "layers"    -> Icons.Rounded.Layers
        "station"   -> Icons.Rounded.Place
        "train"     -> Icons.Rounded.Train
        "grid"      -> Icons.Rounded.GridView
        "rotate"    -> Icons.Rounded.Loop
        "done"      -> Icons.Rounded.CheckCircle
        // Link rows, carried over from the about screen's own mapping
        "public"      -> Icons.Rounded.Public
        "description" -> Icons.Outlined.Description
        "email"       -> Icons.Outlined.Email
        "star"        -> Icons.Rounded.Star
        else          -> Icons.Rounded.Info
    }

    /** True when [name] resolves to something other than the fallback. */
    fun isKnown(name: String?): Boolean = name != null && of(name) !== Icons.Rounded.Info
}
