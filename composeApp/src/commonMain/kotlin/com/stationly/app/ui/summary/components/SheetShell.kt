package com.stationly.app.ui.summary.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.theme.LocalThemeTokens
import kotlinx.coroutines.launch

/**
 * The chrome every explore sheet wears: what this surface is called, and a way
 * out of it.
 *
 * Shared rather than copied because the two sheets are the same gesture on two
 * cards sitting side by side, and a user who opens both in ten seconds will
 * notice any difference between them. The drag handle above this row says
 * "this moves"; it does not say "this closes", and it does not name the
 * surface, which is why a full-height sheet without this row read as a screen
 * that had lost its navigation bar.
 */
@Composable
fun SheetChrome(heading: String, onDismiss: () -> Unit) {
    val t = LocalThemeTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            heading.uppercase(),
            color = t.textSubtle,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Done",
            color = t.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The "go and read the source" action at the foot of a sheet.
 *
 * Outlined in the brand amber rather than filled with it. A filled amber block
 * is the loudest thing the palette can produce, and on a sheet whose whole job
 * is ranking severity it would outrank a line closure. Leaving TfL's website is
 * the least urgent thing offered here, so it is drawn as the quietest.
 */
@Composable
fun SheetLinkButton(label: String, onClick: () -> Unit) {
    val t = LocalThemeTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(t.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = t.onPrimaryContainer,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Close a sheet properly, then optionally do something.
 *
 * ## The bug this exists to stop
 * The "See TfL fares" button used to call `openUrl(...)` and `onDismiss()` in
 * the same frame. `onDismiss` only flips the caller's `showDialog` flag, which
 * removes the sheet from the composition WITHOUT letting `ModalBottomSheet`
 * run its hide animation — and it did that at the exact moment the app was
 * backgrounding to Safari. Coming back, the sheet's modal window was still
 * mounted: invisible, full-screen, and consuming every touch. Both explore
 * cards went dead and stayed dead, with nothing on screen to explain why.
 *
 * The fix is ordering. `sheetState.hide()` suspends until the sheet has
 * actually animated away, so the window is gone before the composition drops
 * it and before anything else is allowed to happen. The URL therefore opens
 * against a screen with no modal on it.
 *
 * All of this runs on the composition's own scope, so it is main-thread by
 * construction and safe to touch Compose state from.
 *
 * Every exit path goes through here — the Done button included. A dismissal
 * that skips the animation is the same latent bug waiting for a slower frame.
 * The one exception is `ModalBottomSheet`'s own `onDismissRequest` (swipe
 * down, tap the scrim): Material has already finished hiding by the time it
 * fires, so it takes the plain `onDismiss`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetExit(
    sheetState: SheetState,
    onDismiss: () -> Unit,
): (after: (() -> Unit)?) -> Unit {
    val scope = rememberCoroutineScope()
    // Read at INVOCATION time, so the returned lambda can be remembered across
    // recompositions without capturing a stale `onDismiss`.
    val currentDismiss by rememberUpdatedState(onDismiss)

    return remember(sheetState, scope) {
        // One exit per sheet. Two taps on "Done" — or a tap on the link
        // followed by a tap on Done before the animation finishes — used to
        // launch two `hide()` coroutines racing the same animation, and the
        // loser was cancelled with its `after` block still unrun.
        var exiting = false

        exit@{ after: (() -> Unit)? ->
            if (exiting) return@exit
            exiting = true
            scope.launch {
                // try/finally, because `hide()` can fail to resume. It is an
                // animation, and an animation that is interrupted — by the app
                // backgrounding, by a competing gesture — may be cancelled
                // rather than completed. Left in the happy path, `onDismiss()`
                // then never runs and the caller's `showDialog` flag stays true
                // forever: the sheet is in the composition, its state is
                // hidden, so it is invisible, and tapping the card that opens
                // it sets true to true. No state change, no recomposition, a
                // card that looks broken.
                //
                // `after` is in the finally for the same reason. It was after
                // it, so a cancelled hide swallowed the whole point of the
                // call: the user tapped "See TfL fares", the sheet closed, and
                // no page ever opened.
                try {
                    sheetState.hide()
                } finally {
                    currentDismiss()
                    after?.invoke()
                }
            }
        }
    }
}

/**
 * Keep the caller's "is this sheet showing" flag honest.
 *
 * Belt and braces for the failure above. Anything that leaves the sheet
 * genuinely hidden while the caller still believes it is open produces a card
 * that silently stops responding, and that state is unrecoverable without
 * killing the app — there is nothing on screen to tap to fix it. So rather
 * than trusting every exit path to be correct forever, the truth is read back
 * off `SheetState` and the flag is corrected to match.
 *
 * The `everShown` guard is load-bearing: a sheet's `currentValue` is
 * [SheetValue.Hidden] for the first frames of its ENTRANCE animation too, and
 * without the guard this would dismiss every sheet at the moment it opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetStateSync(sheetState: SheetState, onDismiss: () -> Unit) {
    val currentDismiss by rememberUpdatedState(onDismiss)
    var everShown by remember { mutableStateOf(false) }
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue != SheetValue.Hidden) {
            everShown = true
        } else if (everShown) {
            currentDismiss()
        }
    }
}
