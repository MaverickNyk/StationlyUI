# StationlyUI KMP Architecture Plan for Widget-Heavy App

## Executive Summary
This document outlines the Kotlin Multiplatform architecture for StationlyUI, specifically designed to handle the widget-heavy nature of the London Underground departure board application. The architecture prioritizes shared business logic while accommodating platform-specific widget implementations.

## Current MindTheTimeAndroid Analysis

### Widget Architecture
```kotlin
// Current Android Widget Implementation
DepartureWidgetProvider
├── updateAppWidget()           // Renders widget UI
├── updateWidgetContent()       // Updates with real-time data
├── showWaitingState()          // Shows loading state
└── formatDepartures()          // Formats prediction data
```

### Key Widget Features
1. **Real-time Departure Board**: Shows live train predictions
2. **Platform Headers**: Groups by platform
3. **ETA Formatting**: "Due", "X min" calculations
4. **Line Status**: Service disruption information
5. **Auto-refresh**: Updates via FCM push
6. **Click-to-open**: Launches main app

### Data Flow
```
FCM Push → WidgetProvider → RemoteViews → Home Screen
     ↓
SharedPreferences (cached data)
     ↓
User Selections (saved stations)
```

## KMP Architecture Design

### 1. Core Module Structure

```
core/
├── src/commonMain/kotlin/
│   ├── model/
│   │   ├── TransportMode.kt
│   │   ├── LineInfo.kt
│   │   ├── StationBrief.kt
│   │   ├── UserSelection.kt          // Saved station configs
│   │   ├── LineStatus.kt
│   │   ├── FcmPayload.kt             // Real-time predictions
│   │   └── PredictionItem.kt
│   ├── repository/
│   │   ├── SelectionRepository.kt    // User preferences
│   │   └── DepartureRepository.kt    // Real-time data
│   ├── usecase/
│   │   ├── GetModesUseCase.kt
│   │   ├── GetLinesUseCase.kt
│   │   ├── GetRouteUseCase.kt
│   │   ├── SearchStationsUseCase.kt
│   │   ├── SaveSelectionUseCase.kt
│   │   ├── GetSelectionsUseCase.kt
│   │   ├── SubscribeToTopicsUseCase.kt
│   │   ├── UnsubscribeFromTopicsUseCase.kt
│   │   ├── ProcessFcmPayloadUseCase.kt
│   │   └── FormatDeparturesUseCase.kt
│   ├── service/
│   │   ├── TflApiService.kt (interface)
│   │   └── RealTimeService.kt (interface)
│   ├── platform/
│   │   ├── Platform.kt              // Expect/actual for platform features
│   │   ├── WidgetManager.kt         // Widget-specific operations
│   │   ├── NotificationManager.kt   // Push notification handling
│   │   └── StorageManager.kt        // Local storage abstraction
│   └── util/
│       ├── DateFormatters.kt
│       ├── StringFormatters.kt
│       └── WidgetDataMapper.kt      // Maps API data to widget format
└── src/commonTest/kotlin/
    ├── repository/
    ├── usecase/
    └── util/
```

### 2. Platform-Specific Implementations

#### Android (Native Widget)
```
android/app/
├── src/main/kotlin/com/stationly/mobile/
│   ├── ui/
│   │   ├── SelectionScreen.kt       // Compose UI
│   │   ├── SummaryScreen.kt         // Compose UI
│   │   └── common/
│   │       ├── DirectionPicker.kt
│   │       └── SearchableDropdown.kt
│   ├── widget/
│   │   ├── DepartureWidgetProvider.kt    // AppWidgetProvider
│   │   ├── WidgetUpdateWorker.kt         // WorkManager for background updates
│   │   └── WidgetDataFormatter.kt        // Formats data for RemoteViews
│   ├── service/
│   │   ├── FcmMessagingService.kt        // Handles FCM messages
│   │   └── NetworkModule.kt              // Retrofit setup
│   ├── platform/
│   │   ├── AndroidWidgetManager.kt       // Actual WidgetManager
│   │   ├── AndroidNotificationManager.kt // Actual NotificationManager
│   │   └── AndroidStorageManager.kt      // SharedPreferences implementation
│   └── MainActivity.kt
├── src/main/res/
│   ├── xml/
│   │   └── departure_widget_info.xml     // Widget metadata
│   └── layout/
│       ├── widget_departure_board.xml    // Widget layout
│       ├── widget_departure_row.xml
│       └── widget_platform_header.xml
└── build.gradle.kts
```

**Key Android-Specific Features:**
- **AppWidgetProvider**: Native Android widget
- **WorkManager**: Background widget updates
- **RemoteViews**: Widget UI rendering
- **FCM**: Push notifications
- **SharedPreferences**: Local storage

