package com.stationly.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.stationly.app.platform.composeResourcesBundled
import com.stationly.app.resources.Res
import com.stationly.app.resources.stationly_logo
import org.jetbrains.compose.resources.painterResource

/**
 * The Stationly brand mark — the REAL logo (the same
 * `stationly_logo.png` Android ships) once composeResources are bundled,
 * with the legacy drawn red-disc "S" as a crash-proof fallback when the
 * Copy-Compose-Resources build phase didn't run (see composeResourcesBundled).
 *
 * Single source for every logo placement: Summary/Profile top bars, Login
 * landing, update dialog, board footer.
 */
@Composable
fun StationlyLogo(size: Dp, modifier: Modifier = Modifier) {
    if (composeResourcesBundled) {
        Image(
            painter = painterResource(Res.drawable.stationly_logo),
            contentDescription = "Stationly",
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier = modifier.size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "S",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.56f).sp
            )
        }
    }
}
