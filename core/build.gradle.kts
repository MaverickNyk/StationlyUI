plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("app.cash.sqldelight")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        publishAllLibraryVariants()
    }

    iosArm64()
    iosSimulatorArm64()

    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:3.0.0-rc-1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0-rc-1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0-rc-1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
                implementation("co.touchlab:kermit:2.0.4")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-android:3.0.0-rc-1")
                implementation("androidx.core:core-ktx:1.12.0")
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                implementation("androidx.work:work-runtime-ktx:2.8.1")
                implementation("com.google.firebase:firebase-messaging-ktx:23.2.1")
                implementation("com.google.firebase:firebase-auth-ktx:22.1.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
                implementation("androidx.preference:preference-ktx:1.2.0")
            }
        }

        // Shared iOS source set — both arm64 and simulator depend on this
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.0.0-rc-1")
                implementation("app.cash.sqldelight:native-driver:2.0.2")
            }
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }

        val wasmJsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:3.0.0-rc-1")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
    }
}

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
            // Schema lives in commonMain/sqldelight/.../StationlyDatabase.sq.
            // No `migrations/` directory yet — the app hasn't shipped, so
            // every install starts fresh on the current schema. When we
            // DO ship and later evolve the schema, add `N.sqm` files in a
            // `migrations/` directory next to the `.sq` file; SQLDelight
            // infers the current schema version from the migration count
            // (e.g. one migration → version 2).
        }
    }
}
