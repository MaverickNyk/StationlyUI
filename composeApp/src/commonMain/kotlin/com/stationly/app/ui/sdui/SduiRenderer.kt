package com.stationly.app.ui.sdui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.core.model.sdui.SduiAppComponent

// Theme-aware so SDUI content flips correctly in light/dark — matches Android's
// SduiComponentRenderer.kt exactly (was hardcoded dark on iOS, broke light mode).
private val Surface1 @Composable get() = MaterialTheme.colorScheme.surface
private val White90  @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
private val White55  @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
private val White25  @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
private val White08  @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

/**
 * Renders any SduiAppComponent in the profile/about SDUI context.
 * For login/selection forms, the dedicated screen composables handle rendering inline.
 */
@Composable
fun SduiRenderer(
    component: SduiAppComponent,
    onLinkOpen: (String) -> Unit = {},
    onAction: (String) -> Unit = {},
    /**
     * Device facts, for the components that render one, today just
     * [SduiAppComponent.StatRow]. Empty by default so every existing caller
     * (the profile/about screen) is unchanged; a stat row with no facts behind
     * it renders its zero copy, which is the correct reading of "this client
     * cannot tell you".
     *
     * Visibility conditions are NOT applied here. They are resolved once per
     * screen by `visibleFor`, before the list reaches this function, so a
     * hidden component is never handed to a renderer at all.
     */
    facts: Map<String, String> = emptyMap()
) {
    when (component) {
        is SduiAppComponent.Text -> SduiText(component)
        is SduiAppComponent.Card -> SduiCard(component, onLinkOpen, onAction, facts)
        is SduiAppComponent.Section -> SduiSection(component, onLinkOpen, onAction, facts)
        is SduiAppComponent.LinkRow -> SduiLinkRow(component, onLinkOpen)
        is SduiAppComponent.Button -> SduiButton(component, onAction)
        is SduiAppComponent.Divider -> HorizontalDivider(color = White08, modifier = Modifier.padding(vertical = 4.dp))
        is SduiAppComponent.Spacer -> Spacer(Modifier.height(component.size.dp))
        is SduiAppComponent.Announcement -> SduiAnnouncementBanner(component, onLinkOpen)
        is SduiAppComponent.Demo -> SduiDemoMedia(
            url = component.url,
            frames = component.frames,
            frameMs = component.frameMs,
            loop = component.loop,
            aspectRatio = component.aspectRatio,
            caption = component.caption,
            corner = component.corner,
            fit = component.fit,
            background = component.background,
        )
        is SduiAppComponent.Image -> SduiImage(component)
        is SduiAppComponent.Row -> SduiRow(component, onLinkOpen, onAction, facts)
        is SduiAppComponent.Grid -> SduiGrid(component, onLinkOpen, onAction, facts)
        is SduiAppComponent.Tabs -> SduiTabs(component, onLinkOpen, onAction, facts)
        is SduiAppComponent.Steps -> SduiSteps(component)
        is SduiAppComponent.StatRow -> SduiStatRow(component, facts)
        else -> {} // Dropdown, Input, Location, FlowPicker handled inline in their screens
    }
}

