package com.stationly.app.ui.support

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.theme.LocalThemeTokens

/**
 * The permanent home for supporting Stationly: a card in the profile.
 *
 * The banner is a moment and can be missed; this is the address. Someone who
 * decided later, or dismissed the banner and changed their mind, needs somewhere
 * to go that does not depend on having added a board recently, and Profile is
 * where people look for the thing that is about them and the app rather than
 * about a station.
 *
 * ## Why it looks like this
 * It was a settings row before: a glyph, two lines of grey text, and the word
 * "Support" in amber where every other row on the screen puts a chevron. It read
 * as one more preference, in a list of preferences, and the one thing on this
 * screen that is a request rather than a setting cannot afford to look like the
 * things around it.
 *
 * So it is the only card in Profile with its own accent wash and its own solid
 * button. Not louder in the cheap sense: no animation, no badge, no colour the
 * app does not already use. The weight comes from being the only card that
 * carries a filled action, which is also the honest signal, because it is the
 * only card here that leads somewhere.
 *
 * ## It says something different once you have contributed
 * A supporter does not need the pitch again; they need to see that it worked.
 * Same card, two states, rather than a second card that appears and a first that
 * vanishes, which is how a profile screen ends up with a hole in it on the
 * thirty-first day. The supporter state is not the same card with a pill added:
 * it inverts, becoming the amber-washed thing in a list of grey ones, because
 * "this worked" and "would you" are not the same sentence at different volumes.
 */
@Composable
fun SupportProfileCard(
    config: SupportMoneyConfig,
    strings: Map<String, String>,
    isSupporter: Boolean,
    contributionCount: Int,
    onOpen: () -> Unit,
) {
    if (isSupporter) {
        SupporterStateCard(
            config = config,
            strings = strings,
            contributionCount = contributionCount,
            onOpen = onOpen,
        )
    } else {
        SupportAskCard(config = config, strings = strings, onOpen = onOpen)
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The ask
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The shell both states share: rounded card, amber edge, amber wash, 18dp.
 *
 * A wash rather than a fill. The card has to read as warmer than its neighbours
 * from across the screen and still be a plain card up close; a flat amber panel
 * behind body text would be the loudest thing in Profile and would make the ask
 * feel like an advert.
 *
 * The two states differ only in HOW MUCH amber, so that is the only thing
 * parameterised. They were two copies of this before, which is two places for
 * the corner radius and the padding to drift apart.
 */
@Composable
private fun SupportCardShell(
    borderAlpha: Float,
    borderWidth: Dp,
    washFrom: Float,
    washTo: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalThemeTokens.current
    Surface(
        color = t.card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(borderWidth, t.primary.copy(alpha = borderAlpha)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(t.primary.copy(alpha = washFrom), t.primary.copy(alpha = washTo)),
                        ),
                    ),
            )
            Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
        }
    }
}

