# Stationly iOS Widget — Design & WidgetKit Constraints

**Audience:** the next engineer/agent touching `iosApp/StationlyWidget/`.
**Last updated:** 2026-08-08. **Branch:** `ios-parity`.
**Companion docs:** `docs/IOS_BUILD_AND_HANDOFF.md` (build/deploy, FCM→widget data
flow), `docs/IOS_PARITY_PLAN.md` (the phased plan).

The widget is a SwiftUI/WidgetKit reimplementation of the Android home-screen
departure board (`android/res/layout/widget_departure_board.xml`): TfL amber on
black, dot-matrix "lit cell" rows, one station per widget — chosen per widget in
its configuration (§5).

---

## 1. File map (`iosApp/StationlyWidget/`)

| File | Role |
|---|---|
| `StationlyWidgetBundle.swift` | `@main` widget bundle entry point |
| `AppGroupID.swift` / `AppGroupKeys.swift` | The App Group's identifier and every key in it — one declaration each, per target (§5) |
| `StationlyWidget.swift` | Widget declaration + `DepartureBoardProvider` (the timeline) |
| `StationConfiguration.swift` | Which station this widget shows — entity, query, configuration intent (§5) |
| `WidgetPageIntent.swift` | Per-station platform paging + the refresh intent (§6) |
| `WidgetRefreshService.swift` | The extension's own REST refresh |
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

## 5. One widget, one station (2026-08-07)

The widget used to read a single set of flat App Group keys describing the app's
PRIMARY station, so every widget on the home screen showed the same board and
adding a second one was pointless. It is now configurable per instance, on the
iOS Weather model: **several widgets, each pinned to a place.**

### What the user does
Long-press the widget → **Edit Widget** → **Station** → a list of their stations,
each with its name, what runs there, and a mode symbol. Exactly where anyone who
has configured a Weather widget already expects to find it.

### How it is built

| Piece | Role |
|---|---|
| `StationConfiguration.swift` | `StationEntity` (one station), `StationEntityQuery` (the list), `SelectStationIntent` (the configuration) |
| `StationlyWidget.swift` | `AppIntentConfiguration` + `AppIntentTimelineProvider` — was `StaticConfiguration`/`TimelineProvider` |
| `core/iosMain/platform/WidgetAppGroup.kt` | The KMP→Swift wire format: `WidgetStationRef`, `WidgetBoard`, `WidgetFeed` |
| `IosWidgetManager.refreshFromPrimary` | Now writes EVERY station, not just the primary |

