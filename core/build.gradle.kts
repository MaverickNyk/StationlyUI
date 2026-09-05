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
                // Lets the 401 policy in NetworkModule be driven against scripted
                // responses. That path decides whether a user stays signed in and
                // is otherwise UNTESTABLE: `account_gone` needs a deleted account,
                // and the retry needs a server that 401s a freshly minted token,
                // neither of which can be arranged against the real backend.
                implementation("io.ktor:ktor-client-mock:3.0.0-rc-1")
            }
        }

        // JVM-side tests for the Android target. This exists for ONE thing that
        // cannot be tested anywhere else: the SQLDelight migration.
        //
        // `commonTest` cannot do it — there is no driver in common code — and an
        // instrumented test would need a device on every run. The JDBC driver
        // gives a real SQLite file (or an in-memory one) inside an ordinary JVM
        // unit test, which is enough to build a database at the OLD schema,
        // migrate it, and assert what survived. That is the only way to see the
        // failure that matters here: `Schema.create` runs solely on an empty
        // database, so a missing migration is invisible on every development
        // device and shows up first on the phones that have had the app longest.
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
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

    testOptions {
        unitTests.all {
            // The v1 golden fixtures live in docs/, not in test resources, and
            // that is deliberate: they are read by a human deciding whether a
            // behaviour change is intended at least as often as by this test.
            // Passing the repo root explicitly beats relying on the test task's
            // working directory, which is an AGP implementation detail.
            it.systemProperty("stationly.repoRoot", rootDir.absolutePath)
        }
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

            // ✅ `verifyMigrations` IS enabled, and this is what it took.
            //
            // It compares a database built by applying the migrations to one
            // built by `Schema.create`, and fails the build when they differ.
            // That closes the gap this file used to describe by hand: the
            // `.sqm` duplicates its DDL from the `.sq`, nothing compared them,
            // and the drift would have surfaced only as a runtime failure on
            // UPGRADED installs — never on the fresh ones a developer tests on.
            //
            // ## The bit that is not a one-liner
            // The task needs a recorded BASELINE at
            // `src/commonMain/sqldelight/databases/<version>.db`, and without
            // one it fails with "Verifying a migration requires a database file
            // to be present. To generate one, use the generate schema Gradle
            // task" — pointing at a `generate…Schema` task that SQLDelight 2.0.2
            // does not register in this configuration. That dead end is why an
            // earlier attempt was reverted.
            //
            // `1.db` is therefore built by hand, and correctly so: it must be
            // the schema as the LAST RELEASED Android build created it, which is
            // a fact about a shipped APK rather than about anything in this
            // tree. It comes from the same checked-in fixture the migration test
            // uses, so the two can never disagree:
            //
            //   sqlite3 core/src/commonMain/sqldelight/databases/1.db \
            //     < docs/android-v2/fixtures/v1/schema-v1.sql
            //   sqlite3 core/src/commonMain/sqldelight/databases/1.db \
            //     "PRAGMA user_version = 1;"
            //
            // ## Adding the next migration
            // Write `2.sqm`, and do NOT touch `1.db`. The baseline is the
            // starting point of the whole chain, not a snapshot of the present;
            // regenerating it from the current schema would make the check
            // vacuous — it would be comparing the schema against itself.
            //
            // The task is not wired into `check`, so it is named explicitly in
            // the gate (see docs/android-v2/README.md).
            verifyMigrations.set(true)
        }
    }
}
