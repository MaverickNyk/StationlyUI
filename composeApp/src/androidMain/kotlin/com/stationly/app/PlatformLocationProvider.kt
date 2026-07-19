// The actual must live in the SAME package as the commonMain expect
// (ui.selection) — this file previously declared `com.stationly.app`, which
// left the expect unmatched and broke the (unshipped) android target.
package com.stationly.app.ui.selection

// This composeApp android target is a build-verification surface only — the
// shipped Android app (android/) has its own location stack, so NoOp is the
// honest provider here.
actual fun platformLocationProvider(): LocationProvider = NoOpLocationProvider
