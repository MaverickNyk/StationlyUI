plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "stationly-web"
        browser {
            commonWebpackConfig {
                outputFileName = "stationly-web.js"
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve resources from the module
                        add(project.file("src/wasmJsMain/resources").absolutePath)
                    }
                }
            }
        }
        binaries.executable()
    }
    
    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                
                // Date/time handling
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                
                // Browser APIs for localStorage and console
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
    }
}
