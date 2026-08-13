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
                implementation("io.ktor:ktor-client-websockets:3.0.0-rc-1")
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
            // Schema lives in commonMain/sqldelight/.../StationlyDatabase.sq,
            // migrations alongside it in `migrations/N.sqm`. SQLDelight infers
            // the current version from the migration COUNT — one migration
            // means version 2 — so a new `.sqm` is what bumps the database.
            //
            // Every change from here needs one. `Schema.create` runs only on an
            // empty database, so a change made to the `.sq` alone reaches
            // fresh installs and NOTHING else; a new table then fails with
            // "no such table" on precisely the devices that have been using the
            // app longest. Adding a column with a DEFAULT happens to survive
            // that (old rows read the default), which is why the omission went
            // unnoticed for several schema changes.

            // ⚠️ `verifyMigrations` is NOT enabled, and turning it on is not a
            // one-liner — it was tried and reverted here, so the next person
            // does not repeat it.
            //
            // The gap is real: `1.sqm` duplicates its `CREATE TABLE` from
            // `StationlyDatabase.sq` by hand, nothing compares the two, and the
            // drift would surface only as a runtime failure on UPGRADED installs
            // — never on the fresh ones a developer tests with.
            //
            // `verifyMigrations.set(true)` makes `:core:build` FAIL with
            // "Verifying a migration requires a database file to be present",
            // and the `generate…Schema` task the error points at is not
            // registered by SQLDelight 2.0.2 in this configuration. Closing it
            // properly means adding a recorded schema baseline
            // (`sqldelight/databases/<version>.db`) and checking it in, which is
            // its own change with its own verification — not something to
            // smuggle in alongside unrelated work.
            //
            // Until then the `.sqm` and the `.sq` are kept identical BY HAND.
            // Change one, change the other, in the same commit.
        }
    }
}
