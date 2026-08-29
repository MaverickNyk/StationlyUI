package com.stationly.app.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.HapticType
import com.stationly.app.platform.performHaptic
import com.stationly.app.ui.common.pressScale
import com.stationly.app.ui.theme.LocalThemeTokens
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The moment after paying.
 *
 * ## Why this is worth building properly
 * Somebody just gave money to an app that gates nothing and could have taken
 * theirs for free forever. The only thing they get is this screen, so this
 * screen is the product. It says thank you in the app's own voice, says what
 * their money actually does, and then gets out of the way.
 *
 * ## It fires optimistically
 * Shown the instant the browser returns, before the Stripe webhook has
 * necessarily reached the backend. Making someone watch a spinner after paying,
 * to confirm a thing they just did, would be the worst possible place to be
 * accurate at the expense of being decent. The server record is the authority
 * and reconciles later; if a payment somehow failed after returning, the badge
 * simply never arrives from the server and lapses with the local one.
 *
 * ## Once
 * `SupportStore` clears the pending flag as soon as it plays, so a relaunch
 * mid-celebration does not replay it, and the same deep link arriving twice
 * does not either.
 *
 * ## The panel, and the theme bug it fixes
 * The content used to sit directly on the scrim: text in `textPrimary` on top of
 * `scrim`, which is a near-black wash in BOTH themes. In dark that is white on
 * black and looks fine, which is why it survived. In light it is near-black text
 * on a near-black wash, so the entire thank-you was invisible to anyone running
 * the app in light mode. Every token in this file is designed against a surface,
 * so the fix is to give it one: an opaque `card` panel, with the scrim doing
 * nothing but separating it from the board behind.
 */
