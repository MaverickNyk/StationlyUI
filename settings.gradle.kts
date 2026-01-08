pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "1.9.20"
        kotlin("plugin.serialization") version "1.9.20"
        id("org.jetbrains.compose") version "1.6.11"
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