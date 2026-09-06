import java.util.Properties
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    val xcf = XCFramework("composeApp")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "composeApp"
            isStatic = false
            linkerOpts("-lsqlite3")
            xcf.add(this)
        }
    }

    sourceSets {
        // composeApp had no test source set at all. Added for the pure logic
        // that ships strings straight to the screen — a filter summary built
        // from two kinds of pick is invisible to every compiler check and
        // immediately visible to everyone else when it is wrong.
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                // Multiplatform navigation
                implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.0-beta01")
                // Multiplatform lifecycle viewmodel
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0-beta01")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.0-beta01")
                implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.8.1")
                implementation("com.google.firebase:firebase-auth-ktx:22.1.1")
                implementation("com.google.android.gms:play-services-auth:20.7.0")
                // Nearby-station search. `:android:app` already ships this (at
                // 21.0.1); :composeApp needs its own edge because the dependency
                // runs app → library, so the app's classpath is not this one's.
                implementation("com.google.android.gms:play-services-location:21.0.1")
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
            }
        }

        // The Android actuals are mostly Android APIs, which a JVM unit test
        // cannot exercise. What it CAN exercise is the handful of decisions
        // inside them that are not Android at all: a filename that has to match
        // the shipped app's byte for byte, and a haptic mapping that is pure
        // taste. Both are invisible to every compiler check and both have a
        // second implementation in `android/` that they must not drift from.
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
            }
        }

        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}

android {
    namespace = "com.stationly.composeapp"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.stationly.app.resources"
    generateResClass = auto
}
