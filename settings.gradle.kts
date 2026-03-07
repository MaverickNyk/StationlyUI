pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.0.0"
        kotlin("plugin.serialization") version "2.0.0"
        kotlin("plugin.compose") version "2.0.0"
        id("org.jetbrains.compose") version "1.6.11"
        id("app.cash.sqldelight") version "2.0.2"
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
include(":web")