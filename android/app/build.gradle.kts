import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val f = project.rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

/**
 * The custom URL scheme this build answers to, declared ONCE per flavour and
 * spent in two places that must never disagree:
 *
 *  - `${deepLinkScheme}` in the manifest, which decides what Android is willing
 *    to deliver to us;
 *  - `BuildConfig.DEEP_LINK_SCHEME`, which decides what we do with it once it
 *    arrives (`parseDeepLink`, and the links we build ourselves for FCM).
 *
 * Two literals is how iOS lost two days: the app registered
 * `stationly-staging://` and then compared the incoming URL against a hardcoded
 * `"stationly"`. Every staging link was silently dropped — nothing errored, taps
 * simply did nothing. Setting both from one argument makes that mismatch
 * unspellable rather than merely discouraged.
 */
fun com.android.build.api.dsl.ApplicationProductFlavor.deepLinkScheme(scheme: String) {
    manifestPlaceholders["deepLinkScheme"] = scheme
    buildConfigField("String", "DEEP_LINK_SCHEME", "\"$scheme\"")
}

android {
    namespace = "com.stationly.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stationly.mobile"
        minSdk = 26
        // Google Play requires new apps and updates to target API 35
        // (Android 15) as of 31 Aug 2025. The app already opts into
        // edge-to-edge via enableEdgeToEdge() in MainActivity, which is the
        // main behaviour change enforced at this level.
        targetSdk = 35
        // v2: patched reCAPTCHA (security fix) over the first uploaded bundle.
        // versionName stays 1.0 (no user-facing feature change).
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Prod is signed with its OWN key so its (package + SHA-1) is distinct
        // from staging's. Staging keeps the shared debug key, so this does not
        // affect staging at all. Creds live in local.properties (git-ignored).
        create("prod") {
            localProperties.getProperty("prod.keystore.path")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = localProperties.getProperty("prod.keystore.storePassword")
                keyAlias = localProperties.getProperty("prod.keystore.keyAlias")
                keyPassword = localProperties.getProperty("prod.keystore.keyPassword")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("prod") {
            dimension = "environment"
            signingConfig = signingConfigs.getByName("prod")
            buildConfigField("String", "STATIONLY_API_KEY", "\"${localProperties.getProperty("prod.STATIONLY_API_KEY") ?: ""}\"")
            deepLinkScheme("stationly")
        }
        create("staging") {
            dimension = "environment"
            versionNameSuffix = "-staging"
            resValue("string", "app_name", "Stationly Staging")
            // `v2_launcher_label` lived here until AV2-3.5. It named the SECOND
            // launcher icon while the shared UI was a staging-only door beside
            // the shipped app. There is one door now, and it is `app_name`.
            buildConfigField("String", "STATIONLY_API_KEY", "\"${localProperties.getProperty("staging.STATIONLY_API_KEY") ?: ""}\"")
            deepLinkScheme("stationly-staging")
        }
    }

    buildTypes {
        release {
            // R8 full mode: shrink + obfuscate. Shrinks the bundle and makes
            // the embedded BuildConfig.STATIONLY_API_KEY harder to recover.
            // Keep rules for the reflection-based libraries (Gson, kotlinx
            // serialization, Firebase) live in proguard-rules.pro.
            // NOTE: always smoke-test a release build before uploading — R8
            // strips unused code aggressively.
            isMinifyEnabled = true
            isShrinkResources = true
            // Bundle native debug symbols (from dependency .so files) into the
            // AAB so Play can symbolicate native crashes/ANRs. Resolves the
            // "no debug symbols" upload warning. Lives in BUNDLE-METADATA, not
            // the delivered APK, so it doesn't increase install size.
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // KMP Core Module
    implementation(project(":core"))

    // The shared Compose Multiplatform UI — the same 101 files iOS runs on.
    // Since AV2-3.5 it is not "the shared UI" as distinct from the app's: it IS
    // the app. `MainActivity` is `setContent { App(...) }` and there is no other
    // screen. See docs/android-v2/DECISIONS.md D2.
    //
    // This was `stagingImplementation` until the cutover, and the reason it was
    // scoped is worth keeping written down. `:composeApp` builds against Compose
    // Multiplatform 1.8.0 and drags every AndroidX Compose artifact up with it:
    //
    //     androidx.compose.runtime     1.7.0 -> 1.8.0
    //     androidx.compose.foundation  1.7.0 -> 1.8.0
    //     androidx.compose.material3   1.3.0 -> 1.3.2
    //
    // overriding the compose-bom pin below. The hazard was never the bump on its
    // own: it was `navigation-compose:2.8.0`, which is pinned to Compose **1.7**
    // and which v1's NavHost depended on. That pairing exists because it already
    // went wrong once — a shipped blank screen, recorded in the navigation
    // comment this replaced.
    //
    // v1's NavHost is deleted and `navigation-compose` with it, so the pairing
    // has no second half left to break. The only NavHost in the app is the
    // shared one, which brings its own navigation at its own aligned version.
    // The bom below is now a FLOOR for the app module's remaining Compose (the
    // Daydream), not a ceiling on the shared UI's.
    //
    // Staging has been running exactly this resolution since AV2-3.1, on
    // hardware, through three device passes.
    implementation(project(":composeApp"))

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Material3 Theme (for XML themes)
    implementation("com.google.android.material:material:1.10.0")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // ONE font pipeline, and this is not it (AV2-3.5 task (c)).
    //
    // v1 loaded Inter Tight through the Google Play Services Fonts provider
    // (`ui-text-google-fonts` + `res/values/font_certs.xml`), and `:composeApp`
    // bundles the same six static weights as compose-resources. Two font
    // pipelines in one app is two places for the brand face to come out wrong,
    // and the downloadable one has a failure mode the bundled one does not: no
    // GMS, no network, or a blocked provider silently degrades the wordmark to
    // the system face. Both users of the provider — v1's `Type.kt` and its
    // `font_certs.xml` — are deleted, so the dependency goes with them.
    //
    // The cost is ~250KB of TTF in the APK. That is the price of the app and
    // the widget agreeing about what Stationly looks like offline.

    // Navigation — `androidx.navigation:navigation-compose` was here until
    // AV2-3.5 and is deliberately NOT replaced. v1's NavHost was its only user;
    // the shared UI brings its own navigation, aligned to its own Compose.
    // Adding a second navigation library back would recreate the exact version
    // pairing whose breakage shipped a blank screen — see the `:composeApp`
    // comment above.

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // FCM and Auth
    implementation(platform("com.google.firebase:firebase-bom:32.3.1"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    // firebase-auth 22.1.2 (from the BOM above) transitively pulls
    // recaptcha 18.1.2, which Google flagged with a CRITICAL security
    // vulnerability and deprecated. Pin the patched latest explicitly so
    // Gradle resolves it over the vulnerable transitive version. Same public
    // API, so it's a drop-in for firebase-auth's internal use. Revisit when
    // the firebase-bom is bumped (a newer BOM pulls a patched recaptcha itself).
    implementation("com.google.android.recaptcha:recaptcha:18.9.1")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // WorkManager (for background widget updates)
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Play Services Coroutines (for .await() on Firebase tasks)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.coil-kt:coil-compose:2.5.0")
}
