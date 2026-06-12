# Stationly iOS Widget — Design & WidgetKit Constraints

**Audience:** the next engineer/agent touching `iosApp/StationlyWidget/`.
**Last updated:** 2026-06-11 (Session 6). **Branch:** `ios-parity`.
**Companion docs:** `docs/IOS_BUILD_AND_HANDOFF.md` (build/deploy, FCM→widget data
flow), `docs/IOS_PARITY_PLAN.md` (the phased plan).

The widget is a SwiftUI/WidgetKit reimplementation of the Android home-screen
departure board (`android/res/layout/widget_departure_board.xml`): TfL amber on
black, dot-matrix "lit cell" rows, one station per widget.

---

## 1. File map (`iosApp/StationlyWidget/`)

| File | Role |
|---|---|
| `StationlyWidgetBundle.swift` | `@main` widget bundle entry point |
| `StationlyWidget.swift` | Widget declaration + `DepartureBoardProvider` (the timeline) |
| `WidgetViews.swift` | All views: board, rows, status strip, footer, live clock, empty state |
| `WidgetTheme.swift` | Design tokens (amber/black palette, mode tints, ETA colours) |
| `DepartureEntry.swift` | `TimelineEntry` + `WidgetData`/`DepartureRow` models |
| `AppGroupStorage.swift` | Reads board state from the App Group (written by KMP `IosWidgetManager`) |

Data path (detail in `IOS_BUILD_AND_HANDOFF.md` §5): FCM push → KMP
`ProcessFcmPayloadUseCase` → `IosWidgetManager.updateWidget` writes the App Group
+ bumps `widget_reload_signal` → `WidgetCenter.reloadAllTimelines()` →
`DepartureBoardProvider.getTimeline` re-reads the App Group.

---

## 2. The hard WidgetKit constraints (why the design is what it is)

A home-screen widget is a **pre-rendered static snapshot**, not a live view:

1. **No animation API reaches the widget.** `withAnimation`, `TimelineView`,
   `Canvas` redraws, GIFs — none run. A smooth, per-second marquee is
   **impossible**. This is the answer to "Android does it, why can't iOS?":
   the Android widget is a `RemoteViews` tree of REAL views hosted live by the
   launcher process, so `android:ellipsize="marquee"` +
   `marqueeRepeatLimit="marquee_forever"` (see
   `android/app/src/main/res/layout/widget_departure_board.xml`,
   `status_reason`) animates continuously for free. An iOS widget is an
   archived snapshot rendered by a system process (`WidgetRenderer_Default`);
   no view code from the app ever runs while it's on screen. There is no
   scrolling-text primitive through iOS 26 — Apple's own Stocks/News widgets
   don't marquee either.
2. **The only self-updating elements** are the date/timer `Text` styles
   (`.timer`, `.relative`, `.time`, …). These tick every second on the home
   screen with zero timeline reloads — Apple's own Clock widget runs on this.
3. **Timeline entries are honoured at ~1/minute at best.** Sub-minute entry
   dates get coalesced. Pre-rendered local entries are otherwise free: they do
   NOT consume the ~40–70/day background-refresh budget; only timeline
   *reloads* (`.atEnd`, `WidgetCenter.reload…`) do.
4. **iOS 17 adds default content margins** (~16pt safe area) around widget
   content unless the configuration opts out.

Everything non-obvious in the widget exists to work around 1–4.

## 3. The three Session-6 design decisions

### 3.1 Full-bleed board (the widget IS the board)

Problem: the board floated inside a black frame — default iOS 17 content
margins (§2.4) + an internal `.padding(5)` + rounded per-cell corners made it
read as "a board within the widget".

Fix:
- `.contentMarginsDisabled()` on the `WidgetConfiguration`
  (`StationlyWidget.swift`) — content extends to the physical widget edges.
- Outer `.padding(...)` removed from `BoardWidgetView` / `SmallWidgetView`.
- `LitCell` corner radius → **0** (squared cells, full width). The system's own
  corner mask clips the four outer corners to the widget's continuous radius —
  no manual rounding needed, and the 2pt black `VStack` gaps between cells read
  as the panel bezel.
