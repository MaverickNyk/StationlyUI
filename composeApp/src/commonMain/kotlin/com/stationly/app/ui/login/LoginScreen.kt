package com.stationly.app.ui.login

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stationly.app.ui.theme.*

@Composable
fun LoginScreen(
    screenType: String,
    authProvider: PlatformAuthProvider,
    onNavigateToSummary: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRegister: () -> Unit = {},
    onNavigateToForgotPassword: () -> Unit = {},
    onGoogleSignIn: (() -> Unit)? = null,
    resetOobCode: String? = null,
    showPasswordResetSuccess: Boolean = false,
    onPasswordResetBannerShown: () -> Unit = {},
    viewModel: LoginViewModel = viewModel { LoginViewModel(authProvider) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(screenType) {
        viewModel.loadLayout(screenType)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        // Animated TfL line colours background
        TflBackgroundCanvas()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            // Back button (register / forgot-password / reset-confirm)
            if (screenType != "login") {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onNavigateToLogin) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Logo
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(TflAmber, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("S", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 28.sp)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = screenTitle(screenType),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = screenSubtitle(screenType),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(32.dp))

            // Password reset success banner
            if (showPasswordResetSuccess) {
                LaunchedEffect(Unit) { onPasswordResetBannerShown() }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1B5E20),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                        Text(
                            "Password updated successfully",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Forgot-password: email sent confirmation
            if (uiState.resetEmailSent) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MarkEmailRead, null, tint = TflAmber, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Reset email sent", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Check ${uiState.resetEmail} and follow the link to reset your password.",
                            color = Color.White.copy(0.6f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onNavigateToLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to sign in", color = TflAmber, fontWeight = FontWeight.SemiBold)
                }
                return@Column
            }

            // ── Form fields ────────────────────────────────────────────────────
            when (screenType) {
                "forgot-password" -> ForgotPasswordForm(
                    uiState = uiState,
                    onInputChanged = viewModel::onInputChanged,
                    onSubmit = {
                        viewModel.onForgotPasswordSubmit(
                            uiState.inputs["email"] ?: "",
                            onSent = {}
                        )
                    }
                )
                else -> EmailPasswordForm(
                    screenType = screenType,
                    uiState = uiState,
                    onInputChanged = viewModel::onInputChanged,
                    onTogglePassword = viewModel::togglePasswordVisibility,
                    onSubmit = {
                        viewModel.onSubmit(
                            screenType = screenType,
                            onSuccess = onNavigateToSummary,
                            resetOobCode = resetOobCode
                        )
                    }
                )
            }

            // Error
            uiState.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF3A1C1C)
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.ErrorOutline, null, tint = ErrorColor, modifier = Modifier.size(18.dp))
                        Text(err, color = ErrorColor, fontSize = 13.sp)
                    }
                }
            }

            // Google sign-in (login + register only)
            if (screenType == "login" || screenType == "register") {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Divider(Modifier.weight(1f), color = Color.White.copy(0.1f))
                    Text("  or  ", color = Color.White.copy(0.4f), fontSize = 12.sp)
                    Divider(Modifier.weight(1f), color = Color.White.copy(0.1f))
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { onGoogleSignIn?.invoke() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.15f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Continue with Google", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Footer navigation links
            Spacer(Modifier.height(28.dp))
            when (screenType) {
                "login" -> Row(horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onNavigateToForgotPassword) {
                        Text("Forgot password?", color = Color.White.copy(0.5f), fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onNavigateToRegister) {
                        Text("Create account", color = TflAmber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
                "register" -> TextButton(onClick = onNavigateToLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("Already have an account? Sign in", color = Color.White.copy(0.5f), fontSize = 13.sp)
                }
                else -> Unit
            }

            Spacer(Modifier.height(40.dp))
        }

        // Loading overlay
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TflAmber)
            }
        }
    }
}

// ── Animated TfL line-colours background ──────────────────────────────────────