@Composable
private fun SduiText(component: SduiAppComponent.Text) {
    val (color, size, weight) = when (component.style) {
        "title"    -> Triple(White90, 20.sp, FontWeight.Bold)
        "subtitle" -> Triple(White55, 16.sp, FontWeight.SemiBold)
        "caption"  -> Triple(White25, 12.sp, FontWeight.Normal)
        "amber"    -> Triple(MaterialTheme.colorScheme.primary, 16.sp, FontWeight.SemiBold)
        else       -> Triple(White55, 14.sp, FontWeight.Normal)
    }
    val align = when (component.textAlign) {
        "center" -> TextAlign.Center
        "end"    -> TextAlign.End
        else     -> TextAlign.Start
    }
    Text(
        text = component.text,
        color = color,
        fontSize = size,
        fontWeight = weight,
        textAlign = align,
        lineHeight = (size.value * 1.5f).sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SduiCard(
    component: SduiAppComponent.Card,
    onLinkOpen: (String) -> Unit = {},
    onAction: (String) -> Unit = {},
    facts: Map<String, String> = emptyMap()
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, White08)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            component.title?.let {
                Text(it, color = if (component.style == "brand") MaterialTheme.colorScheme.primary else White90,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            component.body?.let {
                Text(it, color = White55, fontSize = 13.sp, lineHeight = 19.sp)
            }
            component.components.forEach { child ->
                SduiRenderer(component = child, onLinkOpen = onLinkOpen, onAction = onAction, facts = facts)
            }
        }
    }
}

@Composable
fun SduiSection(
    component: SduiAppComponent.Section,
    onLinkOpen: (String) -> Unit = {},
    onAction: (String) -> Unit = {},
    facts: Map<String, String> = emptyMap()
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        component.title?.let {
            Text(
                it.uppercase(), color = White25,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        component.components.forEach { child ->
            SduiRenderer(component = child, onLinkOpen = onLinkOpen, onAction = onAction, facts = facts)
        }
    }
}

@Composable
private fun SduiLinkRow(component: SduiAppComponent.LinkRow, onLinkOpen: (String) -> Unit) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, White08),
        modifier = Modifier.fillMaxWidth().clickable { onLinkOpen(component.url) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(component.title, color = White90, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                component.subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, color = White55, fontSize = 12.sp)
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew, null,
                tint = White25, modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SduiButton(component: SduiAppComponent.Button, onAction: (String) -> Unit) {
    val isEnabled = component.enabled
    when (component.variant) {
        "secondary" -> OutlinedButton(
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else White08),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) { Text(component.label, fontWeight = FontWeight.SemiBold) }

        "ghost" -> TextButton(
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = White55)
        ) { Text(component.label) }

        "danger" -> Button(
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444), contentColor = Color.White)
        ) { Text(component.label, fontWeight = FontWeight.Bold) }

        else -> Button( // primary
            onClick = { onAction(component.action) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { Text(component.label, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SduiAnnouncementBanner(
    component: SduiAppComponent.Announcement,
    onLinkOpen: (String) -> Unit
) {
    val borderColor = when (component.variant) {
        "warning" -> Color(0xFFFFB300).copy(alpha = 0.5f)
        "tip"     -> Color(0xFF4CAF50).copy(alpha = 0.5f)
        else      -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    }
    val accentColor = when (component.variant) {
        "warning" -> Color(0xFFFFB300)
        "tip"     -> Color(0xFF4CAF50)
        else      -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = component.url?.let { url -> Modifier.fillMaxWidth().clickable { onLinkOpen(url) } }
                   ?: Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(6.dp).padding(top = 5.dp)
                    .background(accentColor, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(component.title, color = White90, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(3.dp))
                Text(component.body, color = White55, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

/**
 * A numbered walkthrough, each step optionally carrying its own picture.
 *
 * The number sits in a fixed-width gutter and the rule runs between the
 * markers, so the eye can follow the sequence without reading it: a gesture
 * performed with the app CLOSED has to be memorised in order before the reader
 * leaves, and an unordered stack of paragraphs does not survive that trip.
 */
@Composable
private fun SduiSteps(component: SduiAppComponent.Steps) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        component.title?.let {
            Text(it, color = White90, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        component.steps.forEachIndexed { i, step ->
            Row(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.width(34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Per-step tint, else the board amber. A walkthrough where
                    // every marker is the same colour reads as one block; giving
                    // each gesture its own makes the four steps countable at a
                    // glance without reading any of them.
                    val tint = parseHexColor(step.tint) ?: MaterialTheme.colorScheme.primary
                    Surface(
                        color = tint.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            if (step.icon != null) {
                                Icon(
                                    SduiIcons.of(step.icon),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(15.dp),
                                )
                            } else {
                                Text(
                                    "${i + 1}",
                                    color = tint,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    // No rule under the last marker, which would point at nothing.
                    if (i != component.steps.lastIndex) {
                        Box(
                            Modifier.width(1.dp).weight(1f).padding(vertical = 4.dp)
                                .background(White08)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f).padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(step.title, color = White90, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (!step.body.isNullOrBlank()) {
                        Text(step.body!!, color = White55, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    if (step.url != null || step.frames.isNotEmpty()) {
                        SduiDemoMedia(
                            url = step.url,
                            frames = step.frames,
                            frameMs = step.frameMs,
                            loop = true,
                            aspectRatio = step.aspectRatio,
                            caption = null,
                            corner = step.corner,
                            fit = step.fit,
                            background = step.background,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One line of the reader's own state, e.g. "2 widgets on your Home Screen".
 *
 * The backend owns all three templates and the client only picks between them,
 * so the grammar of the plural stays server-side without the server ever
 * knowing the number. This row is what keeps the guide from reading as an
 * advert: it opens by saying what the reader already has.
 */
@Composable
private fun SduiStatRow(component: SduiAppComponent.StatRow, facts: Map<String, String>) {
    // A fact this client does not publish reads as zero, which is what the
    // `zero` template is for. "No widgets yet" is the right thing to say to a
    // device that cannot tell us otherwise.
    val count = facts[component.fact]?.trim()?.toIntOrNull() ?: 0
    val template = when (count) {
        0    -> component.zero
        1    -> component.one
        else -> component.many
    }
    if (template.isBlank()) return
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, White08),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).background(
                    // Amber once there is something to be amber about. A dot
                    // that is always lit says nothing.
                    if (count > 0) MaterialTheme.colorScheme.primary else White25,
                    RoundedCornerShape(4.dp),
                )
            )
            Spacer(Modifier.width(12.dp))
            Text(
                template.replace("{count}", count.toString()),
                color = White90,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * A served image.
 *
 * The component existed in the model from the start and was never rendered here
 * The comment said "handled inline in their screens", which was true of the
 * selection and login flows that draw their own. Nothing rendered it in a
 * generic layout, so a payload could not put a picture on a screen. It can now.
 *
 * `aspectRatio` is what makes it safe to serve a large photo: the box is
 * reserved at the right shape before the bytes arrive, so a slow image lands
 * without shoving everything below it down the screen.
 */
@Composable
private fun SduiImage(component: SduiAppComponent.Image) {
    val shape = RoundedCornerShape(component.corner.coerceIn(0, 48).dp)
    val scale = if (component.fit == "fill") ContentScale.Crop else ContentScale.Fit
    // Bound to locals first: these are public properties of another module, so
    // Kotlin will not smart-cast them out of null inside the branches.
    val ratio = component.aspectRatio
    val w = component.width
    val h = component.height
    val sized = when {
        ratio != null && ratio > 0f -> Modifier.fillMaxWidth().aspectRatio(ratio)
        w != null && h != null -> Modifier.size(w.dp, h.dp)
        else -> Modifier.fillMaxWidth()
    }
    SduiSmartImage(
        url = component.imageUrl,
        contentDescription = component.contentDescription,
        contentScale = scale,
        modifier = sized
            .clip(shape)
            .background(parseHexColor(component.style) ?: Color.Transparent, shape),
    )
}

/**
 * Children side by side.
 *
 * Weights are applied positionally, and a child with no weight sizes itself
 * first, so an all-unweighted row is a plain row, equal weights are equal
 * columns, and `[2, 1]` is a two-thirds split. `visibleFor` drops the weights of
 * any child it filtered out, so a hidden child never leaves its share behind.
 */
@Composable
private fun SduiRow(
    component: SduiAppComponent.Row,
    onLinkOpen: (String) -> Unit,
    onAction: (String) -> Unit,
    facts: Map<String, String>,
) {
    val alignment = when (component.align) {
        "center" -> Alignment.CenterVertically
        "bottom" -> Alignment.Bottom
        else     -> Alignment.Top
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(component.gap.coerceIn(0, 48).dp),
        verticalAlignment = alignment,
    ) {
        component.components.forEachIndexed { i, child ->
            val weight = component.weights.getOrNull(i)?.takeIf { it > 0f }
            Box(if (weight != null) Modifier.weight(weight) else Modifier) {
                SduiRenderer(child, onLinkOpen, onAction, facts)
            }
        }
    }
}

/**
 * Children in equal columns, wrapping.
 *
 * Built from plain rows rather than a `LazyVerticalGrid` on purpose: this sits
 * inside the guide's own `verticalScroll`, and a lazy grid nested in a scrolling
 * parent has infinite height to measure against. The item counts here are small
 * enough that laziness buys nothing anyway.
 */
@Composable
private fun SduiGrid(
    component: SduiAppComponent.Grid,
    onLinkOpen: (String) -> Unit,
    onAction: (String) -> Unit,
    facts: Map<String, String>,
) {
    val columns = component.columns.coerceIn(1, 4)
    val gap = component.gap.coerceIn(0, 48).dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        component.components.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                rowItems.forEach { child ->
                    Box(Modifier.weight(1f)) {
                        SduiRenderer(child, onLinkOpen, onAction, facts)
                    }
                }
                // A short final row must not stretch its items across the full
                // width. Three items in a two-column grid would otherwise make
                // the last one twice the size of the two above it.
                repeat(columns - rowItems.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Panes behind a segmented control.
 *
 * ## Why the selection is not remembered
 * It resets to the first tab on every entry, deliberately. The tabs answer two
 * unrelated questions, and the first one (how do I add a widget) is the one
 * somebody arrives with. Restoring "multiple widgets" because they read it once
 * would hide the add instructions from the next person who needed them.
 *
 * A single-tab payload draws no control at all: one button that cannot be
 * switched away from is furniture.
 */
@Composable
private fun SduiTabs(
    component: SduiAppComponent.Tabs,
    onLinkOpen: (String) -> Unit,
    onAction: (String) -> Unit,
    facts: Map<String, String>,
) {
    if (component.tabs.isEmpty()) return
    // Keyed on the tab titles: a payload refresh mid-view that changes the tabs
    // must reset the index rather than leave it pointing past the end.
    var selected by remember(component.tabs.map { it.title }) { mutableIntStateOf(0) }
    val index = selected.coerceIn(component.tabs.indices)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (component.tabs.size > 1) {
            Surface(
                color = Surface1,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, White08),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(4.dp)) {
                    component.tabs.forEachIndexed { i, tab ->
                        val active = i == index
                        Surface(
                            color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else Color.Transparent,
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    performHaptic(HapticType.TAP)
                                    selected = i
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (tab.icon != null) {
                                    Icon(
                                        SduiIcons.of(tab.icon),
                                        contentDescription = null,
                                        tint = if (active) MaterialTheme.colorScheme.primary else White55,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    tab.title,
                                    color = if (active) MaterialTheme.colorScheme.primary else White55,
                                    fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Filtered here as well as at the screen: a tab's children carry
            // their own conditions and nothing above this has walked into them.
            component.tabs[index].components.visibleFor(facts).forEach { child ->
                SduiRenderer(child, onLinkOpen, onAction, facts)
            }
        }
    }
}
