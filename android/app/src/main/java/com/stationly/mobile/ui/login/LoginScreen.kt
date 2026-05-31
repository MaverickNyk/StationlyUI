package com.stationly.mobile.ui.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.stationly.core.config.AppConfig
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiCondition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun SduiCondition.isSatisfied(inputs: Map<String, String>): Boolean {
    val v = inputs[dependsOn]?.trim() ?: ""
    return when (operator) {
        "not_empty" -> v.isNotEmpty()
        "empty"     -> v.isEmpty()
        "equals"    -> v == value
        else        -> true
    }
}

// ── Palette ────────────────────────────────────────────────────────────────────
// Theme-aware — names preserved so every call site stays unchanged. Each
// `@Composable get()` reads from MaterialTheme.colorScheme so swapping
// AppTheme flips the whole screen with no per-callsite work.
private val Amber     @Composable get() = MaterialTheme.colorScheme.primary
// AmberDark is always used as a foreground on an Amber background (button
// text, checkmark, brand letter), so it maps to onPrimary — Compose
// MaterialTheme guarantees this pair has accessible contrast in both
// themes (Black on bright amber in dark, White on deep amber in light).
private val AmberDark @Composable get() = MaterialTheme.colorScheme.onPrimary
private val AmberDim  @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
private val BgColor   @Composable get() = MaterialTheme.colorScheme.background
private val White80   @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.80f)
private val White50   @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f)
private val White20   @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.20f)
private val White10   @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)

// ── Background ─────────────────────────────────────────────────────────────────
// Clean dark with a soft amber radial glow behind the brand area. One static element,
// no animation — the brand and content stay the focus.
@Composable
private fun SubtleBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Amber.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    radius = 900f
                )
            )
    )
}

// ── Logo ───────────────────────────────────────────────────────────────────────
@Composable
private fun StationlyLogo(sizeDp: Int) {
    Image(
        painter = painterResource(com.stationly.mobile.R.drawable.stationly_logo),
        contentDescription = "Stationly",
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(sizeDp.dp)
    )
}

// ── Shared components ──────────────────────────────────────────────────────────
@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Amber, contentColor = AmberDark,
            disabledContainerColor = Amber.copy(0.35f), disabledContentColor = AmberDark.copy(0.50f)
        )
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.3.sp) }
}

