package com.stationly.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.stationly.app.ui.common.StationlyLogo
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.config.ReleaseGate
import com.stationly.core.model.release.StoreLink
import com.stationly.core.model.release.UpdateCopy
import com.stationly.core.model.release.UpdateVerdict
import kotlinx.coroutines.launch

/**
 * The update surfaces, mounted once at the app ROOT.
 *
 * ## Why the root and not the home screen
 * The check this replaces lived in `SummaryViewModel` and drew a dialog owned
 * by `SummaryScreen`, which gave it a reach it could not do the job with: a
 * launch straight into a board, a widget tap or a deep link never passed
 * through home, so the one screen able to say "this build no longer works"
 * was the one screen those users did not see.
 *
 * A blocking verdict is a statement about the INSTALL. It has to sit above
 * every destination, above the in-app browser, and above any open sheet —
 * otherwise the app remains usable behind it, which is the entire thing a block
 * is for.
 *
 * ## Two surfaces, deliberately not one component with a flag
 * They are different statements and they should not share a shape. The block is
 * a full screen with one button and no way past it. The nudge is a small dialog
 * with a decline, shown at most once a fortnight, and it is genuinely optional —
 * iOS automatic updates carry most of the base forward on their own, so the
 * honest default for the nudge is to say nothing at all.
 *
 * Collapsing them into one dismissible dialog is what the old code did, and it
 * produced the worst of both: too weak to enforce anything, too loud to ignore.
 */
@Composable
fun UpdateSurfaces() {
    // ONE flow, and the verdict carries its own copy. An earlier version also
    // read `ReleaseGate.policy.strings` here, which is a plain `var` rather than
    // snapshot state: adopting a document while a surface was on screen did not
    // recompose it, so the words shown were whichever ones happened to be loaded
    // when the VERDICT last changed. Resolving copy at decision time makes this
    // composable a pure function of `verdict` and removes the second read.
    val verdict by ReleaseGate.verdict.collectAsState()
    val scope = rememberCoroutineScope()
    val openStore = rememberStoreOpener()

    when (val v = verdict) {
        is UpdateVerdict.Ok -> Unit

        is UpdateVerdict.Blocked -> UpdateBlockedScreen(
            copy = v.copy,
            onUpdate = { openStore(v.store) },
        )

        is UpdateVerdict.Nudge -> {
            // Recorded when the nudge is SHOWN, not when it is evaluated, so the
            // rate limiter's behaviour is legible in the activity table: a nudge
            // that never appears and a nudge correctly suppressed are otherwise
            // identical in the data.
            //
            // Keyed on the version so re-entering the same nudge does not write
            // a second row, and `recordBlocking` is suspend — it runs on the
            // effect's coroutine, never on the composition.
            LaunchedEffect(v.toVersion) {
                ActivityLog.recordBlocking(
                    ActivityEvents.APP_UPDATE_NUDGED,
                    mapOf("to" to v.toVersion),
                )
            }
            UpdateNudgeDialog(
                copy = v.copy,
                onUpdate = {
                    openStore(v.store)
                    scope.launch { ReleaseGate.acknowledgeNudge() }
                },
                onDismiss = { scope.launch { ReleaseGate.snoozeNudge() } },
            )
        }
    }
}

/**
 * Opens the store, preferring the deep link.
 *
 * ## Why not the app's own `LocalOpenUrl`
 * That routes http(s) into Stationly's in-app browser, which is right for a TfL
 * page and wrong for this: an App Store listing rendered in a WebView cannot
 * install anything, so the one action the blocking screen offers would lead to
 * a page that looks correct and does nothing.
 *
 * ## Why two URLs, and what each is actually for
 * They are NOT "good link, bad link". On iOS both are fine: `itms-apps://` is
 * handled by the App Store app, and `https://apps.apple.com/…` is a universal
 * link that the App Store app claims, so it opens there too rather than
 * detouring through Safari. The pair earns its place on Android, where
 * `market://` throws `ActivityNotFoundException` on a device with no Play Store
 * and the https form is the only one that resolves.
 *
 * ## The fallback is best-effort, and deliberately so
 * `runCatching(...).isSuccess` catches a throwing handler, which is the Android
 * case above. It cannot catch a handler that fails SILENTLY — a platform that
 * returns normally having opened nothing — and no API here exposes that. The
 * blocking screen therefore leans on the invariant rather than on the fallback:
 * `ReleaseGate.blockedOrOk` refuses to block at all when both links are empty,
 * so the button always has somewhere real to go before this is ever reached.
 */
@Composable
private fun rememberStoreOpener(): (StoreLink) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) {
        { store ->
            val opened = store.deepLink.isNotBlank() &&
                runCatching { uriHandler.openUri(store.deepLink) }.isSuccess
            if (!opened && store.web.isNotBlank()) {
                runCatching { uriHandler.openUri(store.web) }
            }
        }
    }
}

/**
 * The blocking screen. One action, and no way past it.
 *
 * ## Everything here is about not being escapable
 * Opaque and full-bleed rather than a scrim, so nothing of the app shows
 * through to be reached for. A `pointerInput` that consumes every gesture,
 * because the surface underneath is a live composition and a tap landing on a
 * board row behind an update screen is worse than no screen at all. No back
 * affordance and no dismiss button.
 *
 * ## And the one thing that is NOT about that
 * There is no "Maybe Later", but there is also no attempt to punish the user
 * for being here. The copy says what happened and what to do, once, without
 * apologising or lecturing — most people seeing this have done nothing except
 * not open the app for six months.
 */
@Composable
private fun UpdateBlockedScreen(
    copy: UpdateCopy,
    onUpdate: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallows every gesture, including ones aimed at whatever is still
            // composed underneath. `awaitPointerEvent` in a forever-loop is the
            // only form that also blocks scrolls and long-presses, not just taps.
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 32.dp),
        ) {
            StationlyLogo(size = 64.dp)
            Text(
                copy.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                copy.message,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(copy.cta, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/**
 * The optional nudge. Dismissible, and the dismissal is remembered.
 *
 * `onDismissRequest` routes to the same snooze as the decline button, so a tap
 * outside is an answer rather than a way to make the question disappear until
 * the next recomposition — which is exactly what the version this replaces did,
 * because its dismissal was a `remember` inside the home screen.
 */
@Composable
private fun UpdateNudgeDialog(
    copy: UpdateCopy,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StationlyLogo(size = 52.dp)
                Text(
                    copy.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    copy.message,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onUpdate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Text(copy.cta, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        copy.dismiss,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