@Composable
private fun SupportAskCard(
    config: SupportMoneyConfig,
    strings: Map<String, String>,
    onOpen: () -> Unit,
) {
    val t = LocalThemeTokens.current
    SupportCardShell(borderAlpha = 0.28f, borderWidth = 1.dp, washFrom = 0.10f, washTo = 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SupportGlyphTile(icon = config.icon, size = 44.dp, glyphSize = 21.sp)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            strings["support_money.profile.title"]
                                ?: config.heading.ifBlank { "Support Stationly" },
                            color = t.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            strings["support_money.profile.body"]
                                ?: "Free and ad free, always. A little support is what keeps it that way.",
                            color = t.textMuted,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // The filled button, and the reason this card is noticed at all.
                // Every other row in Profile is tappable-as-a-whole with a
                // chevron; a real button says this one leaves the screen and
                // asks something of you, which is true.
                Surface(
                    color = t.primary,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().pressScale(onClick = onOpen),
                ) {
                    Text(
                        strings["support_money.profile.cta"] ?: "Support Stationly",
                        color = t.onPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    )
                }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The thank-you
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What a supporter sees for the length of the badge.
 *
 * Deliberately a different object, not a variant: amber-washed edge to edge,
 * an emblem instead of a glyph tile, the pill on the same line as the title, and
 * a quiet outlined button where the loud filled one was. The person reading it
 * has already done the thing the other card asks for, and showing them a
 * primary-coloured "Support Stationly" button again would read as the app not
 * having noticed.
 *
 * There is no countdown, and there used to be. "Supporter for 23 more days" was
 * a timer on a gift: it made a thank-you read as a subscription about to fail,
 * and it needed the client to be told a badge window it has no business
 * knowing. The mark simply stops being there when the server stops saying it is.
 */
@Composable
private fun SupporterStateCard(
    config: SupportMoneyConfig,
    strings: Map<String, String>,
    contributionCount: Int,
    onOpen: () -> Unit,
) {
    val t = LocalThemeTokens.current
    SupportCardShell(borderAlpha = 0.55f, borderWidth = 1.5.dp, washFrom = 0.22f, washTo = 0.06f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SupporterEmblem(size = 48.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                // No pill here. The title already says "You're a supporter", and
                // a badge repeating the same three words beside it is the card
                // telling the reader twice. The badge belongs on the profile
                // header, next to the avatar, where it is the only thing saying
                // it.
                Text(
                    strings["support_money.profile.title.supporter"] ?: "You're a supporter",
                    color = t.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    (strings["support_money.profile.meta.supporter"]
                        ?: "Thank you for keeping Stationly free")
                        .fillTokens("n" to contributionCount.toString()),
                    color = t.primary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            (strings["support_money.profile.body.supporter"]
                ?: "Your support is paying for the servers and live data behind every board right now.")
                .fillTokens("n" to contributionCount.toString()),
            color = t.textMuted,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )

        // Still a way back in, and deliberately the quiet kind. Removing it
        // entirely would mean the one group of people who have already shown
        // they want to help have no way to do it twice.
        if (config.isPayable) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, t.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().pressScale(onClick = onOpen),
            ) {
                Text(
                    strings["support_money.profile.cta.supporter"] ?: "Support again",
                    color = t.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Shared marks
 * ──────────────────────────────────────────────────────────────────────────── */

/** The server's `icon` metaphor in a tinted circle. Used by every surface. */
@Composable
internal fun SupportGlyphTile(
    icon: String,
    size: Dp,
    glyphSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val t = LocalThemeTokens.current
    Box(
        modifier.size(size).clip(CircleShape).background(t.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Text(iconGlyph(icon), fontSize = glyphSize) }
}

/**
 * The supporter mark: a filled amber disc with a heart in the app's ink.
 *
 * A vector rather than the payload's emoji, and only here and on the avatar.
 * Those are the two places the mark has to be small and permanent, and an emoji
 * is neither crisp at that size nor consistent across the OS versions the app
 * runs on. The metaphor stays server-driven everywhere it is presentational;
 * this one is structural.
 */
@Composable
internal fun SupporterEmblem(size: Dp, modifier: Modifier = Modifier) {
    val t = LocalThemeTokens.current
    Box(
        modifier.size(size).clip(CircleShape).background(t.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Favorite,
            contentDescription = null,
            tint = t.onPrimary,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * The amber SUPPORTER pill.
 *
 * Small, and it does not animate or glow. It is a thank-you the user can see,
 * not a rank: a badge that draws attention to itself turns a gift into a status
 * purchase, and the next person reads the app as having tiers.
 *
 * Shown in exactly ONE place, under the avatar on Profile. It used to appear on
 * the supporter card as well, next to a title that already said the same thing.
 */
@Composable
fun SupporterPill(label: String, modifier: Modifier = Modifier) {
    val t = LocalThemeTokens.current
    Surface(
        color = t.primary.copy(alpha = 0.20f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, t.primary.copy(alpha = 0.5f)),
        modifier = modifier,
    ) {
        // Not uppercased any more. The label is server copy and now leads with
        // a glyph ("🤝 Stationly Supporter"); shouting a whole phrase in caps
        // beside an emoji reads as a sticker rather than as a thank-you, and it
        // makes a longer label harder to scan rather than easier.
        Text(
            label,
            color = t.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * The supporter mark on the user's own avatar, home screen and Profile.
 *
 * ## Why on the avatar
 * It is the one element that is already about this person and already on both
 * screens, so the thank-you can be permanent without adding a permanent thing.
 * Anywhere else it would be a new element that exists only to say "you paid",
 * which is a different and worse sentence.
 *
 * ## The ring is not decoration
 * It is drawn in the surface the avatar sits on, so the disc reads as sitting
 * ON the photo rather than being part of it. Without it the mark dissolves into
 * whatever a Google profile picture happens to have in its bottom-right corner,
 * which is the one part of the image nobody controls.
 *
 * ## Do not push it outward with an offset
 * On the home screen it lives inside an `IconButton`, and `IconButton` clips its
 * content to a circle for the ripple. A badge on the BOTTOM-END corner already
 * sits on that circle's diagonal, which is the furthest point from the centre
 * any content reaches — so nudging it out by even a couple of dp puts its far
 * edge past the clip and the heart comes back with a slice missing. That was the
 * bug. Align it to the corner and leave it there.
 *
 * Call it inside a `Box` that also draws the avatar and align it to the
 * bottom-end corner. It renders nothing when [visible] is false, so a caller can
 * pass the flag straight through without branching.
 */
@Composable
fun SupporterAvatarBadge(
    visible: Boolean,
    size: Dp,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val t = LocalThemeTokens.current
    Box(
        modifier.size(size).clip(CircleShape).background(ringColor),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().padding(size * 0.11f).clip(CircleShape).background(t.primary),
            contentAlignment = Alignment.Center,
        ) {
            // 0.44 rather than 0.5. A heart is widest across its two lobes, so
            // its drawn width fills more of a square box than a glyph like a
            // check does, and at 0.5 the lobes touched the inner circle's edge.
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = "Supporter",
                tint = t.onPrimary,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}