@Composable
private fun GoogleButton(label: String, onClick: () -> Unit) {
    // Google brand button colours are fixed by their brand guidelines —
    // white background, near-black text, neutral grey border in both
    // themes. Do not theme these or Google's brand compliance breaks.
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
        elevation = ButtonDefaults.buttonElevation(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF747775).copy(0.20f))
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(com.stationly.mobile.R.drawable.ic_google_standard), null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun OrRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = White10)
        Text("  or  ", color = White50, fontSize = 12.sp, letterSpacing = 1.sp)
        HorizontalDivider(Modifier.weight(1f), color = White10)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: () -> Unit = {},
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    fieldError: String? = null,
    helpText: String? = null
) {
    // Auto-scroll this field above the IME the moment it gains focus so the user
    // never has to manually scroll. Pairs with the .imePadding() on the parent
    // scroll container that already reserves space for the keyboard.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 14.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .onFocusEvent { state ->
                    if (state.isFocused) {
                        scope.launch {
                            // Small delay so the IME has finished animating in before
                            // we measure how far to scroll.
                            delay(250)
                            runCatching { bringIntoView.bringIntoView() }
                        }
                    }
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            singleLine = true,
            isError = fieldError != null,
            visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            leadingIcon = {
                // Inline mail/lock/person icon — picked from keyboardType so the SDUI
                // backend doesn't have to ship an icon string per field.
                val icon = when {
                    isPassword                      -> Icons.Outlined.Lock
                    keyboardType == KeyboardType.Email -> Icons.Outlined.Email
                    else                            -> Icons.Outlined.Person
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (fieldError != null) MaterialTheme.colorScheme.error else White50,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onTogglePassword) {
                        Icon(
                            if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = if (fieldError != null) MaterialTheme.colorScheme.error else White50,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = if (fieldError != null) MaterialTheme.colorScheme.error else Amber,
                unfocusedBorderColor = if (fieldError != null) MaterialTheme.colorScheme.error.copy(0.60f) else White20,
                focusedContainerColor   = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor   = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedLabelColor  = if (fieldError != null) MaterialTheme.colorScheme.error else Amber,
                unfocusedLabelColor = if (fieldError != null) MaterialTheme.colorScheme.error.copy(0.70f) else White50,
                cursorColor = Amber,
                errorBorderColor = MaterialTheme.colorScheme.error,
                errorLabelColor  = MaterialTheme.colorScheme.error,
                errorCursorColor = MaterialTheme.colorScheme.error
            ),
            shape = RoundedCornerShape(12.dp)
        )
        if (fieldError != null) {
            Text(
                fieldError,
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp)
            )
        } else if (helpText != null) {
            Text(
                helpText,
                color = White50, fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error.copy(0.10f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(0.22f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(44.dp).background(White10, CircleShape)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
    }
}

// ── Terms & Privacy checkbox ───────────────────────────────────────────────────
@Composable
private fun TermsCheckbox(accepted: Boolean, showError: Boolean, onToggle: () -> Unit) {
    // Open Terms/Privacy in the in-app WebView, not the external browser.
    val openUrl = com.stationly.mobile.ui.common.LocalOpenUrl.current
    val annotatedText = buildAnnotatedString {
        withStyle(SpanStyle(color = White50, fontSize = 13.sp)) { append("I agree to the ") }
        pushStringAnnotation("URL", "${AppConfig.webBaseUrl}/terms/")
        withStyle(SpanStyle(color = Amber, fontSize = 13.sp, textDecoration = TextDecoration.Underline)) {
            append("Terms of Service")
        }
        pop()
        withStyle(SpanStyle(color = White50, fontSize = 13.sp)) { append(" and ") }
        pushStringAnnotation("URL", "${AppConfig.webBaseUrl}/privacy/")
        withStyle(SpanStyle(color = Amber, fontSize = 13.sp, textDecoration = TextDecoration.Underline)) {
            append("Privacy Policy")
        }
        pop()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = accepted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Amber, uncheckedColor = if (showError) MaterialTheme.colorScheme.error else White50,
                    checkmarkColor = AmberDark
                )
            )
            Spacer(Modifier.width(4.dp))
            ClickableText(
                text = annotatedText,
                onClick = { offset ->
                    annotatedText.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { openUrl(it.item, null) }
                }
            )
        }
        if (showError) {
            Text(
                "Please accept the terms to continue",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp)
            )
        }
    }
}

// ── Terms & Privacy inline disclaimer (no checkbox — for social sign-in paths) ─
@Composable
private fun TermsDisclaimer(modifier: Modifier = Modifier) {
    // Open Terms/Privacy in the in-app WebView, not the external browser.
    val openUrl = com.stationly.mobile.ui.common.LocalOpenUrl.current
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = White50, fontSize = 12.sp)) { append("By continuing, you agree to our ") }
        pushStringAnnotation("URL", "${AppConfig.webBaseUrl}/terms/")
        withStyle(SpanStyle(color = Amber, fontSize = 12.sp, textDecoration = TextDecoration.Underline)) {
            append("Terms of Service")
        }
        pop()
        withStyle(SpanStyle(color = White50, fontSize = 12.sp)) { append(" and ") }
        pushStringAnnotation("URL", "${AppConfig.webBaseUrl}/privacy/")
        withStyle(SpanStyle(color = Amber, fontSize = 12.sp, textDecoration = TextDecoration.Underline)) {
            append("Privacy Policy")
        }
        pop()
        withStyle(SpanStyle(color = White50, fontSize = 12.sp)) { append(".") }
    }
    ClickableText(
        text = text,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center, lineHeight = 18.sp),
        onClick = { offset ->
            text.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { openUrl(it.item, null) }
        }
    )
}