@Composable
private fun TflBackgroundCanvas() {
    val lineColors = listOf(
        Color(0xFFE32017), Color(0xFF0098D4), Color(0xFF00782A),
        Color(0xFF9B0056), Color(0xFFFF6600), Color(0xFF6950A1),
        Color(0xFF003B8D), Color(0xFFFFD300)
    )
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val offset by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "bg_offset"
    )
    Canvas(Modifier.fillMaxSize()) {
        lineColors.forEachIndexed { i, color ->
            val y = (size.height * ((i.toFloat() / lineColors.size + offset) % 1f))
            drawLine(
                color = color.copy(alpha = 0.07f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 80f
            )
        }
    }
}

// ── Email / Password form ─────────────────────────────────────────────────────

@Composable
private fun EmailPasswordForm(
    screenType: String,
    uiState: LoginUiState,
    onInputChanged: (String, String) -> Unit,
    onTogglePassword: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (screenType != "reset-confirm") {
            StationlyTextField(
                value = uiState.inputs["email"] ?: "",
                onValueChange = { onInputChanged("email", it) },
                label = "Email address",
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                onNext = { passwordFocus.requestFocus() },
                modifier = Modifier.focusRequester(emailFocus)
            )
        }

        StationlyTextField(
            value = uiState.inputs["password"] ?: "",
            onValueChange = { onInputChanged("password", it) },
            label = "Password",
            isPassword = true,
            passwordVisible = uiState.passwordVisible["password"] ?: false,
            onTogglePassword = { onTogglePassword("password") },
            keyboardType = KeyboardType.Password,
            imeAction = if (screenType == "register" || screenType == "reset-confirm") ImeAction.Next else ImeAction.Done,
            onNext = { if (screenType == "register" || screenType == "reset-confirm") confirmFocus.requestFocus() },
            onDone = onSubmit,
            modifier = Modifier.focusRequester(passwordFocus)
        )

        if (screenType == "register" || screenType == "reset-confirm") {
            StationlyTextField(
                value = uiState.inputs["confirm_password"] ?: "",
                onValueChange = { onInputChanged("confirm_password", it) },
                label = "Confirm password",
                isPassword = true,
                passwordVisible = uiState.passwordVisible["confirm_password"] ?: false,
                onTogglePassword = { onTogglePassword("confirm_password") },
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onDone = onSubmit,
                modifier = Modifier.focusRequester(confirmFocus)
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TflAmber, contentColor = Color.Black)
        ) {
            Text(
                submitLabel(screenType),
                fontWeight = FontWeight.Black,
                fontSize = 16.sp
            )
        }
    }
}

// ── Forgot-password form ──────────────────────────────────────────────────────

@Composable
private fun ForgotPasswordForm(
    uiState: LoginUiState,
    onInputChanged: (String, String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Enter your email and we'll send you a link to reset your password.",
            color = Color.White.copy(0.55f),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        StationlyTextField(
            value = uiState.inputs["email"] ?: "",
            onValueChange = { onInputChanged("email", it) },
            label = "Email address",
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onDone = onSubmit
        )
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TflAmber, contentColor = Color.Black)
        ) {
            Text("Send reset link", fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}

// ── Reusable text field ───────────────────────────────────────────────────────

@Composable
private fun StationlyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { onTogglePassword?.invoke() }) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = Color.White.copy(0.4f)
                    )
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { onNext?.invoke() },
            onDone = { onDone?.invoke() }
        ),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TflAmber,
            unfocusedBorderColor = Color.White.copy(0.15f),
            focusedLabelColor = TflAmber,
            unfocusedLabelColor = Color.White.copy(0.4f),
            cursorColor = TflAmber,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun screenTitle(screenType: String) = when (screenType) {
    "register"        -> "Create account"
    "forgot-password" -> "Reset password"
    "reset-confirm"   -> "New password"
    else              -> "Welcome back"
}

private fun screenSubtitle(screenType: String) = when (screenType) {
    "register"        -> "Join Stationly for live TfL departures"
    "forgot-password" -> "We'll email you a reset link"
    "reset-confirm"   -> "Enter your new password"
    else              -> "Sign in to see your departure boards"
}

private fun submitLabel(screenType: String) = when (screenType) {
    "register"      -> "Create account"
    "reset-confirm" -> "Update password"
    else            -> "Sign in"
}
