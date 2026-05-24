# Theming — Agent context

This folder owns every theme-aware colour in the Stationly Android app
(the dot-matrix departure board is the one exception — it stays locked
to its dark TfL-signage palette regardless of the user's chosen theme).
The design goal: any palette change can be shipped via the backend in
under a minute, with no APK rebuild and no user action.

## Files

```
ui/theme/
├── ThemeTokens.kt          Source-of-truth data class. 21 named colour slots
│                           per theme plus 2 cross-theme constants.
│                           DefaultLightTokens + DefaultDarkTokens are baked
│                           into the binary as ground-truth fallbacks.
├── ThemeRepository.kt      Sync read of SharedPrefs cache + async background
│                           fetch from /sdui/app/theme-tokens. Merges the
│                           cache onto the defaults; never blocks startup.
├── Theme.kt                StationlyThemeHost composable. Reads cached
│                           tokens, fires background refresh, exposes
│                           [LocalThemeTokens] and projects the M3-flavoured
│                           subset to MaterialTheme.colorScheme.
├── Type.kt                 Typography. DisplayFamily (Inter Tight via
│                           Google Downloadable Fonts) for wordmarks /
│                           headings; BodyFamily defaults to system sans.
├── AppTheme.kt             User's Light / Dark / System preference; lives
│                           in SharedPrefs (StationlyPrefs). Default = SYSTEM.
├── Color.kt                Legacy TflAmber constant. Anything new goes via
│                           ThemeTokens, NOT here.
└── CLAUDE.md               This file.
```

## Architecture overview

Three layers, evaluated in order on every cold launch:

```
   1. Hardcoded defaults           ── compiled into the APK, always present
              ⊕
   2. SharedPrefs cache (overlay)  ── last successful SDUI sync
              ⊕
   3. Background SDUI refresh      ── updates the cache; takes effect
                                      on the NEXT cold launch (never
                                      mid-session — colour flips on a
                                      running screen are jarring)
```

`ThemeRepository.loadCachedOverrides()` reads the SharedPrefs blob
synchronously on launch and merges it onto the defaults via
`ThemeTokens.merge(overrideMap)`. The merged tokens get provided through
`LocalThemeTokens` and also projected onto `MaterialTheme.colorScheme`
via `ThemeTokens.toColorScheme(darkTheme)`.

`ThemeRepository.refreshInBackground()` fires a fire-and-forget Ktor
call. Its only side effect is writing the latest payload to SharedPrefs;
the running session keeps using whatever was loaded at startup.

## Where to read a colour from

In order of preference:

1. **`MaterialTheme.colorScheme.*`** — anything that maps to a standard
   Material slot (primary, surface, onBackground, error). 80%+ of the
   app reads from here. This is the easiest to refactor later.

2. **`LocalThemeTokens.current.*`** — semantic tokens that don't have
   an M3 slot but need to flow through SDUI. The big ones:
   - `live` — green pulse for live-data indicators.
   - `due` — bright red for "train arriving now".
   - `error` — disruption banners, danger destructive actions.
   - `roundelRed`, `brandSignage` — theme-independent brand marks.

3. **`LocalDreamColors.current.*`** — only inside `dream/`. The dream
   has its own narrow colour set (`canvas`, `onCanvas`, `brandAccent`,
   `danger`, `live`) because the dream is a separate composition tree
   and may pick a different theme than the rest of the app (user can
   set dream = Light while app = Dark).

4. **Hardcoded `Color(0x...)`** — the exception, not the rule. Used
   only for:
   - The dot-matrix board (`#0C0C0C` panel, amber-on-dark by design).
   - The TfL line palette (`TFL_LINE_COLORS`) — official line colours
     that must stay constant for line identity.
   - The Google Sign-In button background (Google brand requirement).
   - The TfL roundel inner field (always white in selection screen).

## TfL line colours: theme-aware overrides

`Board.kt` defines three maps:

| Map                          | Purpose                                                          |
|------------------------------|------------------------------------------------------------------|
| `TFL_LINE_COLORS`            | Canonical TfL palette. Single source of truth for line identity. |
| `TFL_LINE_COLORS_DARK`       | Brightened variants for lines that vanish on the near-black canvas (Piccadilly navy, Suffragette green, Metropolitan magenta, etc.). |
| `TFL_LINE_COLORS_LIGHT`      | Warmed-up variants for the grey lines (Northern, Jubilee, Liberty) so they don't wash into the cream canvas. |

Always pick the line colour via `lineColorForTheme(line, isDark)` —
never index `TFL_LINE_COLORS` directly. In Compose code that runs
inside the app theme, derive `isDark` from
`MaterialTheme.colorScheme.background.luminance() < 0.5f`; in dream code,
read `LocalDreamColors.current === DarkDreamColors`.

## Adding a new token

1. Add the field to `ThemeTokens` data class (Kotlin) AND to
   `ThemeBucket` (TypeScript backend).
2. Add a default value to both `DefaultLightTokens` / `DefaultDarkTokens`
   AND to the backend `ThemeService.getAppThemeTokens()`. The two MUST
   stay in lockstep — if you ship the Kotlin change without the backend
   change, the cache load will fall through to defaults silently.
3. If the new token maps to a Material slot, wire it through
   `Theme.kt`'s `toColorScheme()` projector.
4. Update `ThemeRepository.merge()` to parse the new key from the
   SharedPrefs cache map.
5. Bump `version` in the backend payload so caches that don't have the
   new key get a fresh fetch.
6. Search-and-replace the hardcoded uses across the app.

## Typography (Type.kt)

We use Google Downloadable Fonts so we don't ship TTFs. The provider
certificate chain lives at `res/values/font_certs.xml` (the canonical
GMS certs — do not modify).

- **DisplayFamily** = Inter Tight, applied to the Stationly wordmark
  and section headlines.
- **BodyFamily** = system sans, picks up Pixel-perfect rendering and
  respects the user's font scale.

If GMS Fonts is unavailable (no Play Services, blocked network), Compose
falls back to `FontFamily.SansSerif` automatically — the app never
crashes and text remains readable.

## Things to NOT do

- **Don't add a third colour layer** (e.g. per-screen overrides on top of
  tokens). The cascade is intentionally small.
- **Don't apply SDUI colour changes mid-session.** Re-render flicker
  was painful before we moved to the "take effect on next launch" model.
- **Don't theme the dot-matrix board.** That's the signage panel, the
  brand cue. It IS the product visual; it should look identical on every
  device, every theme.
- **Don't invert brand amber to TfL red.** This was tested; users
  immediately read it as "TfL clone" rather than "Stationly".
- **Don't bundle TTF fonts.** APK bloat for a font that the GMS provider
  already caches once across every app on the device.

## Cross-references

- Backend service: `stationly-backend/src/services/themeService.ts`
- SDUI route: `GET /sdui/app/theme-tokens`
- KMP wire model: `core/src/commonMain/kotlin/model/sdui/SduiAppModels.kt`
  (`SduiThemeTokens`)
- Dream-specific palette: `android/.../dream/DreamTheme.kt`
- Per-layout SDUI theme (separate system): widget XML + `Board.kt` only,
  used for the dot-matrix card. NOT to be confused with the app theme
  tokens. See `dream/CLAUDE.md` invariant #10.
