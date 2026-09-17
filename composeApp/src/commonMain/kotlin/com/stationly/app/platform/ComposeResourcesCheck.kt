package com.stationly.app.platform

/**
 * True when the Compose-Multiplatform resource bundle is present at runtime.
 *
 * iOS: the prebuilt-XCFramework integration does NOT embed composeResources —
 * they're copied into the app bundle by the "Copy Compose Resources" build
 * phase (iosApp/project.yml). If that phase was skipped (stale build, manual
 * xcodebuild without the gradle resource assembly), any `Res.drawable/`
 * `Res.font` read throws MissingResourceException **at draw time** and crashes
 * the first frame (Session-3 device crash). Gating every runtime read on this
 * flag turns "packaging regression = crash loop" into "packaging regression =
 * drawn fallback logo + system font".
 *
 * Android (composeApp target): resources ship inside the AAR — always true.
 */
expect val composeResourcesBundled: Boolean
