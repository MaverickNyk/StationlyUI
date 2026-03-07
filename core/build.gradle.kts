/*
 * StationlyUI Core Module
 * Kotlin Multiplatform shared module for business logic, models, and API
 * 
 * This module contains:
 * - Shared data models (UserSelection, LineStatus, Prediction, etc.)
 * - Business logic (Use Cases for selection flow, real-time processing)
 * - API interfaces (Retrofit/HTTP clients)
 * - Platform abstraction (WidgetManager, NotificationManager, StorageManager)
 */

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("app.cash.sqldelight")
}

kotlin {
    // Target all platforms: Android, iOS, Web (via WASM in future)
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
        
        // Android-specific configuration
        publishAllLibraryVariants()
    }
    
    // Explicit iOS targets to avoid preset issues
    iosArm64()
    iosSimulatorArm64()

    // Web target (Wasm)
    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    // Common configuration for all targets
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Core Kotlin libraries
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                
                // HTTP client (Ktor for multiplatform)
                implementation("io.ktor:ktor-client-core:3.0.0-rc-1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0-rc-1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0-rc-1")
                
                // Date/time handling
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                
                // SQLDelight common
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
                
                // Kermit logging
                implementation("co.touchlab:kermit:2.0.4")
            }
        }
        
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
            }
        }
        
        val androidMain by getting {
            dependencies {
                // Ktor for Android
                implementation("io.ktor:ktor-client-android:3.0.0-rc-1")
                implementation("androidx.core:core-ktx:1.12.0")
                
                // SQLDelight Android driver
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                
                // Android-specific dependencies for platform implementations
                implementation("androidx.work:work-runtime-ktx:2.8.1")
                implementation("com.google.firebase:firebase-messaging-ktx:23.2.1")
                implementation("androidx.preference:preference-ktx:1.2.0")
            }
        }
        
        val iosArm64Main by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.0.0-rc-1")
            }
        }
        
        val iosSimulatorArm64Main by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.0.0-rc-1")
            }
        }
        
        val wasmJsMain by getting {
            dependencies {
                // Ktor for WASM/JS
                implementation("io.ktor:ktor-client-js:3.0.0-rc-1")
                // Browser localStorage API
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
    }
}

// Android-specific configuration for the library
android {
    namespace = "com.stationly.core"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("StationlyDatabase") {
            packageName.set("com.stationly.db")
        }
    }
}