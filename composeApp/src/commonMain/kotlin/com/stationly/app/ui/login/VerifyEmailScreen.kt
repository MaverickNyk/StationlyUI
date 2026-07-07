package com.stationly.app.ui.login

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.resources.Res
import com.stationly.app.resources.stationly_logo
import com.stationly.app.ui.common.LocalOpenUrl
import com.stationly.core.platform.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.painterResource

// Theme-aware palette — names preserved so call sites stay unchanged.
private val Amber     @Composable get() = MaterialTheme.colorScheme.primary
private val BgColor   @Composable get() = MaterialTheme.colorScheme.background
private val White80   @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.80f)
private val White50   @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f)

private const val PREF_LAST_VERIFY_SENT_AT = "last_verify_send_at"
private const val RESEND_COOLDOWN_SEC = 60

/**
 * Hard gate shown after email signup, or after email login when the user's address
 * is not yet verified. Google sign-in skips this — Google emails are pre-verified.
 *
 * Three actions:
 *   - I've verified  → user.reload(), check isEmailVerified, proceed if true
 *   - Resend email   → Firebase sendEmailVerification, 60s local rate limit
 *   - Use a different email → sign out, back to landing
 */
@Composable
fun VerifyEmailScreen(
    authProvider: PlatformAuthProvider,
    onVerified: () -> Unit,
    onUseDifferentEmail: () -> Unit,
    viewModel: LoginViewModel = viewModel { LoginViewModel(authProvider) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scopeForPersist = rememberCoroutineScope()
    val openUrl = LocalOpenUrl.current
    
    // Cooldown is derived from a persisted timestamp — survives force-stop / process death.
    var resendCooldown by remember { mutableStateOf(0) }
    
    // On first composition, load any persisted cooldown.
    LaunchedEffect(Unit) {
        val lastSentAt = Platform.storageManager
            .loadString(PREF_LAST_VERIFY_SENT_AT)?.toLongOrNull() ?: 0L
        val elapsedSec = ((Clock.System.now().toEpochMilliseconds() - lastSentAt) / 1000).toInt()
        if (elapsedSec in 0..RESEND_COOLDOWN_SEC) {
            resendCooldown = RESEND_COOLDOWN_SEC - elapsedSec
        }
    }

    // Auto-detect verification when the user returns to the app from their email.
    // Polling on ON_RESUME catches their return seamlessly.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.silentlyCheckEmailVerified(onVerified)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sourced from sdui home-config
    var strings by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        runCatching {
            com.stationly.core.service.NetworkModule.sduiApi.getHomeConfig()
        }.getOrNull()?.strings?.let { strings = it }
    }
    fun str(key: String, default: String): String = strings[key] ?: default

    // Cooldown ticker
    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 0) {
            delay(1000)
            resendCooldown -= 1
        }
    }

    val email = remember {
        authProvider.currentUserEmail() ?: ""
    }

    Box(Modifier.fillMaxSize().background(BgColor)) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Subtle brand wink at the top
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(Res.drawable.stationly_logo),
                    contentDescription = "Stationly",
                    modifier = Modifier.size(20.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "STATIONLY",
                    color = Amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.5.sp,
                )
            }

            Spacer(Modifier.weight(0.4f))

            AnimatedMailIcon()

            Spacer(Modifier.height(24.dp))
            Text(
                str("auth.verify.title", "Check your inbox"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                str("auth.verify.subtitle", "We sent a verification link to"),
                color = White50, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                maskEmail(email),
                color = Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                str(
                    "auth.verify.body",
                    "Tap the link in the email, then come back here and tap \"I've verified\"."
                ),
                color = White50, fontSize = 13.sp, lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            uiState.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(0.4f))

            // PRIMARY action: jump to the user's email app (message:// URL scheme on iOS)
            Button(
                onClick = { openUrl("message://", null) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    Icons.Outlined.MailOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    str("auth.verify.open_email", "Open email app"),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // SECONDARY action: manual "I've verified" check.
            OutlinedButton(
                onClick = { viewModel.confirmEmailVerified(onVerified) },
                enabled = !uiState.isAuthenticating,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.50f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber)
            ) {
                if (uiState.isAuthenticating) {
                    CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        str("auth.verify.confirm", "I've verified"),
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (resendCooldown > 0) {
                    str("auth.verify.resend_cooldown", "Resend email in {s}s").replace("{s}", resendCooldown.toString())
                } else {
                    str("auth.verify.resend", "Resend email")
                },
                color = if (resendCooldown > 0) White50 else Amber,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = if (resendCooldown == 0) {
                    Modifier
                        .clickable {
                            viewModel.resendVerificationEmail()
                            resendCooldown = RESEND_COOLDOWN_SEC
                            scopeForPersist.launch {
                                Platform.storageManager
                                    .saveString(PREF_LAST_VERIFY_SENT_AT, Clock.System.now().toEpochMilliseconds().toString())
                            }
                        }
                        .padding(8.dp)
                } else Modifier.padding(8.dp)
            )

            Spacer(Modifier.height(20.dp))
            Text(
                str("auth.verify.different_email", "Use a different email"),
                color = White50, fontSize = 13.sp,
                modifier = Modifier
                    .clickable { viewModel.signOutAfterVerificationFlow(onUseDifferentEmail) }
                    .padding(8.dp)
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AnimatedMailIcon() {
    val pulse by rememberInfiniteTransition(label = "mailPulse").animateFloat(
        0.94f, 1.06f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "mailPulseValue"
    )
    Box(
        modifier = Modifier
            .size(96.dp)
            .scale(pulse)
            .background(Amber.copy(alpha = 0.10f), CircleShape)
            .border(1.dp, Amber.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.MarkEmailRead,
            contentDescription = "Verification email sent",
            tint = Amber,
            modifier = Modifier.size(48.dp)
        )
    }
}

private fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) return email
    val name = email.substring(0, at)
    val domain = email.substring(at)
    val visible = name.take(3)
    val stars = "*".repeat((name.length - visible.length).coerceAtLeast(2))
    return "$visible$stars$domain"
}
