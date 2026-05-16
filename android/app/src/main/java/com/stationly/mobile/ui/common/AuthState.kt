package com.stationly.mobile.ui.common

import androidx.compose.runtime.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

/**
 * Reactive wrapper around Firebase's AuthStateListener. The returned State updates the
 * moment Firebase decides the session has changed — sign-in, sign-out, token revoked,
 * account deleted from the console, etc. Without this the UI evaluates currentUser
 * exactly once at composition and never finds out the session is no longer valid.
 */
@Composable
fun rememberFirebaseAuthState(): State<FirebaseUser?> {
    val state = remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { state.value = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    return state
}
