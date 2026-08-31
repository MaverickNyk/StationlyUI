# SDUI Phase 2 & Operational Thresholds Handover

**Date:** 2026-08-31  
**Status:** Code-complete, tested, staging-deployed, verified  
**Branches:** `StationlyUI` on `ios-parity`, `stationly-backend` on `dev_13Jul`

---

## 1. Executive Summary

This session completed **SDUI Phase 2 (TfL Line & Mode Palette Consolidation)** and wired the **Six Tunable Operational Thresholds** across client and backend.

### What Was Consolidated
Prior to this work, TfL line and mode colours were duplicated across four independent locations without a single source of truth:
1. `core/util/TflLineColors.kt` (Android notification chip)
2. `composeApp/.../Board.kt` (`TFL_LINE_COLORS`, `TFL_LINE_COLORS_DARK`, `TFL_LINE_COLORS_LIGHT`, and `modeRoundelColor`)
3. `iosApp/StationlyWidget/WidgetTheme.swift` (`modeColor` switch)
4. `stationly-backend/src/services/lineIconService.ts` (Backend icon rendering)

### Core Architectural Decisions
- **Base + Overrides Model:** TfL brand colours (e.g. `northern` = `#000000`) are maintained as official brand values, while per-theme legibility overrides (e.g. `northern` dark = `#888888`, light = `#6E6A66`) ensure contrast on dark/light boards without breaking notification chips.
- **Single Config Adoption Point:** `SduiConfig.refresh(strings)` in `:core` is the single entry point for incoming SDUI home-config payloads, updating both `BoardPolicyStore` and `LinePaletteStore` simultaneously.
- **Dynamic Operational Thresholds:** Six hardcoded constants were converted to SDUI-tunable, clamped parameters in `BoardPolicy`.

---

## 2. Inventory of Changes

### A. Palette Keys (42 keys)
- `line.color.<id>` (21 brand line colors)
- `line.color.dark.<id>` (9 dark theme overrides)
- `line.color.light.<id>` (3 light theme overrides)
- `mode.color.<id>` (8 transport mode roundel tints)
- `mode.color.default` (1 mode fallback tint `#DC241F`)

### B. Operational Threshold Keys (6 keys)
| Key | Default | Clamp Range | Consumers |
|---|---|---|---|
| `board.hero.urgency_min` | `1` min | `0..10` min | `Board.kt` (amber hero border), `DreamSummary.kt` (amber countdown pulse) |
| `selection.dropdown.cache_ttl_ms` | `86400000` (24h) | `3600000..604800000` (1h..7d) | `SelectionViewModel.kt` (deduped), `StationSettingsViewModel.kt` |
| `station.route_text.max_age_ms` | `1209600000` (14d) | `86400000..7776000000` (1d..90d) | `StationSettingsViewModel.kt` (route destination refresh check) |
| `support.fetch.min_interval_ms` | `60000` (60s) | `5000..600000` (5s..10m) | `SupportViewModel.kt` (non-forced supporter status poll interval) |
| `explore.fares.max_days_to_peak` | `14` days | `1..60` days | `ExploreSection.kt` (peak fare window search horizon) |
| `weather.refresh_interval_ms` | `1800000` (30m) | `300000..7200000` (5m..2h) | `WeatherStation.kt` (screensaver weather polling interval) |

---

## 3. Production Android Non-Regression Audit

> [!IMPORTANT]
> **Android Production Freeze:** Android `versionCode 2` is live in production. It depends on `:core` and does not receive forced updates. All changes have been audited and verified to guarantee 100% backward compatibility.

1. **`TflLineColors.hexFor(lineId)` Contract Maintained:**
   - Android's `FcmMessagingService` calls `TflLineColors.hexFor(lineId)` to tint push notification chips.
   - `TflLineColors` delegates to `LinePaletteStore.current.hexFor(lineId)`.
   - On Android, `LinePaletteStore.current` defaults to `LinePalette.DEFAULT`, which resolves brand colours (e.g. `northern` = `#000000`).
   - Bit-identical behaviour to pre-P2 codebase.
2. **Backend Payload Additivity:**
   - Backend `getHomeConfig()` is purely additive (+48 keys). No existing keys were deleted or altered.
3. **Android Core Compilation:**
   - `./gradlew :core:assembleDebug` succeeds with 0 errors and generates the debug AAR.

---

## 4. Verification & Build Matrix

| Check / Build Target | Result | Notes |
|---|---|---|
| `:core:testDebugUnitTest` | **PASSED** | 315 tests passing, including `LinePaletteTest` & `BoardPolicyTest` |
| `:core:assembleDebug` | **BUILD SUCCESSFUL** | Validates Android `:core` compilation |
| `:composeApp:assembleComposeAppDebugXCFramework` | **BUILD SUCCESSFUL** | Debug XCFramework for iOS |
| `xcodebuild -scheme "iosApp Staging"` | **BUILD SUCCEEDED** | iOS app & StationlyWidget compilation clean |
| Backend `npm test` | **182/182 PASSED** | Includes linePaletteService contract tests |
| Backend `tsc --noEmit` | **CLEAN** | 0 TypeScript errors |
| `python3 scripts/sdui_keys.py --check` | **PASSED** | 48 keys added, 0 removed (295 total keys) |
| Staging Deployments | **LIVE** | Backend deployed via `.scripts/staging_deploy.sh`; iOS deployed via `ios-dev.sh` |

---

## 5. Visual Verification Points

1. **Northern Line Rendering:**
   - Dark theme board: `#888888` (pill & accents)
   - Light theme board: `#6E6A66`
   - Android notification chip: `#000000`
2. **Cable Car Rendering:**
   - Board renders official brand red `#E21836` (intentional fix; was previously falling through to amber).
3. **Roundel Consistency:**
   - In-app station strip roundel and home screen widget roundel match identical mode tints via `LinePalette` and `WidgetFallbackTable.modeColors`.