// ── Password reset success banner ──────────────────────────────────────────────
@Composable
private fun PasswordResetSuccessBanner(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(5000)
        onDismiss()
    }
    Surface(
        Modifier.fillMaxWidth(),
        color = Amber.copy(0.10f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(0.28f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.CheckCircle, null, tint = Amber, modifier = Modifier.size(16.dp))
            Text(
                "Password reset successfully. Sign in with your new password.",
                color = Amber.copy(0.90f), fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── SDUI form renderer ─────────────────────────────────────────────────────────
@Composable
private fun SduiFormContent(
    screenType: String,
    components: List<SduiAppComponent>,
    inputs: Map<String, String>,
    error: String?,
    showGoogleSignIn: Boolean = false,
    showPasswordResetSuccess: Boolean = false,
    onInputChanged: (String, String) -> Unit,
    onAction: (String) -> Unit,
    onPasswordResetBannerDismissed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isRegister = screenType == "register"

    val allButtons    = components.filterIsInstance<SduiAppComponent.Button>()
    val allInputs     = components.filterIsInstance<SduiAppComponent.Input>()

    // Condition-filtered views — re-evaluate whenever inputs change
    val visibleButtons  = allButtons.filter { it.condition?.isSatisfied(inputs) != false }
    val inputFields     = allInputs.filter  { it.condition?.isSatisfied(inputs) != false }

    val googleBtn = if (showGoogleSignIn) visibleButtons.find { it.action == "GOOGLE_LOGIN_ACTION" } else null
    val submitBtn = visibleButtons.firstOrNull { it.action in setOf("LOGIN_ACTION", "REGISTER_ACTION", "RESET_PASSWORD_ACTION") }
    val forgotBtn = visibleButtons.find { it.action == "NAVIGATE_TO_FORGOT_PASSWORD" || it.action == "FORGOT_PASSWORD_ACTION" }
    val navBtns   = visibleButtons.filter { it.action.startsWith("NAVIGATE_") && it.action != "NAVIGATE_TO_FORGOT_PASSWORD" }

    // Local form state
    var passwordVisible  by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var confirmPw        by remember { mutableStateOf("") }
    var showConfirmPw    by remember { mutableStateOf(false) }
    var fieldErrors      by remember { mutableStateOf(emptyMap<String, String>()) }
    var termsAccepted    by remember { mutableStateOf(false) }
    var showTermsError   by remember { mutableStateOf(false) }

    // FocusRequesters: one per SDUI field + one for confirm-password if register
    val totalFields = inputFields.size + if (isRegister) 1 else 0
    val focusRequesters = remember(totalFields) { List(totalFields) { FocusRequester() } }

    // Auto-focus first field when form is ready
    LaunchedEffect(inputFields.isNotEmpty()) {
        if (inputFields.isNotEmpty()) {
            delay(300)
            try { focusRequesters.firstOrNull()?.requestFocus() } catch (_: Exception) {}
        }
    }

    fun validate(): Boolean {
        val errs = mutableMapOf<String, String>()
        inputFields.forEach { field ->
            val value = inputs[field.id]?.trim() ?: ""
            val v = field.validation
            if (v != null) {
                if (v.required && value.isEmpty()) {
                    errs[field.id] = v.errorMessage ?: "Please fill in ${field.label}."
                } else if (value.isNotEmpty()) {
                    v.minLength?.let { min ->
                        if (value.length < min) errs[field.id] = v.errorMessage ?: "${field.label} must be at least $min characters."
                    }
                    v.maxLength?.let { max ->
                        if (value.length > max) errs[field.id] = v.errorMessage ?: "${field.label} must be at most $max characters."
                    }
                    v.pattern?.let { pat ->
                        if (!Regex(pat).containsMatchIn(value)) errs[field.id] = v.errorMessage ?: "${field.label} is invalid."
                    }
                }
            }
        }
        if (isRegister) {
            val password = inputs["password"] ?: ""
            if (password.isNotEmpty() && password != confirmPw) errs["confirmPassword"] = "Passwords don't match"
        }
        fieldErrors = errs
        return errs.isEmpty()
    }

    fun doSubmit() {
        val sb = submitBtn ?: return
        if (isRegister && !termsAccepted) { showTermsError = true; validate(); return }
        showTermsError = false
        if (validate()) onAction(sb.action)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Password reset success banner (login screen only)
        if (showPasswordResetSuccess) {
            PasswordResetSuccessBanner(onPasswordResetBannerDismissed)
        }

        // Google button (landing only)
        if (googleBtn != null) {
            GoogleButton(googleBtn.label) { onAction(googleBtn.action) }
            if (inputFields.isNotEmpty()) OrRow()
        }

        // SDUI input fields with ImeAction chaining
        inputFields.forEachIndexed { index, field ->
            val isLastField = !isRegister && index == inputFields.size - 1
            AuthField(
                label         = field.label,
                value         = inputs[field.id] ?: "",
                onValueChange = {
                    onInputChanged(field.id, it)
                    if (fieldErrors.containsKey(field.id)) fieldErrors = fieldErrors - field.id
                },
                keyboardType  = when (field.style) {
                    "email"    -> KeyboardType.Email
                    "password" -> KeyboardType.Password
                    else       -> KeyboardType.Text
                },
                isPassword       = field.style == "password",
                showPassword     = passwordVisible[field.id] ?: false,
                onTogglePassword = {
                    passwordVisible = passwordVisible + (field.id to !(passwordVisible[field.id] ?: false))
                },
                imeAction    = if (isLastField) ImeAction.Done else ImeAction.Next,
                onImeAction  = if (isLastField) { { doSubmit() } } else {
                    { focusRequesters.getOrNull(index + 1)?.requestFocus() }
                },
                focusRequester = focusRequesters.getOrNull(index),
                fieldError     = fieldErrors[field.id],
                helpText       = field.helpText
            )
        }

        // Confirm password (register only — client-side, not in SDUI)
        if (isRegister) {
            AuthField(
                label         = "Confirm password",
                value         = confirmPw,
                onValueChange = {
                    confirmPw = it
                    if (fieldErrors.containsKey("confirmPassword")) fieldErrors = fieldErrors - "confirmPassword"
                },
                keyboardType     = KeyboardType.Password,
                isPassword       = true,
                showPassword     = showConfirmPw,
                onTogglePassword = { showConfirmPw = !showConfirmPw },
                imeAction    = ImeAction.Done,
                onImeAction  = { doSubmit() },
                focusRequester = focusRequesters.getOrNull(inputFields.size),
                fieldError     = fieldErrors["confirmPassword"]
            )
        }

        // Terms & Privacy (register only)
        if (isRegister) {
            TermsCheckbox(
                accepted   = termsAccepted,
                showError  = showTermsError,
                onToggle   = { termsAccepted = !termsAccepted; showTermsError = false }
            )
        }

        // Submit button — enabled only when all fields are filled AND, for register,
        // the terms checkbox is ticked. Without the terms gate the button felt
        // submittable but doSubmit() silently bounced the click on an inline error.
        if (submitBtn != null) {
            val allFilled = inputFields.all { (inputs[it.id] ?: "").isNotBlank() } &&
                (!isRegister || confirmPw.isNotBlank()) &&
                (!isRegister || termsAccepted)
            Spacer(Modifier.height(2.dp))
            PrimaryButton(submitBtn.label, enabled = allFilled) { doSubmit() }
        }

        // Server-side error banner
        AnimatedVisibility(!error.isNullOrBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ErrorBanner(error ?: "")
        }

        // Forgot password link
        if (forgotBtn != null) {
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                Text(
                    forgotBtn.label,
                    color = White50, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onAction(forgotBtn.action) }
                        .padding(vertical = 10.dp, horizontal = 16.dp)
                )
            }
        }

        // Navigation links (e.g. "Already a member? Sign in")
        navBtns.forEach { btn ->
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                val q = btn.label.indexOf('?')
                if (q != -1 && q < btn.label.lastIndex) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${btn.label.substring(0, q + 1)}  ", color = White50, fontSize = 14.sp)
                        Text(
                            btn.label.substring(q + 1).trim(),
                            color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onAction(btn.action) }
                        )
                    }
                } else {
                    Text(btn.label, color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onAction(btn.action) }.padding(8.dp))
                }
            }
        }
    }
}