Two new App Group keys carry it: `widget_stations` (the directory the picker
reads) and `widget_board_<groupingId>` (one station's whole board). Keyed rather
than nested in one blob because NSUserDefaults has no partial write, and
re-encoding every station's departures on every frame of a live stream is work
that scales with how many boards the user keeps.

### The decisions worth knowing

- **The id is the app's GROUPING id** — the hub — which is exactly what one card
  on the home screen is. A bus hub's several poles are one entry in the picker,
  not one per pole, and "a widget" and "a card" mean the same thing.
- **The row's icon is the real roundel, and the fallback matters more than it
  looks.** `DisplayRepresentation.Image(data:)` takes a bitmap, so `RoundelImage`
  walks the same ladder the board itself does: the backend's cached PNG → a
  roundel DRAWN in that mode's tint → an SF Symbol. The `/modes` PNGs are simply
  absent on a fresh install, which is exactly when someone is setting their
  widgets up, so a picker that only knew how to show cached PNGs would be blank
  at the worst possible moment. `isTemplate: false` throughout — a template image
  is re-tinted to the system's foreground colour, flattening every roundel to one
  shade and throwing away the only thing that distinguishes a bus stop from a
  tube station at a glance.
- **The subtitle's line names come from KMP.** `LineShortNames.displayName`
  resolves them before the directory is written, so the extension holds no line
  vocabulary at all. The first version prettified canonical ids in Swift and
  promptly disagreed with the app's own station settings screen about the same
  station.
- **The query is an `EntityStringQuery`**, so the search field at the bottom of
  the sheet actually filters — on station name *or* line name, because at the
  moment of typing the user has one of the two in mind and no way to say which.
- **A station's board is MERGED across its lines**, the way the app's card merges
  it. A user tracking the Circle and the District at Edgware Road and pinning
  that station is asking for Edgware Road, not for whichever line sorts first.
- **No per-platform line prefixes**, unlike the app's board ("(Cir.) Edgware
  Road"). The extension's own REST refresh re-derives rows from the payload and
  cannot know which line each came from, so prefixes would appear on a push and
  vanish on a refresh tap — a board that changes shape depending on who wrote it
  last.
- **`lineName` is only set when the station tracks exactly one line.** It is what
  the platform header prefixes ("Piccadilly: Platform 1"), and with two lines on
  one platform that prefix would name one and be wrong about the other.
- **The legacy flat keys are still written for the primary.** They are what an
  unconfigured widget reads — one added before this build, or one whose station
  has been deleted — and the window between installing an update and WidgetKit
  next asking for a timeline is exactly when a half-migrated App Group would blank
  a widget that was working a second ago.
- **Stale `widget_board_*` keys are pruned on every write.** Left behind, a widget
  still configured for a deleted station would render its last known departures
  for ever, with no refresh able to correct them.
- **Refresh and paging now carry the station id.** With several widgets up,
  "refresh the widget" would otherwise rewrite the legacy keys and change a board
  the user was not touching. The debounce stays GLOBAL, though: it exists to
  protect TfL from a drumming finger, and three widgets are three buttons.
- **The refresh does one REST call per distinct naptan** (one for rail, one per
  pole at a bus hub, capped at 3) and keeps only the feeds the user tracks —
  the endpoint answers with every line calling there.

### What it costs, and the two rules that keep it affordable

`IosWidgetManager.refreshAllBoards` runs on **every stream frame and every
push** — on a busy station, every few seconds — and it now rebuilds N stations
rather than one. Two rules hold the cost down, and both are load-bearing rather
than micro-optimisation:

1. **Every board is built once.** The primary's board used to be built a second
   time to fill the legacy keys: the same ~3 SQL queries per selection, run
   twice, per frame.
2. **Writes are diffed and the reload signal is bumped once, only if something
   moved** (`putIfChanged`). That signal is what makes Swift call
   `WidgetCenter.reloadAllTimelines()`. Bumping it unconditionally asked
   WidgetKit to regenerate every widget's timeline on every push — including
   pushes for a station none of the user's widgets show — and Apple meters
   reloads at roughly 40–70/day.

Related: the stale-key sweep reads the ids back out of the directory it already
writes rather than calling `dictionaryRepresentation()`, which materialises the
entire user-defaults domain (every Apple-owned key in it) and was doing so on
the same hot path.

### ⚠️ If the picker is empty
`widget_stations` is written by KMP, so **the XCFramework has to be rebuilt**
(§4 of `IOS_HANDOVER.md`). A Swift-only build links the previous framework, the
key is never written, and the editor shows a "Station" row with nothing in it —
which looks exactly like a broken `EntityQuery`. This was hit during
development; check the key is present before debugging AppIntents:

```bash
xcrun devicectl device copy from --device <id> --domain-type appGroupDataContainer \
  --domain-identifier group.com.stationly.shared --source / --destination /tmp/pull
plutil -convert xml1 -o /tmp/ag.xml /tmp/pull/Library/Preferences/group.com.stationly.shared.plist
python3 -c "import plistlib;d=plistlib.load(open('/tmp/ag.xml','rb'));print(d.get('widget_stations'))"
```

## 6. Platform paging: arrows, not a tappable header (2026-08-07)

WidgetKit cannot scroll, so the board pages between platform groups (§2). That
paging was one `Button(intent:)` wrapping the whole header, cycling forwards with
a "‣ 2/3" hint. It worked and it could not say two things the user needs:
**which directions exist**, and **how to get back** — from the last platform the
only route to the first was to keep going forwards, and nothing on screen
suggested the header was tappable at all.

Now: a chevron at each end of the header cell, and the board slides in the
direction the arrow points.

- **The platforms are a RING (2026-08-10).** Past the last platform the next
  arrow press comes round to the first, and back from the first lands on the
  last. They used to be a line with two ends, and the arrow pointing past an end
  went dim — the theory being that a dim arrow says "nothing that way". In use it
  says "this widget's controls are broken": on a two-platform board one of the
  two arrows is always dead, and a user pressing the same place twice gets a
  response once. Both arrows are live whenever the section has more than one
  platform, and none is ever drawn dim.
- **The "2/4" marker carries the whole position story** as a result, and is
  therefore `fixedSize()` — it must never be the text a narrow canvas squeezes
  out. It is also what tells a three-platform board from a two-platform one now
  that no arrow dims.
- **Both arrow slots are reserved unconditionally**, so the title stays optically
  centred and does not shift by half an arrow as it pages. Same trick as the
  station header's refresh slot, and for the same device-proven reason.
- **Two normalisations, and the difference is load-bearing.**
  `WidgetBoardPage.move` CLAMPS what it reads and WRAPS what it writes. Wrapping
  is the step. Clamping is what a reader does with a stored index it did not
  write: a station whose Platform 4 goes quiet drops to three groups, and a
  widget parked on the last page must land on the last platform there IS rather
  than being flung back to the first. Both the renderer and the intent clamp the
  stored value the same way, so they always agree about which page is current.
- **Page state is keyed per STATION and per SECTION** (`widget_page_<id>`,
  `widget_page_<id>#u`, `widget_page_<id>#d` — see §6.1). There is no supported
  per-instance identifier in WidgetKit — the provider is handed a configuration,
  not an instance — so two widgets pinned to the same station page together. That
  is the one case this cannot separate.
- **`groupCount` is passed into the intent**, not recomputed inside it. The
  intent runs with no access to the rendered board, and re-deriving the count
  from the App Group would use rows ticked to a different minute than the ones
  the user is looking at, which is exactly when the count can differ by one.

### ⚠️ An arrow must always be a Button

The first version drew the disabled arrow as plain content, on the reasoning
that something inert should not look tappable. On device that was actively
wrong: **every non-interactive pixel of a widget belongs to the widget's own tap
target**, so tapping the dim arrow launched the app — the one thing a disabled
control must never do.

There is no disabled state left to get wrong, but the rule outlived it: anything
arrow-shaped on this board is a `Button(intent:)`, and the same reasoning applies
to anything decorative added later.

## 6.1 One board, three families (2026-08-10)

Three gaps closed at once, and they were all the same gap: **layout decisions
that lived in one family's view instead of in the shared board.**

### The small family had its own view, and fell behind it

`SmallWidgetView` drew a station lockup, the first three departures of whatever
block came first, and a footer. So a 2×2 widget had **no refresh button and no
platform pager** — not by decision, but because those were built in
`BoardWidgetView` and this view simply never called them. A user tracking three
platforms saw one of them, with nothing on screen admitting the others existed.

It is deleted. Every family now renders `BoardWidgetView` at its own
`BoardMetrics`, and the metrics carry what actually differs: type scale, the
inset of the corner-zone cells, and the width of the tap targets (small's
refresh slot is 22pt against medium's 30pt, its arrow slot 24pt against 37pt —
two chevrons and a refresh button have to come out of a third of the width).
`HeaderLadder` is what makes the header survive that: it steps down through
KMP's shorter wordings ("Piccadilly Platform 2 Westbound" → "Pic. Plat. 2")
rather than scaling or ellipsising, which is why the small pager can afford
arrows at all.

### The large family dropped platforms it had no room for

Large rendered every block top-down against a whole-board row budget: the first
platform took what it wanted, the next took the remainder, **and a third
platform got nothing and was not drawn.** No arrow said so, because paging was
medium-only. On the biggest widget iOS offers, a third platform was unreachable.

Large now draws **two sections**, each a pager of its own (`BoardSection.upper` /
`.lower`), 3 row cells each:

| Platforms | Upper | Lower |
|---|---|---|
| 1 | the block, 6 rows | — |
| 2 | one block | one block (unchanged from before) |
| 3 | two blocks, arrows | one block |
| 4 | two blocks, arrows | two blocks, arrows |

### Which blocks share a section: `WidgetData.sections`

A split purely by count would put the Piccadilly westbound above and the
Piccadilly eastbound below — two halves of one decision, at opposite ends of the
widget, each paging past the other line to be compared. So blocks are collected
into runs of related ones first, and the split falls on a run boundary.

- **Rail: the line**, read off the rows' `lineShort` (KMP resolves it on every
  rail row; `mixesLines` only decides whether it is *drawn*). A shared
  Circle/District platform is its own kind and pairs with another shared one.