- Cell text inset is the cells' own `hPad`: **10pt for mid-board rows, deeper
  for the corner-zone cells** — header 14pt; footer 20pt (16pt on small). The
  top/bottom cells sit inside the widget's corner-mask zone: at the footer
  logo's height the curve intrudes ~9–12pt (iOS 26 corner radii are generous),
  so a 10pt inset left the Stationly mark and the "ago" timer looking clipped.
  Only the *content* is inset; the lit-cell backgrounds stay full-bleed.

Height behaviour is unchanged: every cell is height-flexible
(`maxHeight: .infinity` inside `LitCell`) with `layoutPriority` steering the
share-out, so the column always fills the canvas exactly.

### 3.2 Live HH:MM:SS footer clock (ticks every second)

`LiveClock` (`WidgetViews.swift`): a `.timer`-style `Text` anchored at **local
midnight** — `Text(Calendar.current.startOfDay(for: entryDate), style: .timer)`.
Elapsed time since 00:00:00 *is* the time of day, so the system renders a
self-ticking `18:47:32` with no timeline reloads (§2.2). Used by both the
medium/large footer and the small widget.

Gotchas baked into the implementation:
- **Midnight rollover:** the anchor is computed per timeline entry; the
  per-minute entries re-anchor it within a minute of 00:00.
