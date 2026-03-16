# StationlyUI Copilot Instructions

## Project Overview
StationlyUI is a **Kotlin Multiplatform (KMP)** widget-heavy app for London Underground departure information. It shares business logic via a `core` module across Android, iOS, and Web (WASM) platforms, with platform-specific UI implementations (Jetpack Compose for Android, SwiftUI for iOS, Next.js for Web).

**Key architectural constraint**: Widget-driven data flow requires careful state management—real-time FCM/APNs notifications update cached selections stored locally.

---

## Architecture Patterns

### 1. **Clean Architecture with Use Cases**
- **Location**: `core/src/commonMain/kotlin/usecase/`
- **Pattern**: Single responsibility—each use case wraps one API call or business operation
  - `GetModesUseCase`, `GetLinesUseCase`, `GetRouteUseCase`, `SearchStationsUseCase`
  - `SaveSelectionUseCase`, `ProcessFcmPayloadUseCase`, `FormatDeparturesUseCase`
- **Usage**: Inject via constructor; call with `suspend operator fun invoke()` signature
- **Example**: See [SelectionViewModel.kt](../../web/src/wasmJsMain/kotlin/com/stationly/mobile/ui/selection/SelectionViewModel.kt#L60-L75) for use case instantiation pattern

### 2. **Kotlin Multiplatform (KMP) Structure**
```
core/src/
├── commonMain/     # Shared models, APIs, use cases (all platforms)
├── androidMain/    # Android-specific (StorageManager, WidgetManager)
├── wasmJsMain/     # Web WASM (WebStorageManager, WebNotificationManager)
```
- **Rule**: Platform-specific code goes in `androidMain` or `wasmJsMain`; shared code in `commonMain`
- **Expect/Actual pattern**: Use `expect class/fun` in `commonMain`, implement with `actual` in platform directories
- **Example**: [WebStorageManager](../../web/src/wasmJsMain/kotlin/) vs Android's StorageManager

### 3. **State Management via StateFlow**
- **Pattern**: MutableStateFlow for UI state in ViewModels (see [SelectionViewModel.kt](../../web/src/wasmJsMain/kotlin/com/stationly/mobile/ui/selection/SelectionViewModel.kt#L53-L60))
- **UI State Data Class**: Immutable state with sensible defaults (see `SelectionUiState` in SelectionViewModel)
- **Updates**: Always use `.copy()` for immutability—never mutate state directly
- **Coroutine Scope**: ViewModels create their own scope (e.g., `CoroutineScope(Dispatchers.Main)`)

### 4. **Repository Pattern for Data Access**
- **Location**: `core/src/commonMain/kotlin/repository/`
- **Purpose**: Abstracts data sources (API, local storage) behind a single interface
- **Example**: `SelectionRepository` manages saved user selections via `StorageManager`
- **Key methods**: `.selections` StateFlow for reactive updates

### 5. **Platform Manager Pattern**
- **Storage**: `StorageManager` (expect/actual)—abstract local storage across platforms
- **Notifications**: `NotificationManager` (expect/actual)—FCM (Android) vs APNs (iOS) vs WebPush (Web)
- **Widgets**: `WidgetManager` (expect/actual)—platform-specific widget updates
- **Usage**: Injected into repositories and use cases; implement in `androidMain`/`wasmJsMain`

---

## Key Data Models

All defined in [Models.kt](../../core/src/commonMain/kotlin/model/Models.kt):

- **UserSelection**: Core saved config (mode, line, station, direction, destinations)
- **TransportMode**: "Tube", "DLR", etc.
- **LineInfo**: Line metadata (id, name, modeName)
- **StationBrief**: Station with naptanId, commonName, associated lines
- **LineRouteResponse**: Directions and destinations for a line
- **LineStatus**: Service status/disruptions

**Serialization**: All models use `@Serializable` from `kotlinx.serialization` for API/storage compatibility.

---

## Critical Data Flows

### **Selection Flow** (User selects mode → line → direction → station → saves)
1. User picks **Mode** → `GetModesUseCase` fetches modes, `onModeSelected()` triggers `loadLines()`
2. User picks **Line** → `GetLinesUseCase` fetches lines, `onLineSelected()` triggers `loadDirections()`
3. User picks **Direction** → `GetRouteUseCase` fetches route, `onDirectionSelected()` triggers `loadStations()`
4. User picks **Station** → `onStationSelected()` caches selection
5. User saves → `SaveSelectionUseCase` stores and notifies (FCM subscribe for Android)

### **Real-time Updates** (FCM/APNs push to widget)
- Incoming notification calls `ProcessFcmPayloadUseCase`
- Updates cached predictions in `DepartureRepository`
- Platform manager (e.g., `WebWidgetManager`) refreshes UI
- Widget re-renders with fresh departure times

---

## Build & Development

### **Gradle Tasks**
```bash
# Build all platforms
./gradlew build

# Build specific modules
./gradlew :core:build              # Shared module only
./gradlew :android:app:build       # Android
./gradlew :web:build               # Web WASM

# Watch mode (web)
./gradlew :web:wasmJsBrowserDevelopmentRun -t

# Clean before build if cache issues occur
./gradlew clean build
```

### **Android Build**
- Targets: API 28+ (compileSdk 34)
- Language: Kotlin 2.0.0
- Compose: Jetpack Compose for UI

### **Web Build**
- Target: WASM (experimental, requires Kotlin 2.0+)
- Output: Webpack bundled to `build/js/`
- Development server: Built-in webpack dev server
- **Note**: Web module depends on `:core` KMP library

### **Debugging Web WASM**
```kotlin
@JsName("console")
external object console {
    fun log(message: String)
    fun error(message: String)
}
// Use: console.log("Debug message")
```

---

## Naming & Code Conventions

1. **File Organization**: Mirror package structure (e.g., `service/TflApiService.kt` → `package com.stationly.core.service`)
2. **Use Case Naming**: `<Verb><Noun>UseCase` (e.g., `GetModesUseCase`, `SaveSelectionUseCase`)
3. **Repository Naming**: `<Entity>Repository` (e.g., `SelectionRepository`)
4. **Platform Managers**: `<Platform><Feature>Manager` (e.g., `WebStorageManager`, `WebNotificationManager`)
5. **Data Classes**: Use `@Serializable` for API models; add helper methods for null-safety
6. **StateFlow naming**: `_private` for MutableStateFlow, expose as public `StateFlow<T>` via `.asStateFlow()`
7. **Error Handling**: Wrap async calls in try-catch; update UI state with error messages

---

## Common Pitfalls & Solutions

| Issue | Cause | Solution |
|-------|-------|----------|
| "Unresolved reference" to core models | Android module not depending on `:core` | Check `android/app/build.gradle.kts` has `implementation(project(":core"))` |
| Web WASM compile fails | Expect/actual mismatch | Ensure `wasmJsMain` has actual implementations for all expect declarations |
| Widget not updating after save | FCM not subscribed | Verify `SaveSelectionUseCase` calls `NotificationManager.subscribe()` |
| StateFlow updates not reflecting | Mutating state instead of copying | Always use `.copy()` when updating MutableStateFlow values |
| Coroutine leak | Scope not cancelled | ViewModels should cancel scope in destructor (platform-specific) |

---

## Where to Add Code

| Task | Location | Example |
|------|----------|---------|
| New API endpoint | `core/src/commonMain/kotlin/service/TflApiService.kt` (interface) | Add method; implement in platform-specific factory |
| New data model | `core/src/commonMain/kotlin/model/Models.kt` | Add `@Serializable data class` |
| New business logic | `core/src/commonMain/kotlin/usecase/<Feature>UseCase.kt` | Create suspend operator fun invoke |
| Platform storage | `core/src/androidMain/kotlin/platform/StorageManager.kt` (or wasmJsMain) | Implement StorageManager interface |
| UI feature | `web/src/wasmJsMain/kotlin/com/stationly/mobile/ui/` | Add Composable and ViewModel |

---

## Quick Reference: Key Files

- **Models**: [Models.kt](../../core/src/commonMain/kotlin/model/Models.kt)
- **Use Cases**: [usecase/](../../core/src/commonMain/kotlin/usecase/)
- **Web ViewModel Example**: [SelectionViewModel.kt](../../web/src/wasmJsMain/kotlin/com/stationly/mobile/ui/selection/SelectionViewModel.kt)
- **Architecture Plan**: [kmp-architecture-plan.md](../../kmp-architecture-plan.md) (detailed design rationale)
- **Web Build Config**: [web/build.gradle.kts](../../web/build.gradle.kts)
- **Root Config**: [settings.gradle.kts](../../settings.gradle.kts) (module inclusions)

---

## Questions?
Refer to README.md for platform-specific setup, or check the kmp-architecture-plan.md for deep architectural decisions.