- **Bus: the routes calling at the pole.** A bus block IS a pole, so pole
  identity groups nothing — every block would be its own run. Route 39 northbound
  and southbound are two naptans on opposite sides of one road, and that
  association lives in `feeds` (`station` = the pole = the bus block key, `line` =
  the route).
- **Neither: the block key**, which yields one run per block and therefore a
  plain balanced split — the right answer when there is nothing to reason from.

The boundary chosen is the one leaving the halves closest in size, ties going
UPWARDS so three platforms put the pager on top where the eye lands first.

**This is the one place a renderer reorders KMP's blocks**, and it is bounded:
order within a run is untouched (unassigned last, the pin, the soonest train),
runs appear in the order their first member did, and the function is pure — so
every entry of a timeline sections identically and no page moves under an arrow.

## 6.2 The cell count is fixed; only the data moves (2026-08-10)

**Symptom:** "the widget keeps resizing, the font size keeps changing." It was
not the font. Every cell is height-flexible at equal priority (§3.6), so the
NUMBER of cells decides how tall each one is — and the board drew a cell per
departure it happened to have. Four trains at 09:00, three at 09:03, and the
whole panel re-laid itself out around the difference. A board that reflows while
you are reading it looks broken, not live.

So the skeleton is now a constant per family and the data moves inside it:

