package com.stationly.mobile.ui.login

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.mobile.util.SduiThemeManager
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CenterAlignedTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    screenType: String = "login",
    onNavigateToSummary: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRegister: () -> Unit = {},
    onNavigateToForgotPassword: () -> Unit = {},
    viewModel: LoginViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = uiState.layout?.theme
    val dynamicPrimaryColor = SduiThemeManager.getPrimaryColor(theme)
    val dynamicBackgroundColor = SduiThemeManager.getBackgroundColor(theme)

    LaunchedEffect(screenType) {
        viewModel.setScreenType(screenType)
    }

    val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        viewModel.signInWithGoogle(task, onNavigateToSummary)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (screenType != "login") {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                if (screenType == "register") "Create Account" else "Account Recovery",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = onNavigateToLogin,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(44.dp)
                                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            }
        ) { padding ->
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = dynamicPrimaryColor, strokeWidth = 3.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { 
                        Spacer(modifier = Modifier.height(if (screenType == "login") 60.dp else 20.dp)) 
                    }

                    val components = uiState.layout?.components ?: emptyList()
                    val mainComponents = components.filter { !(it is SduiAppComponent.Button && (it.action == "NAVIGATE_TO_FORGOT_PASSWORD" || it.action == "FORGOT_PASSWORD_ACTION")) }
                    val forgotBtn = components.find { it is SduiAppComponent.Button && (it.action == "NAVIGATE_TO_FORGOT_PASSWORD" || it.action == "FORGOT_PASSWORD_ACTION") } as? SduiAppComponent.Button

                    mainComponents.forEach { component ->
                        item(key = component.id ?: component.javaClass.simpleName) {
                            when (component) {
                                is SduiAppComponent.Image -> {
                                    val painter = if (component.imageUrl == "stationly_logo") {
                                        painterResource(id = com.stationly.mobile.R.drawable.stationly_logo)
                                    } else {
                                        // Fallback icon if no Coil
                                        painterResource(id = com.stationly.mobile.R.drawable.ic_launcher_foreground)
                                    }
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            painter = painter,
                                            contentDescription = component.contentDescription,
                                            modifier = Modifier.size(if (component.style == "logo") 60.dp else 120.dp)
                                        )
                                    }
                                }
                                is SduiAppComponent.Text -> {
                                    val isTitle = component.style == "title"
                                    Text(
                                        text = component.text,
                                        style = if (isTitle) MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold) else MaterialTheme.typography.bodyLarge,
                                        color = Color.White,
                                        textAlign = when(component.textAlign) {
                                            "center" -> TextAlign.Center
                                            else -> TextAlign.Start
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        lineHeight = if (isTitle) 42.sp else 24.sp
                                    )
                                }
                                is SduiAppComponent.Input -> {
                                    val value = uiState.inputs[component.id] ?: ""
                                    val isPassword = component.style == "password"
                                    
                                    OutlinedTextField(
                                        value = value,
                                        onValueChange = { viewModel.onInputChanged(component.id ?: "", it) },
                                        label = { Text(component.label) },
                                        placeholder = { component.placeholder?.let { Text(it) } },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = if (component.style == "email") androidx.compose.ui.text.input.KeyboardType.Email 
                                                           else if (isPassword) androidx.compose.ui.text.input.KeyboardType.Password 
                                                           else androidx.compose.ui.text.input.KeyboardType.Text
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = dynamicPrimaryColor,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = dynamicPrimaryColor,
                                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                                            cursorColor = dynamicPrimaryColor
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )
                                }
                                is SduiAppComponent.Button -> {
                                    val isGoogleAction = component.action == "GOOGLE_LOGIN_ACTION"
                                    val isTransparent = component.color == "transparent"
                                    
                                    val buttonBackgroundColor = if (isGoogleAction) {
                                        Color.White
                                    } else if (isTransparent) {
                                        Color.Transparent
                                    } else {
                                        try { Color(android.graphics.Color.parseColor(component.color)) } catch (e: Exception) { dynamicPrimaryColor }
                                    }

                                    val buttonContentColor = if (isGoogleAction) {
                                        Color(0xFF1F1F1F) // Google Sign-In Typography Gray
                                    } else if (component.color == "#FFFFFF") {
                                        Color.Black
                                    } else if (isTransparent) {
                                        Color.White.copy(alpha = 0.7f)
                                    } else {
                                        Color.Black
                                    }
                                    
                                    Button(
                                        onClick = { 
                                            viewModel.handleAction(
                                                action = component.action, 
                                                onAuthSuccess = onNavigateToSummary,
                                                onNavigateToRegister = onNavigateToRegister,
                                                onNavigateToLogin = onNavigateToLogin,
                                                onNavigateToForgotPassword = onNavigateToForgotPassword,
                                                onGoogleSignInRequested = {
                                                    val signInIntent = viewModel.authManager.getGoogleSignInClient().signInIntent
                                                    googleSignInLauncher.launch(signInIntent)
                                                }
                                            ) 
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (isTransparent) 48.dp else 54.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = buttonBackgroundColor,
                                            contentColor = buttonContentColor
                                        ),
                                        shape = CircleShape,
                                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (isGoogleAction) 2.dp else 0.dp),
                                        border = if (isGoogleAction) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF747775).copy(alpha = 0.3f)) else null
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isGoogleAction) {
                                                Image(
                                                    painter = painterResource(id = com.stationly.mobile.R.drawable.ic_google_standard),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                            }
                                            Text(
                                                text = component.label, 
                                                fontWeight = FontWeight.Bold, 
                                                fontSize = 15.sp,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    
                    item {
                        AnimatedVisibility(
                            visible = uiState.error != null && !uiState.isBackendOffline,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Surface(
                                color = Color(0xFFE91E63).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE91E63).copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(imageVector = Icons.Filled.Info, contentDescription = "Error", tint = Color(0xFFF06292), modifier = Modifier.size(20.dp))
                                    Text(text = uiState.error ?: "", color = Color(0xFFF06292), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }


                    item { Spacer(modifier = Modifier.height(24.dp)) }

                    if (forgotBtn != null && screenType == "login") {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = forgotBtn.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .clickable { 
                                            viewModel.handleAction(
                                                action = forgotBtn.action,
                                                onNavigateToForgotPassword = onNavigateToForgotPassword
                                            )
                                        }
                                        .padding(16.dp),
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                )
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(40.dp)) }
                }
            }
        }

        // Full-screen overlay loader
        AnimatedVisibility(
            visible = uiState.isAuthenticating,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition()
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.9f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                // App Logo Placeholder (Stylized Circle)
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(scale)
                        .background(dynamicPrimaryColor, CircleShape)
                        .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        color = Color.Black,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // ── Service Unavailable Overlay (Must be at the very end to render on top) ──
        androidx.compose.animation.AnimatedVisibility(
            visible = uiState.isBackendOffline,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(300))
        ) {
            com.stationly.mobile.ui.common.ServiceUnavailableScreen(
                context = "login_sync",
                overridingErrorMessage = uiState.error,
                onRetry = { viewModel.setScreenType(screenType) },
                onDismiss = { viewModel.clearOfflineState() }
            )
        }
    }
}

