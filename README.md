# StationlyUI - Hybrid Multi-Platform Station Departure App

## Overview
StationlyUI is the next-generation multi-platform solution for the Stationly ecosystem, providing real-time London Underground departure information across Android, iOS, and Web platforms.

## Project Structure

```
StationlyUI/
├── core/                    # Kotlin Multiplatform shared module
│   ├── src/commonMain/     # Shared Kotlin code
│   │   ├── kotlin/         # Business logic, models, API
│   │   └── resources/      # Common resources
│   └── build.gradle.kts    # KMP configuration
├── android/                 # Android native app
│   ├── app/                # Android application module
│   └── build.gradle.kts    # Android configuration
├── ios/                     # iOS native app
│   └── Stationly/          # iOS project
├── web/                     # Web application
│   ├── app/                # Next.js PWA
│   └── landing/            # Marketing site
└── build.gradle.kts        # Root build config
```

## Platforms

### Android
- **Technology**: Kotlin + Jetpack Compose
- **Features**: Native widget, FCM notifications
- **Store**: Google Play Store

### iOS
- **Technology**: Swift + SwiftUI
- **Features**: APNs notifications, Siri shortcuts
- **Store**: Apple App Store

### Web (PWA)
- **Technology**: Next.js 14+ + TypeScript
- **Features**: WebSocket real-time, offline support
- **Deployment**: Vercel/Netlify

### Landing Page
- **Technology**: Next.js + Tailwind CSS
- **Purpose**: Marketing, downloads, information
- **Deployment**: Vercel/Netlify

## Architecture

### Kotlin Multiplatform Core
- **Models**: Shared data classes (UserSelection, LineStatus, etc.)
- **API**: Retrofit interfaces for backend communication
- **Business Logic**: Use cases for selection flow, real-time updates
- **Platform Abstraction**: Interfaces for platform-specific features

### Real-time Strategy
- **Mobile**: Firebase Cloud Messaging (Android) + APNs (iOS)
- **Web**: WebSocket connections
- **Backend**: Node.js + Redis for message routing

### Backend API
- **Base URL**: https://api.stationly.com/
- **Endpoints**: Modes, lines, stations, real-time, user sync

## Getting Started

### Prerequisites
- Android Studio (latest version)
- Xcode (for iOS development)
- Node.js 18+ (for web development)
- JDK 17+

### Setup
1. Clone this repository
2. Run `./gradlew build` in core/ to build shared module
3. Open android/ in Android Studio
4. Open ios/ in Xcode
5. Run `npm install` in web/app and web/landing

## Development Workflow

### Adding Shared Code
1. Add models to `core/src/commonMain/kotlin/model/`
2. Add business logic to `core/src/commonMain/kotlin/usecase/`
3. Implement platform-specific code in respective folders

### Testing
- **Unit Tests**: core/src/commonTest/
- **Android Tests**: android/app/src/test/
- **iOS Tests**: ios/StationlyTests/
- **Web Tests**: web/app/tests/

## Deployment

### Android
```bash
cd android
./gradlew assembleRelease
# Upload to Google Play Console
```

### iOS
```bash
cd ios
# Archive in Xcode
# Upload to App Store Connect
```

### Web (Landing Page)
```bash
# Build
./gradlew :web:build

# Deploy to Oracle Server
./deploy.sh

# Accessible at https://stationly.co.uk
```

For detailed deployment instructions, see [DEPLOYMENT.md](DEPLOYMENT.md)

## Team
- **Lead Architect**: [Your Name]
- **Mobile Team**: Android + iOS developers
- **Web Team**: Frontend developers
- **Backend Team**: API and infrastructure

## License
Proprietary - Stationly Ltd.