| | small / medium | large |
|---|---|---|
| station | 1 | 1 |
| platform header | **1, always** — even with no label to put in it | 1 per section = **2, always** |
| departures | 3 | 3 per section = 6 |
| status | takes the **3rd departure cell** when the platform can't fill it | **1 of its own**, always |
| clock | 1 (shed under a 150pt canvas) | 1 |
| **total** | **6** | **11** |

Which gives, on small and medium:

- **3+ departures** → three rows, no strip.
- **2** → two rows and the strip.
- **1** → one row, one dark cell, the strip. *(The dark cell is the point: without
  it the two remaining cells would each grow by half a row.)*
- **0** → "No departures right now" in the first cell, a dark cell, the strip —
  which is where the reason ("Service Closed : …") actually gets said.

and on large, a second platform section that is drawn empty when the station has
only one platform, rather than collapsing and stretching everything above it.

Three supporting pieces, all of which exist for the same reason:

- **`EmptyRowCell`** is LIT, not black. The black gaps between cells read as the
  panel bezel, so an unlit two-row gap looks like a hole in the panel rather than
  an empty row on it.
- **The status strip takes a departure cell's height** on the paged board
  (`metrics.row + 10`, not `status + 8`). It is standing in for a row, so at its
  own smaller minimum it would resize its neighbours every time it appeared.
- **`SkeletonBoardView` draws the same cell count**, because it is what is on
  screen immediately before the first data lands.

`NoDeparturesRow` — a bare centred Text at `maxHeight: .infinity` — is gone; it
was the largest single source of the reflow.

### The status strip says WHICH line (2026-08-10)

`buildBoard` used to send "the first line that has anything to say", with a note
that ranking "would need a severity ranker the extension does not have". True,
and the wrong side to look: the extension cannot rank, but KMP can, and
`LineStatusRanker` — the home board's own — has been in `commonMain` all along.
At King's Cross a part-closed Northern was hidden behind a healthy Victoria that
merely sorted first, so the widget said "Good Service" while the app's board said
"Northern Part Closure".

The widget now gets the **worst line first, named**, through the same ranker and
the same de-duplication ("Circle, District Minor Delays"). A line with no status
record counts as Good Service rather than as an unknown severity, which would
otherwise lead the board with an empty sentence.

**The rotation is not ported.** The home board cycles the remaining disrupted
lines every 8s; a widget is a sequence of static snapshots with no animation loop
(§2.1), and a strip that changed its subject on the per-minute timeline is the
same idea as the stepped marquee that was built and rejected (§3.3). The worst
line is the one that changes a journey.

### Making it fast

**Do not call `reloadTimelines` from an interactive intent unless the DATA
changed.** WidgetKit re-renders the tapped widget by itself once `perform()`
returns, from the timeline it already holds — which is immediate. The paging
intent also called `reloadTimelines(ofKind:)`, which threw that timeline away
and rebuilt all 61 entries, each re-ticking every departure, *before* the new
page could be drawn. That rebuild was the entire lag between the tap and the
board moving, and it bought nothing: the page number is in the App Group and
every entry reads it at render time.