// ── Reset password success state ───────────────────────────────────────────────
@Composable
private fun ResetSuccessContent(email: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pulse by rememberInfiniteTransition(label = "checkPulse").animateFloat(
        0.96f, 1.04f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "checkPulseValue"
    )
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .scale(pulse)
                .background(Amber.copy(alpha = 0.10f), CircleShape)
                .border(1.dp, Amber.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.CheckCircle, null, tint = Amber, modifier = Modifier.size(44.dp))
        }
        Text("Check your inbox", color = MaterialTheme.colorScheme.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            "We've sent a reset link to",
            color = White50, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Text(
            email,
            color = Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap the link in the email to set a new password. The link expires in 30 minutes.",
            color = White50, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(4.dp))
        // Primary: jump straight to the user's email app — mirrors the same affordance
        // on VerifyEmailScreen so reset-password feels like a sibling flow.
        PrimaryButton("Open email app", onClick = {
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_APP_EMAIL)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        })
        Text(
            "Back to sign in",
            color = White50, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onBack).padding(8.dp)
        )
    }
}

// ── Form screen wrapper ────────────────────────────────────────────────────────
@Composable
private fun FormScreenContent(
    screenType: String,
    uiState: LoginUiState,
    showPasswordResetSuccess: Boolean = false,
    onInputChanged: (String, String) -> Unit,
    onAction: (String) -> Unit,
    onPasswordResetBannerDismissed: () -> Unit = {},
    onBack: () -> Unit
) {
    val title = when (screenType) {
        "login"           -> "Welcome back"
        "register"        -> "Create account"
        "forgot-password" -> "Account recovery"
        else              -> ""
    }
    val subtitle = when (screenType) {
        "login"           -> "Sign in to your Stationly account"
        "register"        -> "Join Stationly — never miss a train"
        "forgot-password" -> "Enter your email and we'll send a reset link"
        else              -> ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        // Slim sticky-feeling header: back arrow on its own row, title+subtitle left-
        // aligned right under it. Drops the centered logo + huge centred title which
        // ate ~140dp of vertical space and pushed the form behind the keyboard.
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, top = 8.dp)) {
            BackButton(onBack)
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title,    color = MaterialTheme.colorScheme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text(subtitle, color = White50,     fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
        when {
            uiState.isLoading -> Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) {
                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            }
            screenType == "forgot-password" && uiState.resetEmailSent ->
                ResetSuccessContent(uiState.resetEmail, onBack, Modifier.padding(horizontal = 28.dp))
            else -> SduiFormContent(
                screenType                 = screenType,
                components                 = uiState.layout?.components ?: emptyList(),
                inputs                     = uiState.inputs,
                error                      = if (!uiState.isBackendOffline) uiState.error else null,
                showPasswordResetSuccess   = showPasswordResetSuccess,
                onInputChanged             = onInputChanged,
                onAction                   = onAction,
                onPasswordResetBannerDismissed = onPasswordResetBannerDismissed,
                modifier                   = Modifier.padding(horizontal = 28.dp)
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ── Landing ────────────────────────────────────────────────────────────────────
@Composable
private fun LandingContent(
    onGoogleClick: () -> Unit,
    onEmailClick: () -> Unit,
    onRegisterClick: () -> Unit
) {
    var showMoreOptions by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (showMoreOptions) 180f else 0f, label = "chevron")

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Welcoming hero — sits in the upper portion, tight to the live pill.
        Spacer(Modifier.weight(0.45f))
        LivePill()
        Spacer(Modifier.height(18.dp))
        StationlyBrandLockup()
        Spacer(Modifier.height(10.dp))
        Text(
            "Stop being late. Start being smarter.",
            color = White50, fontSize = 13.sp, letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center
        )

        // Feature bullets — three concrete things you get after you sign up.
        // Fills the previously-dead middle space and primes curiosity for the
        // person hovering over the Google button.
        Spacer(Modifier.height(36.dp))
        FeatureBullets()

        // Action area — Google primary, email behind an expander.
        Spacer(Modifier.weight(0.55f))
        GoogleButton("Continue with Google", onGoogleClick)
        Spacer(Modifier.height(12.dp))

        OtherWaysToggle(
            expanded = showMoreOptions,
            chevronRotation = chevronRotation,
            onClick = { showMoreOptions = !showMoreOptions }
        )

        AnimatedVisibility(
            visible = showMoreOptions,
            enter = fadeIn(tween(200)) + expandVertically(tween(220)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onEmailClick, Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmberDim),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber)
                ) { Text("Continue with email", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("New here?  ", color = White50, fontSize = 14.sp)
                    Text(
                        "Create account",
                        color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onRegisterClick)
                            .padding(vertical = 6.dp, horizontal = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        TermsDisclaimer(Modifier.padding(horizontal = 12.dp))
        // Extra breathing room above the system nav bar — and above the staging
        // banner overlay if this is a staging build — so the disclaimer text
        // never crashes into the orange "STAGING ENVIRONMENT" strip.
        Spacer(Modifier.height(if (com.stationly.mobile.BuildConfig.FLAVOR == "staging") 28.dp else 8.dp))
    }
}

@Composable
private fun LivePill() {
    val pulse by rememberInfiniteTransition(label = "livePulse").animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "livePulseValue"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(50))
            .border(1.dp, White10, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    com.stationly.mobile.ui.theme.LocalThemeTokens.current.live.copy(alpha = pulse),
                    CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Live across London",
            color = White80,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun FeatureBullets() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        FeatureRow(Icons.Outlined.Schedule, "Live arrivals", "Tube · Overground · Elizabeth · DLR · Bus")
        FeatureRow(Icons.Outlined.Widgets, "Home-screen widgets", "Glance and go — no app open needed")
        FeatureRow(Icons.Outlined.NotificationsActive, "Quiet alerts", "Only when your line actually needs you")
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Amber.copy(alpha = 0.10f), CircleShape)
                .border(1.dp, Amber.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Amber, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = White50, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun StationlyBrandLockup() {
    // Roundel + wordmark as one tight vertical lockup. Wordmark in a single amber so
    // the brand reads as one word, not "STATI / ONLY".
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StationlyLogo(96)
        Spacer(Modifier.height(14.dp))
        Text(
            "STATIONLY",
            color = Amber,
            fontSize = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.0).sp
        )
    }
}

@Composable
private fun OtherWaysToggle(
    expanded: Boolean,
    chevronRotation: Float,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Text(
            if (expanded) "Hide other options" else "Other ways to sign in",
            color = White50,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = White50,
            modifier = Modifier.size(16.dp).rotate(chevronRotation)
        )
    }
}

// ── Auth overlay ───────────────────────────────────────────────────────────────
@Composable
private fun AuthenticatingOverlay(message: String = "Signing you in…") {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.88f, 1.08f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), "s"
    )
    // Scrim — uses M3's scrim slot (defaults to Black in both themes,
    // which is the correct overlay regardless of background).
    // pointerInput consumes every tap/drag so the form behind doesn't
    // remain interactive while sign-in / register / forgot-pw is in flight.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.86f))
            .pointerInput(Unit) {
                detectTapGestures { /* swallowed */ }
            },
        Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(72.dp).scale(pulse).background(Amber, CircleShape).border(2.dp, White20, CircleShape), Alignment.Center) {
                Text("S", color = AmberDark, fontSize = 32.sp, fontWeight = FontWeight.Black)
            }
            Text(message, color = White50, fontSize = 13.sp, letterSpacing = 0.5.sp)
        }
    }
}