- **Greedy layout:** timer Texts expand to fill their container and
  left-align. `.multilineTextAlignment(.center)` centers the digits inside the
  expanded frame, keeping the clock mid-board in the footer `ZStack`. (The same
  greed previously broke the "ago" element — see `LiveAgo`'s comment.)
- **Known cosmetic limit:** the timer style cannot zero-pad the hour — a
  single-digit hour renders `8:05:09`, not `08:05:09`. No API for this; accepted.

The old `DateFormatter("HH:mm")` static clock is gone.

### 3.3 Status strip: one colour + stepped marquee

- **Single colour:** severity is **board amber like every other cell** (bold
  weight carries the emphasis); the green/orange per-severity tinting was
  removed (`WidgetTheme.statusColor`, `goodService`, `disruption` deleted).
- **NO marquee — removed by product decision (2026-06-11).** Android's
  continuously-flowing `ellipsize="marquee"` is impossible in WidgetKit
  (§2.1). A stepped fallback WAS built and shipped briefly: the per-minute
  timeline entries advanced a 24-char window through the reason text,
  anchored to a persisted per-status-text timestamp (two earlier anchors
  failed: minutes-since-epoch → random mid-word phase; `lastUpdated` → reset
  to 0 by every push). Verdict from the user: a once-per-minute step doesn't
  read as a marquee, it reads as broken — **if it can't flow like Android,
  show static text**. The reason now truncates with a tail. Don't rebuild a
  stepped marquee; this was tried and rejected. (The severity prefix is
  `fixedSize()` so the truncation always eats the reason, never the label.)

### 3.4 Widgets stuck on redacted placeholder — FULL INVESTIGATION LOG (2026-06-11)

**Symptom:** newly added small/large widgets render as the redacted
placeholder (olive blocks) forever; medium coasts on an old archived
timeline. NOT "blank" — the placeholder archive renders; the *real timeline*
never lands.

**The definitive error** (device syslog via `idevicesyslog`, which DOES work
on this iOS 26 device despite older notes saying otherwise):

```
chronod(ChronoKit): ... timelines/StationlyDepartureBoardWidget/systemSmall----...chrono-timeline
  destroying promise for 'Unable to unarchive collection:
  Error Domain=WidgetKit.WidgetArchiver.ArchivingError Code=2'
chronod(ChronoKit): Task ... Reload failure / Reload state reload -> failed
```

The extension runs fine (`getTimeline` returns 61 entries per family — proved
via `providerLog` os_log lines), the extension archives to
`/var/mobile/tmp/com.apple.chrono/NSIRD_chronod_*/...`, then **chronod cannot
unarchive the produced collection and rejects it**. Placeholder archives
(1 entry, redacted) are accepted; timeline archives (61 entries) are not.
`WidgetCenter` reloads report nothing — the failure is silent app-side.

**Hypotheses tested ON DEVICE and DISPROVED — do not re-try these:**

| # | Hypothesis | Test | Verdict |
|---|---|---|---|
| 1 | WidgetRenderer memory kill from per-cell `Canvas` DotGrid (a JetsamEvent red herring — the jetsam victim was locationd) | Replaced Canvas with tiled `UIImage`, then ONE overlay per board, then **removed the texture entirely** | still `Code=2` |
| 2 | Midnight-anchored `.timer` LiveClock unarchivable | Reverted to static `DateFormatter` HH:mm | still `Code=2` |
| 3 | Marquee (per-entry varying strings) | Reverted to static truncating reason | still `Code=2` |
| 4 | `.contentMarginsDisabled()` | Removed it | still `Code=2` |
| 5 | ANY session code change | **Built the exact `git HEAD` (74b2185) widget sources — the design that was demonstrably live-rendering at 18:47 the same day** | still `Code=2` |
| 6 | Poisoned chronod cache | Rebooted the device (`idevicediagnostics restart`) | still `Code=2` |
| 7 | Xcode-26.5-SDK vs iOS-26.3-beta archive-format skew | Checked: Xcode installed Jun 8 — the SAME toolchain built yesterday's binary whose archive was accepted at 18:41 today | ruled out |
| 8 | **The cached backend mode-icon PNG** (`App Group/mode_icons/overground.png`, fetched from `/modes` by KMP ModeIconStore) poisoning the archive | Re-encode the PNG through `UIGraphicsImageRenderer` before it enters the view (`ModeIconProvider.rerendered`) | **FIXED — `Reload success`, zero unarchive errors, widgets live** |

**ROOT CAUSE: the raw backend mode-icon PNG.** `ModeIconView` embeds the
`UIImage(contentsOfFile:)` bitmap into every timeline entry's view archive
(61 entries × 3 families). Something about the file as served (colour space /
encoding / dimensions — exact property undetermined; the file itself isn't
readable off-device) makes chronod's unarchiver reject the whole collection
with `Code=2`. The placeholder always passed because placeholder data uses
mode `tube`, which has no cached PNG → the *drawn* `TflRoundelMark` fallback.
Timing fit: the icon cache was re-fetched around the 19:01 first app launch
after reinstall, which is exactly when archives started failing — the 18:41
success predated the re-fetch.

**Permanent fix (keep this!):** `ModeIconProvider.icon()` never returns the
raw file image; it re-renders into a fresh 48pt/`scale 3` standard-format
bitmap (`rerendered(_:)` in `AppGroupStorage.swift`). Visually identical at
roundel sizes, guaranteed archive-safe regardless of what the backend serves.
If other raw images ever enter widget views (e.g. future station photos),
they MUST go through the same kind of re-render.

**Verification loop for any future change to this pipeline** (no home-screen
eyeballing needed):

```bash
idevicesyslog -u <UDID> > /tmp/ws.txt 2>&1 & sleep 2
xcrun devicectl device process launch --device <UDID> com.stationly.mobile
sleep 28; kill %1
grep -i stationly /tmp/ws.txt | grep -oE \
  'Accepted successfully to [^ ]*timelines/system[A-Za-z]*|Unable to unarchive collection[^"]*|Reload (failure|success)'
# PASS = "Accepted successfully to .../timelines/system{Small,Medium,Large}"
# FAIL = "Unable to unarchive collection ... Code=2" + "Reload failure"
```

**Found along the way (real bugs, fixed):**
- `applicationDidBecomeActive` is DEAD CODE in a SwiftUI scene-lifecycle app —
  UIKit never calls it. The foreground auth refresh, FCM queue flush and
  `reloadAllTimelines()` never ran. Now wired via `scenePhase` in
  `iOSApp.swift` → `AppDelegate.handleDidBecomeActive()`. (Foreground reloads
  are budget-exempt; this is what delivers first timelines to newly added
  widget instances.)
- `print()` in the widget extension is invisible on device; use `os.Logger`
  (see `providerLog` in `StationlyWidget.swift`).

### 3.5 What the per-minute timeline now exists for

`DepartureBoardProvider.getTimeline` still emits one entry per minute for the
next hour (`.atEnd` → ~24 App-Group re-reads/day). Since the clock ticks on
its own (§3.2), the entries' remaining job is re-anchoring the clock across
midnight (and giving the system per-minute flip points generally). Do not
remove them.

### 3.6 Medium content budget (2026-06-12)

Problem: the medium board stacked up to 8 cells (station + platform header(s)
+ 4 rows + status strip + footer). Cell minimums sum to ~176pt against a
fixed ~155–170pt canvas (Android's 5×3 widget gets ~200–240dp and is
user-resizable — iOS medium never grows), so SwiftUI compressed every cell
below its minimum and the board read as crumbled. The TfL look (fonts, amber,
row surfaces, 2pt bezel gaps) is intentional and was NOT the problem — the
board simply had more rows than the canvas could pay for.

Fix (`BoardMetrics.singlePlatform` + `BoardWidgetView`):

- **Medium budget = 6 cells (~156pt of minimums)**: station + ONE platform
  header (first `groupedByPlatform` group only — a second group would cost a
  second header cell) + exactly 3 departure rows (`maxRows` 4 → 3) + footer.
- **Status strip is backfill-only on medium**: it renders only when the
  primary group has fewer than `maxRows` departures (spare slot — quiet
  boards never show dead space) or when there are no departures at all (the
  board's one shot at saying WHY, e.g. "Service Closed"). Large keeps it
  unconditionally.
- **Footer shed below 150pt** (`GeometryReader`): only SE-class mediums
  (321×148) fall under the threshold; every other family keeps the live
  clock/ago footer.
- **Large is untouched**: every platform group + status + footer — full
  Android parity (large ≈ the Android widget's real canvas).
- **Even surplus distribution (same day, follow-up)**: the original
  layoutPriority ladder (header/footer 2, platform/status 1, rows 0) made
  ONLY the header and footer balloon when the board had 1–2 departures —
  mid rows stayed pinned at minimum height. All `.layoutPriority` modifiers
  were removed: every cell is `maxHeight: .infinity` at equal priority, so
  the VStack splits spare height evenly; the per-cell `minHeight`s remain
  as compression floors. Priorities had no remaining job once the medium
  board was budgeted to fit its canvas.

### 3.7 Mode roundel aspect ratio (2026-06-12)

The station-lockup roundel rendered visibly squashed. Two compounding bugs:

1. `ModeIconProvider.rerendered` (the §3.4 archive-safety re-encode) drew the
   backend PNG into a FORCED 48×48 square — the TfL roundel is wider than
   tall (~1.22:1). Now renders onto a canvas that keeps the source aspect
   (longest side 48pt).
2. `ModeIconView` pinned the image into a square `size × size` frame. Now
   height-anchored (full `size` height, natural width clamped 0.6–1.6×) —
   the iOS equivalent of the Android lockup's 22dp `fitCenter` ImageView.

No cache invalidation needed: the distortion happened at render time; the
cached PNG on disk was never modified.

---

## 4. Quick build + deploy (Swift-only widget changes)

No Kotlin touched → skip the shared-framework rebuild. From `iosApp/`:

```bash
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=00008030-001E0D9C3EFB802E' -derivedDataPath build/DD \
  -allowProvisioningUpdates build
xcrun devicectl device install app --device 00008030-001E0D9C3EFB802E \
  "build/DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --device 00008030-001E0D9C3EFB802E com.stationly.mobile
```

(The widget extension ships inside `iosApp.app`; reinstalling the app updates
it. The home-screen snapshot can lag a reinstall — remove/re-add the widget or
wait for the next timeline reload if it looks stale.)

Full procedure, signing and Xcode-26 gotchas: `IOS_BUILD_AND_HANDOFF.md` §0/§3.
