package com.stationly.app.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.theme.LocalThemeTokens
import kotlinx.coroutines.delay

/**
 * How long the board is left alone before the banner arrives.
 *
 * The user has just watched a board they built appear. That is the moment the
 * app is worth something to them, and it is also a moment they want to LOOK at
 * — departures loading in, the line colours, their station name. Landing a
 * request for money on top of it would take the credit for the thing they were
 * still enjoying.
 *
 * Two and a half seconds is long enough for the board to have painted and
 * settled, short enough that the banner still reads as connected to what just
 * happened rather than as a random interruption.
 */
private const val BANNER_DELAY_MS = 2_500L

/**
 * It does not leave on its own, and that is a deliberate reversal.
 *
 * It used to slide away after nine seconds. Two things were wrong with that.
 * The obvious one: nine seconds is not long enough to read a request, decide
 * how you feel about it, and act, so the common outcome was a user watching the
 * only thing they wanted to tap disappear while they were still reading it. The
 * quieter one: a banner that vanishes teaches people to ignore banners, and the
 * next one this app shows about a closed line gets the same treatment.
 *
 * So it waits, and the way out is to say so. "Maybe later" is right there, it
 * is the first thing under the text, and it costs one tap. That is a better
 * deal than a timer for both sides: the user is never rushed, and an answer is
 * an actual answer rather than an inference drawn from someone looking away.
 *
 * The cost is that "maybe later" is now the ONLY route out, so it buys the full
 * quiet window every time (see `SupportViewModel.onBannerDismissed`). That is
 * the right way round. A deliberate no should be worth more than a timeout ever
 * was.
 */

/**
 * The one time Stationly asks for money unprompted.
 *
 * ## Why it floats instead of sitting in the list
 * Every other banner on the home screen — the announcement, the notification
 * nudge — is part of the scrolling column, inside the group whose measured
 * height decides how tall the board may be. This one cannot be: it arrives
 * seconds AFTER the screen has settled, so joining that group would resize the
 * board under the user's eyes, mid-read, as a side effect of asking them for
 * money. Floating over the bottom costs the layout nothing and takes back
 * nothing the user was already looking at.
 *
 * ## Why it is small
 * It says one thing and offers two answers. There is no illustration, no
 * headline about supporting independent software, and no second paragraph —
 * everything that could be said here is said properly on the sheet behind the
 * button, where the user has chosen to be.
 */
@Composable
fun SupportBanner(
    visible: Boolean,
    icon: String,
    strings: Map<String, String>,
    boardCount: Int,
    amountLabel: String,
    onSupport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalThemeTokens.current

    // The delay lives here rather than in the view model on purpose: it is a
    // property of how the banner presents itself, and a view model that owned it
    // would be holding a timer whose only observable effect is an animation.
    //
    // One delay and nothing after it. There is no second timer taking the
    // banner away again; once it is up, it is up until the user answers.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) { armed = false; return@LaunchedEffect }
        delay(BANNER_DELAY_MS)
        armed = true
    }

    val title = (strings["home.promo.support_money.title"] ?: "Keep Stationly free")
        .fillTokens("n" to boardCount.toString(), "amount" to amountLabel)
    val text = (strings["home.promo.support_money.text"]
        ?: "We work hard to keep it fast, free and completely ad free. " +
        "A little support covers the servers and live data behind your boards.")
        .fillTokens("amount" to amountLabel, "n" to boardCount.toString())
    val cta = strings["home.promo.support_money.cta"] ?: "Support Stationly"
    val dismiss = strings["home.promo.support_money.dismiss"] ?: "Maybe later"

    AnimatedVisibility(
        visible = visible && armed,
        // Springs up rather than fading in. A fade reads as something that was
        // always there and you failed to notice; a spring reads as an arrival,
        // which is honest about what just happened.
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        Surface(
            color = t.cardElevated,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, t.primary.copy(alpha = 0.35f)),
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The glyph, not an icon font. `icon` is an abstract token
                    // from the server (heart | coffee | pint | …) so the
                    // metaphor can change without a release, and the same tile
                    // draws it on all three surfaces so the banner and the
                    // sheet behind its button open on the same mark.
                    SupportGlyphTile(icon = icon, size = 34.dp, glyphSize = 17.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            color = t.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text,
                            color = t.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.5.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // "Not now" first and quiet, the CTA second and solid.
                    // Declining must be the easy, unpunished half of this — it
                    // is reachable without reading, and it looks like what it
                    // is rather than like a greyed-out mistake.
                    Text(
                        dismiss,
                        color = t.textSubtle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .pressScale(onClick = onDismiss)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = t.primary,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.pressScale(onClick = onSupport),
                    ) {
                        Text(
                            cta,
                            color = t.onPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
