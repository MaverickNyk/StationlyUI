package com.stationly.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The furniture every settings screen is built from.
 *
 * There are three of these screens now — home, one station, the screensaver —
 * and until this file existed each had grown its own copy of the same six
 * pieces: a section label, a caption, a bordered card, a hairline divider, a
 * navigating row and a picker tile. The copies were already drifting in padding,
 * corner radius and label alpha, which is the kind of difference a user cannot
 * name but reads as the app being assembled from parts.
 *
 * Everything here is deliberately unconfigurable beyond what two callers
 * genuinely need. A component with a knob for every difference is a second way
 * of writing the same divergence.
 */

/** Small uppercase heading above a block. */
@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

/** One quiet line under a control, saying what it does. Keep it to one line. */
@Composable
fun SettingsCaption(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
    )
}

/** A bordered surface holding a group of rows. */
@Composable
fun SettingsCard(
    borderColor: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(content = content)
    }
}

/** Hairline between rows, inset past the icon column so it reads as a group. */
@Composable
fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
    )
}

/**
 * A row that goes somewhere: icon, title, one line of subtitle, chevron.
 *
 * The chevron is not optional. Every row using this navigates — to another
 * screen, or out to iOS Settings — and a row that leads somewhere without
 * saying so is the one thing iOS users reliably expect to be told.
 *
 * Presses fill rather than scale: a full-width row shrinking pulls its own edges
 * away from the screen's, which reads as the layout breaking. See [pressHighlight].
 */
@Composable
fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .pressHighlight(onClick = onClick, enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tint)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One option in a picker, chosen by looking at it rather than by reading it.
 *
 * The [artwork] slot is the whole point: both places this is used are choosing
 * between LAYOUTS, and a layout is a picture. A sentence describing one makes
 * the user render it in their head and compare that against another sentence.
 *
 * Selection is carried by the border rather than a fill, because the artwork
 * inside already owns the tile's colour and a tinted fill behind a dark board
 * preview does nothing at all. [showTick] adds a second, redundant cue where the
 * artwork is dark enough that a 2dp border alone is easy to miss.
 */
@Composable
fun PickerTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 12.dp,
    showTick: Boolean = false,
    artwork: @Composable ColumnScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
        label = "tile_border",
    )
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, label = "tile_border_width")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(16.dp))
            .pressScale(onClick = onClick)
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = {
            artwork()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (selected) 1f else 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (showTick) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                if (showTick && selected) {
                    Box(
                        modifier = Modifier.size(16.dp).background(accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
        },
    )
}
