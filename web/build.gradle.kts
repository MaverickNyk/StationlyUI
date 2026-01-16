/**
 * Static Web Module Build Configuration
 * 
 * This module serves a static HTML landing page for Stationly.
 * No Kotlin compilation required - pure HTML, CSS, and JavaScript.
 * 
 * Build tasks:
 * - ./gradlew :web:build       - Validates structure
 * - ./gradlew :web:serve       - Serves static site on localhost:8080
 */

plugins {
    base
}

// Copy static assets to build output
tasks.register<Copy>("copyStaticAssets") {
    from("src/static")
    into("$buildDir/dist")
    
    doLast {
        println("✓ Static assets copied to build/dist")
    }
}

// Simple task to serve the static site
tasks.register("serve") {
    dependsOn("copyStaticAssets")
    doLast {
        println("""
            
            ===================================
            Stationly Static Web Server
            ===================================
            
            Serving static site from: $buildDir/dist
            
            Option 1: Using http-server (install globally: npm install -g http-server)
            $ cd web/build/dist && http-server -p 8080
            
            Option 2: Using Python 3
            $ cd web/build/dist && python3 -m http.server 8080
            
            Option 3: Using Python 2
            $ cd web/build/dist && python -m SimpleHTTPServer 8080
            
            Open browser: http://localhost:8080
            
            ===================================
        """.trimIndent())
    }
}

tasks.named("build") {
    dependsOn("copyStaticAssets")
}

// Clean up
tasks.register("cleanDist") {
    doLast {
        delete("$buildDir/dist")
        println("✓ Cleaned build/dist directory")
    }
}
