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
        }
        create("staging") {
            dimension = "environment"
            versionNameSuffix = "-staging"
            resValue("string", "app_name", "Stationly Staging")
            buildConfigField("String", "STATIONLY_API_KEY", "\"${localProperties.getProperty("staging.STATIONLY_API_KEY") ?: ""}\"")
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
    // Downloadable Google Fonts — used for the Stationly brand wordmark
    // (Inter Tight). Fetched via Google Play Services Fonts provider so
    // we don't ship a TTF and fonts can be added/swapped without an
    // APK update. See ui/theme/Type.kt for the FontFamily declaration.
    implementation("androidx.compose.ui:ui-text-google-fonts")

    // Navigation — must match Compose 1.7 (compose-bom 2024.09.00). nav 2.7.x
    // targets Compose 1.6 and its screen transitions break on 1.7's AnimatedContent
    // rewrite: fast navigate+pop (e.g. open/close Profile repeatedly) leaves the
    // fade transition stuck mid-flight, so destinations overlap (ghosted Profile
    // behind Summary) and eventually nothing draws → blank screen. 2.8.x is the
    // Compose-1.7-aligned line and fixes this; it also brings lifecycle 2.8.x in
    // transitively, so the viewmodel-compose pin below is lifted to match.
    implementation("androidx.navigation:navigation-compose:2.8.0")
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