#### iOS (WidgetKit + SwiftUI)
```
ios/Stationly/
├── Shared/
│   ├── Core/                    # KMP framework
│   └── Models/                  # Shared models
├── StationlyApp/
│   ├── App.swift
│   ├── Views/
│   │   ├── SelectionView.swift
│   │   ├── SummaryView.swift
│   │   └── Components/
│   ├── Services/
│   │   ├── APIService.swift     # Conforms to KMP interface
│   │   ├── RealTimeService.swift # WebSocket + APNs
│   │   └── StorageService.swift # UserDefaults + Keychain
│   └── Platform/
│       ├── iOSWidgetManager.swift
│       ├── iOSNotificationManager.swift
│       └── iOSStorageManager.swift
├── StationlyWidget/
│   ├── StationlyWidget.swift
│   ├── WidgetEntry.swift
│   ├── WidgetView.swift
│   └── Provider.swift
└── StationlyTests/
```

**Key iOS-Specific Features:**
- **WidgetKit**: iOS home screen widgets
- **SwiftUI**: Native UI framework
- **APNs**: Apple Push Notifications
- **WebSocket**: Real-time updates for web clients
- **Keychain**: Secure storage

#### Web (PWA + Landing)
```
web/
├── app/                          # Next.js PWA
│   ├── src/
│   │   ├── app/
│   │   │   ├── page.tsx         # Dashboard
│   │   │   ├── selection/
│   │   │   └── api/
│   │   ├── components/
│   │   │   ├── DepartureBoard.tsx
│   │   │   ├── SelectionWizard.tsx
│   │   │   └── WidgetSimulator.tsx
│   │   ├── lib/
│   │   │   ├── kmp-bridge.ts    # KMP WASM bridge
│   │   │   ├── api-client.ts
│   │   │   └── websocket-client.ts
│   │   └── service/
│   │       ├── RealTimeService.ts
│   │       └── StorageService.ts
│   └── public/
│       └── manifest.json        # PWA manifest
├── landing/                      # Marketing site
│   ├── src/
│   │   ├── pages/
│   │   │   ├── index.tsx        # Hero + downloads
│   │   │   ├── features.tsx
│   │   │   └── how-it-works.tsx
│   │   └── components/
│   │       ├── DownloadButtons.tsx
│   │       └── FeatureShowcase.tsx
└── shared/                       # TypeScript types from KMP
```

**Key Web-Specific Features:**
- **Next.js 14+**: App Router + Server Components
- **WebSocket**: Native browser API for real-time
- **Service Workers**: PWA offline support
- **IndexedDB**: Client-side storage
- **WASM**: Potential KMP compilation target

### 3. Widget-Specific Challenges & Solutions

#### Challenge 1: Real-time Data Sync
**Problem**: Widgets need live data but can't maintain persistent connections
**Solution**:
```kotlin
// Core: ProcessFcmPayloadUseCase
class ProcessFcmPayloadUseCase(
    private val repository: DepartureRepository,
    private val widgetManager: WidgetManager
) {
    suspend operator fun invoke(payload: FcmPayload) {
        // 1. Parse predictions
        val predictions = payload.lines.flatMap { lineData ->
            lineData.dirs.flatMap { dirData ->
                dirData.preds.map { pred ->
                    PredictionItem(
                        destId = pred.destId,
                        displayName = pred.displayName,
                        platform = pred.platform,
                        eta = pred.eta
                    )
                }
            }
        }
        
        // 2. Cache locally
        repository.cachePredictions(predictions)
        
        // 3. Update widget (platform-specific)
        widgetManager.updateWidget(predictions)
    }
}
```

#### Challenge 2: Widget State Management
**Problem**: Widgets have limited state management capabilities
**Solution**:
```kotlin
// Core: WidgetDataMapper
object WidgetDataMapper {
    fun mapToWidgetState(
        selections: List<UserSelection>,
        predictions: List<Prediction>,
        lineStatus: LineStatus?
    ): WidgetState {
        return when {
            selections.isEmpty() -> WidgetState.NoSelection
            predictions.isEmpty() -> WidgetState.Loading
            else -> WidgetState.Active(
                stationName = selections.first().stationName,
                lineName = selections.first().line,
                predictions = predictions.take(3), // Top 3
                status = lineStatus?.statusSeverityDescription,
                lastUpdated = Clock.now()
            )
        }
    }
}

// Platform-specific rendering
expect class WidgetManager {
    fun updateWidget(state: WidgetState)
    fun showWaitingState(station: String, line: String)
}
```

#### Challenge 3: Background Updates
**Problem**: Widgets need updates even when app is closed
**Solution**:
- **Android**: WorkManager + FCM
- **iOS**: Background App Refresh + APNs
- **Web**: Service Worker + WebSocket reconnection