// ── Reset password confirm (from deep link oobCode) ───────────────────────────
@Composable
private fun ResetConfirmContent(
    uiState: LoginUiState,
    onInputChanged: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit
) {
    var newPassword     by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showNewPw       by remember { mutableStateOf(false) }
    var showConfirmPw   by remember { mutableStateOf(false) }
    var fieldErrors     by remember { mutableStateOf(emptyMap<String, String>()) }
    val newPwFocus      = remember { FocusRequester() }
    val confirmFocus    = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(300)
        try { newPwFocus.requestFocus() } catch (_: Exception) {}
    }

    fun validate(): Boolean {
        val errs = mutableMapOf<String, String>()
        if (newPassword.isEmpty())       errs["new"]     = "Please enter a new password"
        else if (newPassword.length < 6) errs["new"]     = "Password must be at least 6 characters"
        if (confirmPassword.isEmpty())   errs["confirm"] = "Please confirm your password"
        else if (newPassword != confirmPassword) errs["confirm"] = "Passwords don't match"
        fieldErrors = errs
        return errs.isEmpty()
    }

    fun submit() {
        if (!validate()) return
        onInputChanged("newPassword", newPassword)
        onSubmit()
    }

    val allFilled = newPassword.isNotBlank() && confirmPassword.isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, top = 8.dp)) {
            BackButton(onBack)
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Set new password", color = MaterialTheme.colorScheme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text("Choose something you'll actually remember.", color = White50, fontSize = 14.sp)
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AuthField(
                label            = "New password",
                value            = newPassword,
                onValueChange    = { newPassword = it; fieldErrors = fieldErrors - "new" },
                keyboardType     = KeyboardType.Password,
                isPassword       = true,
                showPassword     = showNewPw,
                onTogglePassword = { showNewPw = !showNewPw },
                imeAction        = ImeAction.Next,
                onImeAction      = { confirmFocus.requestFocus() },
                focusRequester   = newPwFocus,
                fieldError       = fieldErrors["new"]
            )
            AuthField(
                label            = "Confirm password",
                value            = confirmPassword,
                onValueChange    = { confirmPassword = it; fieldErrors = fieldErrors - "confirm" },
                keyboardType     = KeyboardType.Password,
                isPassword       = true,
                showPassword     = showConfirmPw,
                onTogglePassword = { showConfirmPw = !showConfirmPw },
                imeAction        = ImeAction.Done,
                onImeAction      = { submit() },
                focusRequester   = confirmFocus,
                fieldError       = fieldErrors["confirm"]
            )
            Spacer(Modifier.height(2.dp))
            PrimaryButton("Reset Password", enabled = allFilled) { submit() }
            AnimatedVisibility(!uiState.error.isNullOrBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ErrorBanner(uiState.error ?: "")
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ── Root screen ────────────────────────────────────────────────────────────────
@Composable
fun LoginScreen(
    screenType: String = "login",
    resetOobCode: String = "",
    onNavigateToSummary: () -> Unit,
    onNeedsEmailVerification: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRegister: () -> Unit = {},
    onNavigateToForgotPassword: () -> Unit = {},
    showPasswordResetSuccess: Boolean = false,
    onPasswordResetBannerShown: () -> Unit = {},
    viewModel: LoginViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlayMessage = when (screenType) {
        "register"        -> "Creating your account…"
        "forgot-password" -> "Sending reset link…"
        else              -> "Signing you in…"
    }

    LaunchedEffect(screenType) {
        if (screenType == "reset-confirm" && resetOobCode.isNotBlank()) {
            viewModel.setResetOobCode(resetOobCode)
        } else {
            viewModel.setScreenType(screenType)
        }
    }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.signInWithGoogle(GoogleSignIn.getSignedInAccountFromIntent(result.data), onNavigateToSummary)
    }
    fun launchGoogle() = googleLauncher.launch(viewModel.authManager.getGoogleSignInClient().signInIntent)

    Box(Modifier.fillMaxSize().background(BgColor)) {
        SubtleBackground()

        when (screenType) {
            "login" -> {
                var showEmailForm by remember { mutableStateOf(false) }
                AnimatedContent(showEmailForm,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "loginFlow"
                ) { emailMode ->
                    if (emailMode) {
                        FormScreenContent(
                            screenType               = "login",
                            uiState                  = uiState,
                            showPasswordResetSuccess = showPasswordResetSuccess,
                            onInputChanged           = { id, value ->
                                viewModel.onInputChanged(id, value)
                                if (uiState.error != null) viewModel.clearError()
                            },
                            onAction = { action ->
                                viewModel.handleAction(
                                    action                    = action,
                                    onAuthSuccess             = onNavigateToSummary,
                                    onNeedsEmailVerification  = onNeedsEmailVerification,
                                    onNavigateToRegister      = onNavigateToRegister,
                                    onNavigateToForgotPassword = onNavigateToForgotPassword,
                                    onGoogleSignInRequested   = ::launchGoogle
                                )
                            },
                            onPasswordResetBannerDismissed = onPasswordResetBannerShown,
                            onBack = { showEmailForm = false; viewModel.clearFormState() }
                        )
                    } else {
                        LandingContent(
                            onGoogleClick   = ::launchGoogle,
                            onEmailClick    = { showEmailForm = true },
                            onRegisterClick = onNavigateToRegister
                        )
                    }
                }
            }
            "register", "forgot-password" -> FormScreenContent(
                screenType     = screenType,
                uiState        = uiState,
                onInputChanged = { id, value ->
                    viewModel.onInputChanged(id, value)
                    if (uiState.error != null) viewModel.clearError()
                },
                onAction = { action ->
                    viewModel.handleAction(
                        action                    = action,
                        onAuthSuccess             = onNavigateToSummary,
                        onNeedsEmailVerification  = onNeedsEmailVerification,
                        onNavigateToRegister      = onNavigateToRegister,
                        onNavigateToLogin         = onNavigateToLogin,
                        onNavigateToForgotPassword = onNavigateToForgotPassword,
                        onGoogleSignInRequested   = ::launchGoogle
                    )
                },
                onBack = { viewModel.clearFormState(); onNavigateToLogin() }
            )
            "reset-confirm" -> ResetConfirmContent(
                uiState        = uiState,
                onInputChanged = { id, value ->
                    viewModel.onInputChanged(id, value)
                    if (uiState.error != null) viewModel.clearError()
                },
                onSubmit       = { viewModel.confirmPasswordReset(onNavigateToLogin) },
                onBack         = { viewModel.clearFormState(); onNavigateToLogin() }
            )
        }

        AnimatedVisibility(uiState.isAuthenticating, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
            AuthenticatingOverlay(overlayMessage)
        }
        AnimatedVisibility(uiState.isBackendOffline, enter = fadeIn(tween(400)), exit = fadeOut(tween(300))) {
            com.stationly.mobile.ui.common.ServiceUnavailableScreen(
                context                = "login_sync",
                overridingErrorMessage = uiState.error,
                onRetry                = { viewModel.setScreenType(screenType) },
                onDismiss              = { viewModel.clearOfflineState() }
            )
        }
    }
}
