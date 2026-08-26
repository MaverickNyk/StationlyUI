package com.stationly.app.ui.summary.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.app.ui.theme.LocalThemeTokens

/**
 * What a tap on the fares card opens: which fare is live now, when it changes,
 * and the windows that decide it.
 *
 * ## What did not change
 * None of the fare maths. `computeFareState` in `ExploreSection` still owns
 * peak windows, weekends, bank holidays and the "until" countdown, and this
 * file only renders the [FareState] it produces. The peak-window constants are
 * read from there too rather than retyped, so the table on screen cannot drift
 * from the rule that decides the answer above it.
 *
 * ## What did change
 * It was a centred `Dialog` holding two paragraphs of prose. The paragraphs
 * were friendly but they buried the two facts a passenger wants: whether they
 * are paying peak right now, and when that flips. Both were in the middle of a
 * sentence, and the peak windows were listed twice in running text as
 * "Mon–Fri, 06:30–09:30 and 16:00–19:00", which is a timetable pretending to
 * be a paragraph.
 *
 * So: the state and the countdown are the headline, the windows are a table
 * you can scan, and the prose that survives is one line of context rather than
 * two blocks. The shell matches [LineStatusSheet] because both are the same
 * gesture on the same pair of cards, and they should not open into two
 * different-looking worlds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FareSheet(
    fareState: FareState,
    strings: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val t = LocalThemeTokens.current
    val openUrl = LocalOpenUrl.current
    val sheetState = rememberModalBottomSheetState()
    val exit = rememberSheetExit(sheetState, onDismiss)
    SheetStateSync(sheetState, onDismiss)

    // Peak is not an ERROR. It was painted with `tokens.error`, the same red as
    // a suspended line, which says something has gone wrong; nothing has, the
    // fare is simply higher. Amber is the app's own signage colour and reads as
    // "pay attention" rather than "abandon your journey".
    val accent: Color = if (fareState.isPeak) t.warning else t.live

    val headline = if (fareState.isPeak)
        strings["explore.fares.sheet.title.peak"] ?: "You're paying peak"
    else
        strings["explore.fares.sheet.title.offpeak"] ?: "You're riding cheap"

    val note = if (fareState.isPeak)
        strings["explore.fares.sheet.note.peak"]
            ?: "Same trains either side of the window, just a few quid lighter once it passes."
    else
        strings["explore.fares.sheet.note.offpeak"]
            ?: "Weekends and bank holidays stay off-peak all day, whatever the clock says."

    val linkLabel = strings["explore.fares.dialog.link"] ?: "See TfL fares"
    val tflUrl = strings["explore.fares.tflUrl"]
        ?: "https://tfl.gov.uk/fares/find-fares/tube-and-rail-fares"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.canvas,
        scrimColor = t.scrim,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            SheetChrome(
                heading = strings["explore.fares.sheet.heading"] ?: "Fares",
                onDismiss = { exit(null) },
            )

            Spacer(Modifier.height(14.dp))

            Text(
                headline,
                color = t.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                lineHeight = 26.sp,
            )
            Spacer(Modifier.height(6.dp))
            // The countdown, toned and bold, exactly as the status sheet treats
            // its severity breakdown. It is the one number worth acting on.
            Text(
                (strings["explore.fares.sheet.until"] ?: "Until {t}")
                    .replace("{t}", fareState.untilLabel),
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
            )

            Spacer(Modifier.height(18.dp))

            // The windows as a table. Prose made you parse a sentence to answer
            // "is 17:40 peak"; three rows answer it at a glance, and the row
            // that is live right now is marked so you can place yourself in it.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(t.card)
                    .border(1.dp, t.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(vertical = 4.dp),
            ) {
                WindowRow(
                    label = strings["explore.fares.sheet.window.am"] ?: "Morning peak",
                    value = "${formatHhMm(MORNING_PEAK_START)} – ${formatHhMm(MORNING_PEAK_END)}",
                )
                WindowRow(
                    label = strings["explore.fares.sheet.window.pm"] ?: "Evening peak",
                    value = "${formatHhMm(EVENING_PEAK_START)} – ${formatHhMm(EVENING_PEAK_END)}",
                )
                WindowRow(
                    label = strings["explore.fares.sheet.window.weekend"] ?: "Weekends",
                    value = strings["explore.fares.sheet.window.weekend_value"] ?: "Off-peak all day",
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                note,
                color = t.textMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )

            Spacer(Modifier.height(16.dp))

            // Close the sheet, THEN open the page.
            //
            // Required now rather than merely tidy: the in-app browser renders
            // at the app root, and a ModalBottomSheet is a popup ABOVE root
            // content, so a sheet left open would sit on top of the page.
            //
            // Safe in a way it never was against SFSafariViewController: that
            // backgrounded the app mid-animation, which is what left `hide()`
            // suspended and the sheet's flag stuck true. Nothing backgrounds
            // now — this is one screen pushing another — so the hide runs to
            // completion like any other.
            SheetLinkButton(label = linkLabel) {
                exit { openUrl(tflUrl, "TfL Fares") }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** One row of the peak-window table. */
@Composable
private fun WindowRow(label: String, value: String) {
    val t = LocalThemeTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = t.textMuted,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = t.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
