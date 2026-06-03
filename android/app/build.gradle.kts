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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stationly.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("prod") {
            dimension = "environment"
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
            isMinifyEnabled = false
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