#### Challenge 4: Platform-Specific UI
**Problem**: Widget UI is completely different per platform
**Solution**:
```kotlin
// Core: Define widget data structure
data class WidgetData(
    val station: String,
    val line: String,
    val predictions: List<Prediction>,
    val status: String?,
    val timestamp: Long
)

// Platform: Render using native components
expect fun WidgetData.toRemoteViews(): RemoteViews  // Android
expect fun WidgetData.toWidgetEntry(): WidgetEntry  // iOS
expect fun WidgetData.toComponent(): ReactElement   // Web
```

### 4. Data Flow Architecture

#### Selection Flow (Shared)
```
User Input → UseCase → Repository → API → Model → State Update
```

#### Real-time Flow (Platform-Specific)
```
Backend → FCM/APNs/WebSocket → Platform Service → Core UseCase → Widget Update
```

#### Widget Update Flow
```
FCM Payload → ProcessFcmPayloadUseCase → Cache → WidgetManager → Platform Widget
```

### 5. API Evolution

#### Current API (StationlyBE)
```
GET /api/v1/modes
GET /api/v1/lines/mode/{mode}
GET /api/v1/stations/search?searchKey={key}
GET /api/v1/lines/{lineId}/route
GET /api/v1/lines/status?lineId={id}
```

#### Enhanced API (Stationly)
```
GET /v1/modes
GET /v1/lines/mode/{mode}
GET /v1/stations/search
GET /v1/lines/{lineId}/route
GET /v1/lines/status
POST /v1/realtime/subscribe (WebSocket)
GET /v1/realtime/predictions (WebSocket stream)
GET /v1/user/selections (CRUD)
POST /v1/user/devices (Push registration)
```

### 6. Implementation Roadmap

#### Phase 1: KMP Core (Weeks 1-4)
1. Set up KMP project structure
2. Migrate models to commonMain
3. Extract business logic to Use Cases
4. Create platform interfaces
5. Implement API service interfaces

#### Phase 2: Android Migration (Weeks 5-8)
1. Refactor Android app to use KMP core
2. Update widget provider to use KMP
3. Implement Android platform managers
4. Test widget updates with KMP data
5. Migrate FCM handling

#### Phase 3: iOS App (Weeks 9-12)
1. Set up iOS project with KMP framework
2. Implement SwiftUI UI
3. Create iOS platform managers
4. Implement WidgetKit widgets
5. Add APNs + WebSocket support

#### Phase 4: Web App (Weeks 13-16)
1. Set up Next.js project
2. Implement PWA features
3. Create WebSocket client
4. Build selection wizard UI
5. Implement widget simulator

#### Phase 5: Landing Page (Weeks 17-18)
1. Design landing page
2. Implement with Next.js
3. Add analytics and tracking
4. SEO optimization

#### Phase 6: Backend Enhancement (Weeks 19-20)
1. WebSocket server implementation
2. User account system
3. Device management
4. Cross-platform sync

### 7. Widget-Specific Testing Strategy

#### Unit Tests
```kotlin
// Core: Test Use Cases
class ProcessFcmPayloadUseCaseTest {
    @Test
    fun `should parse FCM payload correctly`() = runTest {
        // Test parsing logic
    }
    
    @Test
    fun `should handle empty predictions`() = runTest {
        // Test edge cases
    }
}
```

#### Platform Tests
```kotlin
// Android: Test WidgetProvider
class DepartureWidgetProviderTest {
    @Test
    fun `should update RemoteViews correctly`() {
        // Test widget rendering
    }
}

// iOS: Test WidgetKit
class StationlyWidgetTests {
    func testWidgetEntry() {
        // Test widget timeline
    }
}
```

#### Integration Tests
```kotlin
// End-to-end: FCM → Widget Update
class WidgetUpdateIntegrationTest {
    @Test
    fun `should update widget on FCM message`() = runTest {
        // Test full flow
    }
}
```

### 8. Key Decisions

#### Why KMP for Widget-Heavy App?
1. **Shared Business Logic**: Selection flow, data processing, formatting
2. **Consistent Data Models**: Same UserSelection, Prediction, LineStatus across platforms
3. **API Consistency**: Same Retrofit/HTTP interfaces
4. **Testability**: Common test logic for core functionality
5. **Maintainability**: Single source of truth for business rules

#### What Stays Platform-Specific?
1. **Widget UI**: RemoteViews (Android), WidgetKit (iOS), Web Components
2. **Push Notifications**: FCM (Android), APNs (iOS), WebSocket (Web)
3. **Background Tasks**: WorkManager (Android), Background Refresh (iOS), Service Workers (Web)
4. **Storage**: SharedPreferences (Android), UserDefaults (iOS), IndexedDB (Web)
5. **Native Features**: App shortcuts, Siri, etc.

#### Real-time Strategy
- **Mobile**: FCM/APNs for push (battery efficient)
- **Web**: WebSocket for real-time (browser-friendly)
- **Core**: Unified processing in KMP

This architecture ensures that 70-80% of the codebase is shared while accommodating the widget-heavy nature of the application with platform-specific optimizations.


