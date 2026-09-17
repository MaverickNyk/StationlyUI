package com.stationly.app.ui.support

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.theme.LocalThemeTokens

/**
 * Where the user chooses an amount and pays. Two taps from anywhere.
 *
 * ## The whole flow, and why it is this short
 * Banner or profile card, this sheet, a tier, the platform's payment sheet.
 * Every amount is its own Stripe Payment Link, so tapping "£8" opens a page
 * already priced at £8 with the wallet ready. There is no amount to re-enter, no
 * form, and no account to create. The one longer path is "choose your own
 * amount", which hands the figure to Stripe's own page because that is the only
 * place a free amount can be typed.
 *
 * ## Everything visible here is server copy
 * Heading, body, the impact line, each tier's label and note, the button, the
 * line under it. The backend can rewrite the pitch, reprice the ladder, or
 * change what each rung claims to fund, and this file does not change. What is
 * hardcoded is only the shape: three rungs, a custom row, one button.
 *
 * ## The same voice as the profile card
 * The two surfaces are the same request in different places, and they used to
 * say it differently: the card offered a drink and the sheet explained the
 * running costs. Both now open on the same sentence, because a user who taps
 * through from the card should land on the thing they tapped, not on a second
 * pitch that has to be read from the top.
 */
/** Stands in for a tier id when the custom-amount row is the selection. */
private const val CUSTOM_HINT_ID = "__custom__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSheet(
    config: SupportMoneyConfig,
    strings: Map<String, String>,
    contributionCount: Int,
    onPay: (SupportTier?) -> Boolean,
    onDismiss: () -> Unit,
) {
    val t = LocalThemeTokens.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selected by remember(config.defaultTierId) { mutableStateOf(config.defaultTier) }
    var custom by remember { mutableStateOf(false) }
    // Set when a tap could not open anything: no uid, or no link wired. It is
    // shown in place of the note rather than as a toast, because the user is
    // looking at the button they just pressed and nowhere else.
    var failed by remember { mutableStateOf(false) }

    val customHint = strings["support_money.sheet.custom_hint"]
        ?: "Pick whatever amount feels right on the next screen."
    val amountLabel = if (custom || selected == null) {
        strings["support_money.sheet.custom_cta"] ?: "Choose an amount"
    } else {
        formatMoney(selected!!.amountMinor, config.currency)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.canvas,
        scrimColor = t.scrim,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 20.dp),
        ) {
            // ── The pitch ────────────────────────────────────────────────
            SupportGlyphTile(icon = config.icon, size = 52.dp, glyphSize = 26.sp)

            Spacer(Modifier.height(14.dp))
            Text(
                config.heading.ifBlank { "Keep Stationly free" },
                color = t.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
            )
            if (config.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(config.body, color = t.textMuted, fontSize = 14.sp, lineHeight = 21.sp)
            }
            if (config.impactLine.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                // The impact line earns its own treatment: it is the only
                // sentence here that answers "what does my money actually do",
                // which is the question a stranger is really asking.
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(t.primary),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(config.impactLine, color = t.textPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }

            // Only ever from the count, never a list. There is no history here
            // and there is not going to be one.
            if (contributionCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    (strings["support_money.sheet.repeat"]
                        ?: "You've supported us {n} times already. Genuinely, thank you.")
                        .fillTokens("n" to contributionCount.toString()),
                    color = t.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(22.dp))

            // ── The ladder ───────────────────────────────────────────────
            // `IntrinsicSize.Min` measures the tallest chip and gives all three
            // that height. Without it the chips are as tall as their own label,
            // so a two-line label made the middle rung visibly bigger than the
            // ones either side — which reads as the app recommending it by size
            // rather than by the fact that it is selected. Labels are one line
            // now, but the constraint stays: it is what makes the row immune to
            // whatever wording the server sends next.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                config.tiers.forEach { tier ->
                    TierChip(
                        tier = tier,
                        currency = config.currency,
                        selected = !custom && selected?.id == tier.id,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = {
                            // SELECTION, not TAP. iOS has a separate, lighter
                            // generator for exactly this and the two say
                            // different things: a tap says "that registered",
                            // a selection says "you are on eight now". Moving
                            // between three amounts is the second one, and it
                            // is the moment the user is actually deciding.
                            if (selected?.id != tier.id || custom) performHaptic(HapticType.SELECTION)
                            selected = tier; custom = false; failed = false
                        },
                    )
                }
            }

            // ── What the selected rung actually funds ─────────────────────
            //
            // Three chips reading £4 / £8 / £12 leave the reader holding the
            // question the ladder itself raises: why is that one more than this
            // one. The chip label answers it in two words and this line answers
            // it in a sentence, and it changes as they tap, so choosing an
            // amount is also how they find out what amounts do.
            //
            // Height-reserved rather than appearing and disappearing: the button
            // below it must not jump under a thumb that is already moving
            // towards it.
            // ── Why every hint is rendered at once ───────────────────────
            //
            // All of them are laid out, stacked in one Box, with every one but
            // the selected at zero alpha. The Box is therefore always as tall as
            // the TALLEST hint, so switching rungs changes nothing about the
            // layout: the custom-amount row and the pay button below do not
            // move, and the sheet does not resize under a thumb already on its
            // way to the button.
            //
            // The obvious fix is a fixed height, and it is the wrong one. Every
            // one of these strings is server copy that can be rewritten without
            // an app release, and the font scale is the user's. A number picked
            // today for "three lines" is wrong the first time somebody sets a
            // large text size or the backend ships a longer sentence, and it
            // fails by clipping — silently, on the sentence doing the
            // persuading. Measuring the real strings at the real size cannot go
            // out of date.
            //
            // Cost is a handful of extra Text layouts on one sheet. The old
            // Crossfade laid out one at a time, which is why the height moved.
            val hints = remember(config, customHint) {
                config.tiers.map { it.id to it.hint } + (CUSTOM_HINT_ID to customHint)
            }
            val activeHintId = if (custom || selected == null) CUSTOM_HINT_ID else selected!!.id

            if (hints.any { it.second.isNotBlank() }) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth()) {
                    hints.forEach { (id, line) ->
                        val alpha by animateFloatAsState(
                            targetValue = if (id == activeHintId) 1f else 0f,
                            animationSpec = tween(180),
                            label = "tier_hint_alpha",
                        )
                        Text(
                            line,
                            color = t.textMuted,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            textAlign = TextAlign.Center,
                            // Read in the draw phase, so fading between two
                            // hints never recomposes or re-measures anything.
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { this.alpha = alpha },
                        )
                    }
                }
            }

            if (config.customAmount.enabled && config.cta.urlOneoff.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = if (custom) t.primary.copy(alpha = 0.12f) else t.card,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (custom) t.primary else t.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(onClick = {
                        if (!custom) performHaptic(HapticType.SELECTION)
                        custom = true; failed = false
                    }),
                ) {
                    Text(
                        (strings["support_money.sheet.custom"] ?: "Choose your own amount")
                            .fillTokens(
                                "min" to formatMoney(config.customAmount.minMinor, config.currency),
                                "max" to formatMoney(config.customAmount.maxMinor, config.currency),
                            ),
                        color = if (custom) t.primary else t.textMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── The button ───────────────────────────────────────────────
            Surface(
                color = t.primary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = {
                        val opened = onPay(if (custom) null else selected)
                        failed = !opened
                    }),
            ) {
                Text(
                    config.cta.label.fillTokens("amount" to amountLabel),
                    color = t.onPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }

            // Only when there is something to say. The slot used to always
            // render, describing the checkout ("one tap, then straight back to
            // the app") directly under the button, so a sentence about plumbing
            // was the last thing read before the tap. It earns its space when a
            // checkout actually fails to open, and not otherwise.
            val footer = if (failed) {
                strings["support_money.sheet.error"]
                    ?: "Couldn't open checkout. Make sure you're signed in and try again."
            } else {
                config.cta.note
            }
            if (footer.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    footer,
                    color = if (failed) t.error else t.textSubtle,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * One rung of the ladder.
 *
 * The amount is the loud part and the label is the quiet part, because the
 * amount is what is being decided: a user scanning three chips is comparing
 * numbers, and making "Funds a feature" the headline would make them read three
 * claims to find them. The full sentence behind each claim is under the ladder,
 * where it has room to be a sentence.
 */
@Composable
private fun TierChip(
    tier: SupportTier,
    currency: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalThemeTokens.current
    Surface(
        color = if (selected) t.primary.copy(alpha = 0.14f) else t.card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) t.primary else t.borderSubtle),
        modifier = modifier.pressScale(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                formatMoney(tier.amountMinor, currency),
                color = if (selected) t.primary else t.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
            )
            if (tier.label.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    tier.label,
                    color = if (selected) t.primary.copy(alpha = 0.85f) else t.textSubtle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    // One line, always. A label that wraps changes the chip's
                    // height, and three chips of different heights is a layout
                    // saying something the content does not mean.
                    maxLines = 1,
                )
            }
        }
    }
}
