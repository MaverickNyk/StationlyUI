# StationlyUI

## Overview
StationlyUI is the multi-platform frontend application for the Stationly ecosystem. It provides real-time London Underground departure information and status updates across Android and Web.

## Project Structure

```
StationlyUI/
├── core/                    # Kotlin Multiplatform shared module
│   ├── src/commonMain/      # Shared business logic, models, and repositories
│   └── build.gradle.kts     # KMP configuration
├── android/                 # Native Android application
│   ├── app/                 # Jetpack Compose application source
│   └── build.gradle.kts     # Android-specific build configuration
├── web/                     # Web application (Kotlin/JS)
│   ├── src/                 # Web application source
│   └── build.gradle.kts     # Web-specific build configuration
├── sdui-backend/            # Auxiliary Node.js service for SDUI templates
└── build.gradle.kts         # Root project build configuration
```

## Key Technologies
- **Mobile**: Android (Kotlin, Jetpack Compose, Material 3)
- **Web**: Kotlin/JS (Compose HTML/Vite)
- **Shared Logic**: Kotlin Multiplatform (KMP)
- **Real-time**: Firebase Cloud Messaging (FCM)
- **Database**: SQLDelight (Shared cross-platform storage)
- **Networking**: Ktor Client

## Architecture

### Kotlin Multiplatform Core
The `core` module contains all the business logic, data models, and repository implementations shared between Android and Web.
- **Models**: Unified data structures for stations, departures, and user settings.
- **Repositories**: Data access layers for API and Local Storage.
- **Processors**: Unified logic for sorting and grouping departure data (e.g., `GlobalBoardProcessor`).

### Real-time Updates
Stationly uses Firebase Cloud Messaging (FCM) to push live departure updates directly to the Android app and widget.
- **FCM Service**: Handles background prediction updates and triggers widget refreshes.
- **SDUI**: Server-Driven UI allows for dynamic layout updates without app releases.

## Getting Started

### Prerequisites
- Android Studio (Ladybug or newer)
- JDK 17+
- Node.js (for SDUI backend experiments)

### Setup
1. Clone the repository.
2. Open the project in Android Studio.
3. Sync Gradle projects.
4. Run the `:android:app` module on a device or emulator.

## Development Workflow

### Shared Code Updates
All shared logic should be added to the `core` module. Use `./gradlew build` to verify cross-platform compatibility.

### Building
- **Android Debug**: `./gradlew installDebug`
- **Web Build**: `./gradlew :web:jsBrowserProductionLibraryDistribution`

## License
Proprietary - Stationly Ltd.