# Performance & SDUI Caching (Android)

_Client first-paint / caching strategy. Introduced incrementally on branch `dev_25Apr`._

Stationly is **SDUI-driven**: almost every screen's content is a backend payload
(layout, config, theme, dropdown data). The performance strategy follows from that:

> **Every SDUI payload is cached stale-while-revalidate (SWR): paint the last-known
> copy instantly, then ALWAYS re-fetch from the backend and replace.**

This makes screen transitions instant **without** sacrificing "configure on the go" —
the backend remains the source of truth on every open, so any server-side change
(copy, layout, enable/disable a mode, A/B, theme) lands within ~1s of the next open.

> ⚠️ This is **SWR**, not a TTL cache. We never serve stale content *without* also
> refreshing. A TTL-only cache (serve stale until expiry) would hide backend changes —
> do **not** introduce one for SDUI payloads.

---

## The `SduiCache` helper

`com.stationly.mobile.util.SduiCache` is the generic SWR cache for SDUI payloads
(SharedPreferences-backed, namespaced `sdui_cache_<key>`):

```kotlin
// 1. instant first paint
SduiCache.read<SduiAppScreen>(context, "auth_layout_login")?.let { paint(it) }

// 2. always refresh + replace + persist
viewModelScope.launch {
    try {
        val fresh = apiService.getLoginLayout()
        paint(fresh)
        SduiCache.write(context, "auth_layout_login", fresh)
    } catch (_: Exception) { /* keep the cached copy painted */ }
}
```

Rules when adding a new SWR-cached payload:
- Show the cached copy first; only show a full-screen loader when there's **nothing**
  cached (`cached == null`).
- The refresh **always** runs and **replaces** the painted content wholesale (so
  removed/changed items propagate).
- On refresh failure, keep whatever is painted (cache or hardcoded fallback) — don't
  surface an offline error if we already showed something.

---

## What's cached today (instant first paint)

| Payload | Where | Mechanism |
|---|---|---|
| Selection layout | Selection | `loadCachedLayout` (`cached_app_layout`) |
| `/modes` | Selection | `loadCachedModes` (`cached_modes`) — SWR |
| Cascading dropdowns | Selection | `fetchDropdownData` (`cached_dropdown_*`, 24h then refresh) |
| Theme tokens | App-wide | `ThemeRepository` (cached overrides + refresh) |
| **Auth layouts** (login/register/forgot) | Auth | **`SduiCache`** (`auth_layout_*`) |
| **About layout** | Profile | **`SduiCache`** (`about_layout`) + hardcoded fallback as deepest layer |
| **Home config strings** | Home + Profile | **`HomeConfigStore`** seed → refresh (same store the widget/dream/board read) |

Live operational data (predictions, line status, selections) is **SQLDelight-backed**
and read locally (fast), refreshed via REST on setup and live via FCM — that's the
single-source-of-truth/reactive layer; SDUI *layouts* use the SWR cache above.

## Intentionally NOT cached

- **Home announcement** (`getHomeAnnouncement`) — time-sensitive, and it does **not**
  gate first paint (the board renders immediately; the banner slots in when it loads).
  Caching it would risk briefly flashing a just-pulled announcement. Left as a direct
  fetch.
- **Nearby stations / station search** — location/query-dependent, not cacheable.
- **Mutations** (`resolveStation`, `syncStations`, `syncProfile`, station add/remove) —
  per-action, not first-paint.

---

## Remaining opportunities (future)

- **Mode-icon double-download:** on first open each mode icon is fetched twice — once by
  Coil for the picker's `AsyncImage(iconUrl)`, once by `ModeIconCache.sync` for the
  widget/dream. Point the picker at `ModeIconCache.cachedFile(ctx, mode) ?: iconUrl` to
  unify (single download, instant + offline). Coil's default disk cache already makes
  *repeat* loads fast.
- **Home icon-warmup** (`SummaryViewModel`) re-fetches `/modes` uncached; could reuse
  `cached_modes`.
- **Cross-platform parity:** `SduiCache` is Android-side (consistent with the existing
  `cached_*` idiom). If iOS/web need the same instant-paint behaviour, promote it into
  `core/commonMain` behind `StorageManager`.
- **Main-thread SharedPrefs:** the cached reads run on the main thread during VM init
  (tiny). Could move off-thread if a cold-start trace shows it matters.

---

## Principles to preserve

1. **Backend is the source of truth on every open** — SWR refresh is non-negotiable.
2. **Reuse the existing cache idiom** (`SduiCache` / `cached_*` SharedPrefs /
   `HomeConfigStore` / `ThemeRepository`) — don't invent a new mechanism per screen.
3. **SQLDelight reactive SSOT for live data**; SWR SharedPrefs for SDUI layouts/config.
   Keep that split.
