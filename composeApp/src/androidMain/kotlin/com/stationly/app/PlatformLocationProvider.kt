package com.stationly.app

import com.stationly.app.ui.selection.LocationProvider
import com.stationly.app.ui.selection.NoOpLocationProvider
import com.stationly.app.ui.selection.platformLocationProvider

// On Android, SelectionScreen wires the real AndroidLocationProvider
// via the onGoogleSignIn / Activity callback pattern.
// The ViewModel default falls back to NoOp; the Activity overrides it.
actual fun platformLocationProvider(): LocationProvider = NoOpLocationProvider
