pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.2.0"
        kotlin("plugin.serialization") version "2.2.0"
        kotlin("plugin.compose") version "2.2.0"
        id("org.jetbrains.compose") version "1.8.0"
        id("app.cash.sqldelight") version "2.0.2"
        id("com.android.application") version "8.8.0" apply false
        id("com.android.library") version "8.8.0" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StationlyUI"
include(":core")
include(":android:app")
include(":composeApp")
include(":web")