@Composable
fun SupportThanksOverlay(
    visible: Boolean,
    config: SupportMoneyConfig,
    strings: Map<String, String>,
    amountMinor: Int,
    contributionCount: Int,
    onDismiss: () -> Unit,
) {
    val t = LocalThemeTokens.current
    val thanks = config.thanks

    // ── The celebration ──────────────────────────────────────────────────
    //
    // Fired here rather than in the view model, because it has to land WITH the
    // burst. The view model records the contribution on a coroutine that may
    // finish a frame or several before this composes, and a buzz arriving
    // before the first piece is drawn does not read as celebration, it reads as
    // a notification.
    //
    // Three pulses, not one. A single SUCCESS is the pattern the app already
    // uses for "your board saved", and this is not that. The heavy hit is the
    // pop, and the two light taps chasing it are the paper landing; together
    // they are about as close to a party popper as a phone can get.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        performHaptic(HapticType.SUCCESS)
        delay(110)
        performHaptic(HapticType.TAP)
        delay(80)
        performHaptic(HapticType.TAP)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(220)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Deeper than the shared scrim token. This is not a sheet with
                // context behind it worth glancing at; it is a full stop, and
                // the live board showing through a departure at a time would be
                // the app carrying on with its day over the top of a thank-you.
                .background(t.canvas.copy(alpha = 0.90f))
                // Tap anywhere to leave. The user has been thanked and read a
                // note; making them hunt for a close button would be the one
                // ungracious note in the whole flow.
                .pressScale(onClick = onDismiss, scale = 1f),
            contentAlignment = Alignment.Center,
        ) {
            // Behind the panel, so pieces fall past it rather than over the
            // words. Confetti drawn on top of body text costs the note its
            // legibility, and the note is the part that matters.
            if (thanks.confetti) ConfettiField()

            // ## Why a MutableTransitionState and not `visible = visible`
            //
            // This is composed only once the OUTER AnimatedVisibility has begun
            // entering, and by then `visible` is already true. An
            // AnimatedVisibility whose visibility is true on its very first
            // composition renders straight away without running its enter
            // transition — so written the obvious way, the spring below never
            // played once, and the panel simply appeared. The bug is invisible
            // in code review precisely because the code looks correct.
            //
            // A transition state that starts false and is driven true on the
            // first frame gives the animation an edge to run on.
            val panelState = remember { MutableTransitionState(false) }
            panelState.targetState = true

            AnimatedVisibility(
                visibleState = panelState,
                // Springs in from slightly small. The panel should arrive like
                // something popping out, in step with the burst behind it.
                enter = scaleIn(
                    initialScale = 0.86f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                ) + fadeIn(tween(200)),
                exit = scaleOut(targetScale = 0.94f, animationSpec = tween(180)) + fadeOut(tween(160)),
            ) {
                ThanksPanel(
                    config = config,
                    strings = strings,
                    amountMinor = amountMinor,
                    contributionCount = contributionCount,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ThanksPanel(
    config: SupportMoneyConfig,
    strings: Map<String, String>,
    amountMinor: Int,
    contributionCount: Int,
    onDismiss: () -> Unit,
) {
    val t = LocalThemeTokens.current
    val thanks = config.thanks

    // Consumes taps, so only the SCRIM dismisses.
    //
    // The whole background is clickable, which is right: tap-outside-to-close is
    // what people try first. But the panel sits inside that clickable area, so
    // without this the note itself was a dismiss button, and a finger resting on
    // the card while reading closed the one screen the user might want to sit
    // with. There is a close control now; the card no longer needs to be one.
    Box(
        Modifier
            .padding(horizontal = 28.dp)
            .widthIn(max = 420.dp)
            .pressScale(onClick = {}, scale = 1f),
    ) {
    Surface(
        color = t.card,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, t.primary.copy(alpha = 0.35f)),
        shadowElevation = 24.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 26.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The emblem, not the payload glyph. They are a supporter as of
            // three seconds ago, and this is the mark they will now see on their
            // own avatar; showing it here first is what makes the badge on the
            // home screen recognisable rather than mysterious.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(t.primary.copy(alpha = 0.14f)),
                )
                SupporterEmblem(size = 68.dp)
            }

            Spacer(Modifier.height(22.dp))

            thanks.titleLines.forEachIndexed { i, line ->
                if (i > 0) Spacer(Modifier.height(4.dp))
                Text(
                    line.fillTokens(
                        "amount" to formatMoney(amountMinor, config.currency),
                        "n" to contributionCount.toString(),
                    ),
                    color = if (i == 0) t.textPrimary else t.primary,
                    fontSize = if (i == 0) 34.sp else 20.sp,
                    fontWeight = if (i == 0) FontWeight.Black else FontWeight.Bold,
                    letterSpacing = if (i == 0) (-0.8).sp else 0.sp,
                    textAlign = TextAlign.Center,
                )
            }

            // NOTHING between the headline and the note.
            //
            // There was a SUPPORTER pill and a "for the next 30 days" line here,
            // and before that a two-row departure board reading "The board stays
            // lit / +1 day". Every version of this space has been the same
            // mistake in a different costume: a STATUS where a THANK-YOU
            // belongs. Someone who has just given money does not need to be told
            // what they now count as, or for how long, and putting a label there
            // turns a gift into a receipt for a membership.
            //
            // The note below is the whole point of this screen.

            // Kept, and empty by default. The server can put real figures back
            // here whenever there are real figures to put.
            if (thanks.boardLines.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = t.cardElevated,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        thanks.boardLines.forEachIndexed { i, line ->
                            if (i > 0) Spacer(Modifier.height(9.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(line.l, color = t.textMuted, fontSize = 12.sp)
                                Spacer(Modifier.width(24.dp))
                                Text(
                                    line.r.fillTokens(
                                        "amount" to formatMoney(amountMinor, config.currency),
                                    ),
                                    color = t.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            // ── Two sentences, doing two different jobs ──────────────────
            //
            // The FIRST names what this particular amount paid for. It only
            // appears when the figure matches a rung, which is every path except
            // a custom amount, and it is the specific half: "£8 helps people
            // waiting at stops like Manor House". Being thanked for the thing
            // you actually did lands differently from being acknowledged.
            //
            // The SECOND is the same for everybody and says where the money
            // really goes: servers, the team, the next features, and the promise
            // that none of it ends up behind a paywall. It is the answer to the
            // question a generous person is quietly asking, which is whether any
            // of this was worth it.
            //
            // Specific first because it is about them; general second because it
            // is the bigger, slower point and reads better as the closing note.
            val specific = config.tiers
                .firstOrNull { it.amountMinor == amountMinor && it.thanks.isNotBlank() }
                ?.thanks

            fun fill(line: String) = line.fillTokens(
                "amount" to formatMoney(amountMinor, config.currency),
                "n" to contributionCount.toString(),
            )

            if (!specific.isNullOrBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    fill(specific),
                    color = t.primary,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }

            if (thanks.note.isNotBlank()) {
                Spacer(Modifier.height(if (specific.isNullOrBlank()) 18.dp else 12.dp))
                Text(
                    fill(thanks.note),
                    color = t.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }

        }
    }

    // ── The way out ──────────────────────────────────────────────────────
    //
    // An icon, not the worded button that used to sit at the bottom. Two
    // reasons. A button reading "Back to your boards" is one more sentence to
    // read before you are allowed to leave a screen whose whole job was to say
    // one thing, and it competed with the note for the eye. And a close control
    // in the top-right corner is the thing every person already knows how to
    // find, so it needs no words at all.
    //
    // The whole scrim is still tappable, as it always was. This is not the only
    // way out; it is the VISIBLE one, because "tap anywhere" is a thing you
    // either happen to try or never discover.
        Surface(
            color = t.cardElevated,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(34.dp)
                .pressScale(onClick = onDismiss),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = strings["support_money.thanks.close"] ?: "Close",
                    tint = t.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Confetti
 * ──────────────────────────────────────────────────────────────────────────── */

/** How many pieces. Two cannons' worth, plus a light fall over the top. */
private const val BURST_COUNT = 78
private const val FALL_COUNT = 26

/** One pass, then it stops. Confetti that loops is a loading spinner. */
private const val CONFETTI_MS = 3_400

/** The animation's own clock, in seconds, so the physics below read as physics. */
private const val CONFETTI_SECONDS = CONFETTI_MS / 1000f

/**
 * The festive palette.
 *
 * The old field was brand amber at varying alpha, on the reasoning that the app
 * has exactly one accent colour and a burst of primaries would be the only place
 * in it that does not. That reasoning holds for chrome and does not hold here.
 * Monochrome confetti does not read as confetti; it reads as an effect, and the
 * one screen in the app whose entire job is to feel like a good moment is the
 * one place the house style should give way. Every party popper anybody has ever
 * pulled was multicoloured.
 *
 * Fixed hues rather than theme tokens, and saturated enough to hold up on both
 * the cream and the near-black canvas. The brand amber is in the mix and appears
 * twice as often as the rest, so the burst is still recognisably Stationly's.
 */
private val CONFETTI_COLORS = listOf(
    Color(0xFFFFC819), // brand amber
    Color(0xFFFFC819),
    Color(0xFFFF5C8A), // pink
    Color(0xFF4ADE80), // green
    Color(0xFF4A90D9), // blue
    Color(0xFFB06CF0), // violet
    Color(0xFFFF8A3D), // orange
    Color(0xFF34D6D6), // teal
)

/**
 * A party popper: two cannons from the bottom corners, then paper coming down.
 *
 * ## Why it is drawn, not animated per piece
 * One `Canvas` and one animated float. The alternative, a composable per piece
 * each with its own animation, is a hundred animations and a hundred recomposing
 * nodes on the frame where the user is also being shown a panel and a scrim, on
 * the oldest phone we support. Here every piece's position is a pure function of
 * one clock value, so the whole field costs one draw call and no recomposition.
 *
 * ## The physics, such as they are
 * Each burst piece leaves a bottom corner at an angle and a speed, and from
 * there it is `x = vx·t` with a little drag and `y = vy·t + ½gt²`. That is
 * enough to give the arc a real top and a real fall, which is the whole
 * difference between confetti and dots moving up. The fall pieces skip the
 * cannon and just descend with a sway.
 *
 * ## The flutter
 * Each piece is a rectangle whose HEIGHT is scaled by `|cos(spin·t)|`, so it
 * narrows to a line and opens out again as it turns. It is the cheapest possible
 * fake for a piece of paper tumbling edge-on, and it is the single detail that
 * makes a field of rectangles look like paper rather than like sprites.
 */
@Composable
private fun ConfettiField() {
    // Seeded once. `Random` inside the draw block would re-roll every frame and
    // the field would boil rather than fly.
    val pieces = remember {
        List(BURST_COUNT + FALL_COUNT) { i ->
            val burst = i < BURST_COUNT
            val fromLeft = i % 2 == 0
            ConfettiPiece(
                burst = burst,
                fromLeft = fromLeft,
                // Left cannon fires up and to the right, right cannon up and to
                // the left. Both spread about 40 degrees, which is wide enough
                // to cover the screen and narrow enough to still read as two
                // sources rather than as a general upward drift.
                angleDeg = if (fromLeft) 52f + Random.nextFloat() * 40f
                else 88f + Random.nextFloat() * 40f,
                speed = 1.25f + Random.nextFloat() * 0.55f,
                x = Random.nextFloat(),
                delay = if (burst) Random.nextFloat() * 0.06f else 0.10f + Random.nextFloat() * 0.45f,
                size = 5f + Random.nextFloat() * 6f,
                spin = 3f + Random.nextFloat() * 7f,
                phase = Random.nextFloat() * 6.283f,
                color = CONFETTI_COLORS[Random.nextInt(CONFETTI_COLORS.size)],
                round = Random.nextFloat() < 0.22f,
            )
        }
    }
    var progress by remember { mutableStateOf(0f) }
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(CONFETTI_MS, easing = LinearEasing),
        label = "confetti",
    )
    LaunchedEffect(Unit) { progress = 1f }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Gravity and launch speed are both expressed in screen heights per
        // second, so the arc is the same shape on a phone and on a tablet.
        val g = 2.1f * h

        for (p in pieces) {
            val local = ((animated - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val time = local * CONFETTI_SECONDS

            val x: Float
            val y: Float
            if (p.burst) {
                val rad = p.angleDeg * 0.017453292f
                val v = p.speed * h
                val vx = cos(rad) * v
                val vy = -sin(rad) * v
                val ox = if (p.fromLeft) -0.04f * w else 1.04f * w
                // Air drag on the horizontal only. Vertically the fall is the
                // point, and damping it makes the pieces hang.
                x = ox + vx * time * (1f - 0.22f * local)
                y = h * 1.02f + vy * time + 0.5f * g * time * time
            } else {
                x = p.x * w + sin(local * 6.283f * p.spin * 0.25f + p.phase) * 0.05f * w
                y = local * (h + 80f) - 40f
            }
            if (y > h + 40f) continue

            // Fades out over the last third rather than at the end, so the
            // field thins instead of blinking off.
            val alpha = (1f - ((local - 0.62f) / 0.38f)).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue
            val color = p.color.copy(alpha = alpha)

            if (p.round) {
                drawCircle(color = color, radius = p.size * 0.5f, center = Offset(x, y))
            } else {
                // The flutter. Never fully zero, so a piece turning edge-on
                // thins to a hairline instead of disappearing for a frame.
                val flutter = 0.15f + abs(cos(time * p.spin + p.phase)) * 0.85f
                val pw = p.size
                val ph = p.size * 1.6f * flutter
                rotate(degrees = (time * p.spin * 26f + p.phase * 20f), pivot = Offset(x, y)) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x - pw / 2f, y - ph / 2f),
                        size = Size(pw, ph),
                    )
                }
            }
        }
    }
}

private data class ConfettiPiece(
    val burst: Boolean,
    val fromLeft: Boolean,
    val angleDeg: Float,
    val speed: Float,
    val x: Float,
    val delay: Float,
    val size: Float,
    val spin: Float,
    val phase: Float,
    val color: Color,
    val round: Boolean,
)