`perform()` is now two `UserDefaults` writes and a return. The cost: a second
widget pinned to the same station shares the counter and waits for its own next
reload. The refresh intent still reloads, because there the data genuinely
changed.

The first tap after the widget has been idle is still slow — that is the
extension process cold-starting, and no code here can avoid it.

### Making it look smooth

A widget cannot animate on its own, but **WidgetKit does animate the view diff
after an interactive intent** (iOS 17+). Three things make it read as one board
moving rather than a cut:

1. **The header and every row carry the same `.transition(.push(from:))`.** The
   push direction comes from the last move, stored in the App Group
   (`widget_board_page_dir_<id>`): the view is rebuilt from scratch after the
   intent and has no memory of the previous page, and a transition that always
   pushes one way makes going back feel like going on.
2. **Identity is the PAGE, not the row.** `DepartureRow.id` is a fresh UUID on
   every decode, so keying rows on it would re-insert every row on every minute
   tick and animate a countdown as though the platform had changed. Rows are
   `.id("\(page)-\(index)")`.
3. **The title and its "2/3" marker travel together** inside one transitioning
   container. The marker is part of what changes; leaving it still while the
   platform name slides out from under it is what makes a transition look
   half-finished.

Transitions WidgetKit does not support are ignored rather than failing, so the
worst case here is the cut we had before.

## 7. The App Group is a hand-kept contract (2026-08-08)

Nothing checks that the Kotlin and Swift sides agree about the App Group, and
both halves of the failure are silent:

- **A wrong KEY** reads `nil`, which is indistinguishable from "the app never
  wrote it". The project has already paid for this once — the 2026-07-25 App
  Group ID rename had to find every copy of the identifier, and a missed copy
  opens an empty suite rather than failing to build. The keys had drifted into
  the same shape: ~20 raw literals across four Swift files, four of them spelled
  out in two files each. They now live in one `AppGroupKeys.swift` per target
  (the app and the extension are separate compilation units and cannot share
  one), mirroring `AppGroupKeys` in `core/iosMain/platform/Platform.ios.kt`.
- **A wrong FIELD** in the JSON decodes as absent, and the extension renders an
  empty board rather than throwing. `WidgetAppGroup.kt` and its Swift mirrors
  (`StoredBoard`, `StationRef`, `BoardFeed` in `AppGroupStorage.swift`) are the
  contract: **add fields, never rename them, and change both sides in one
  commit.**

**Do not nest one key's name inside another's prefix.** The paging keys were
first called `widget_board_page_<id>`, which sits under the `widget_board_`
prefix a station's board uses — so any code that ever scans by that prefix reads
`widget_board_page_940GZZ…` as a station whose id begins "page_". Nothing scans
by prefix today (the stale-key sweep diffs the directory instead), and the point
of the rename to `widget_page_<id>` is that nothing can start.

Ownership, which is what tells you whether a change needs a matching one over
there:

| Keys | Written by | Read by |
|---|---|---|
| `widget_station_*`, `widget_predictions`, `widget_status`, … | KMP (primary station) | extension, when unconfigured |
| `widget_stations`, `widget_board_<id>` | KMP (all stations) | extension + configuration picker |
| `widget_api_*` | KMP | extension's own REST refresh |
| `widget_reload_signal` | KMP, and the extension after a refresh | the app's `WidgetReloadObserver` |
| `widget_page_<id>` (+ `#u`/`#d` sections), `widget_page_dir_<id>` | extension | extension — **but KMP deletes them** when a station is removed, because it is the only side with an event for that. A section added in Swift needs its suffix in `AppGroupKeys.WIDGET_PAGE_SECTIONS` or it leaks |
| `widget_last_manual_refresh`, `widget_refresh_*` | extension | extension |

---

## 8. Quick build + deploy (Swift-only widget changes)

**Swift-only** means no Kotlin touched at all — including
`core/iosMain/platform/Platform.ios.kt`, which is where the widget's data comes
from. If you edited any Kotlin, rebuild the framework first (`IOS_HANDOVER.md`
§8) or you will ship the previous one and debug a symptom that is not there;
§5's "if the picker is empty" note is what that looks like.

From `iosApp/`:

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
