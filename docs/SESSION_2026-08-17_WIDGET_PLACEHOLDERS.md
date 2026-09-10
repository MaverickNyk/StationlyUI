# Widget placeholder text and design — session log

**Branch** `ios-parity` · **Date** 2026-08-17 · **Scope** iOS only
**Design reference** `docs/IOS_WIDGET_DESIGN.md` §6.4 (one ink) and §6.5 (the
board says why it is empty)

Everything a Stationly widget says when it has no departures to show: the words,
the palette, the type, the layout, and which of them is true.

---

## 1. The finding that started it

**Every piece of static text on the widget was the only text on the panel that
was not board amber.** Four call sites, and it was exactly the four.

| where | on | was | contrast |
|---|---|---|---|
| "no departures" cell | row surface | `textMuted` (0.40 grey) | ~3.0 : 1 |
| empty-state title | black | `textPrimary` (white) | ~21 : 1 |
| empty-state message | black | `textMuted` | ~3.7 : 1 |
| empty-state small message | black | `textMuted` | ~3.7 : 1 |

Everything the board got from TfL was amber (~11.3 : 1 on a row, ~13.5 : 1 on
black). Everything the board said in its own voice was a grey used nowhere else,
at roughly a third of the contrast, at 11pt — under the 4.5 : 1 body text is
normally held to.

Pulling that thread found a larger one: the widget reached **one** of the seven
empty-board states the app distinguishes, with a hardcoded string. At 02:00 the
home board said "Service ended for tonight" and the widget for the same station,
in the same second, said the trains merely happened not to be running.

---

## 2. Bugs fixed

Ordered by how wrong the user-visible result was.

### B1 · SIGNAL_LOST's `{age}` placeholder was destroyed at publish time
`publishFallbackCopy` called `resolveBoardFallbackCopy(BoardFallbackState(kind), …)`,
which substitutes `{age}` using the state's age — defaulted to **0** when
resolving a table. So `"Last refresh {age} ago"` was published as
**"Last refresh just now ago"**, and the widget then found no placeholder to
fill and would have shown that sentence at every age, forever.

Silent, permanent, and reproducible only on a stale board. Found in review
before it reached the device.

**Fix** `resolveBoardFallbackCopy` gained `substituteAge: Boolean = true`; the
publisher passes `false`. The parameter is documented as load-bearing so it is
not "simplified" away later.

### B2 · `snapshot(for:in:)` never resolved a fallback, so empty boards rendered blank
A snapshot is a real render — WidgetKit asks for one whenever it needs the
widget's current appearance without a timeline (home-screen state rebuilds, the
multitasking card, the gallery). It built its `DepartureEntry` without a
`fallback`, so `rowCell` had no message to place and fell through to dark cells.

An empty board therefore appeared as **a station name over an entirely blank
panel** — the exact "it's just blank" reading the whole fallback table exists to
remove, surviving in the one path that skips `timeline(for:in:)`.

**Fix** the snapshot resolves one too. The gallery preview is unaffected:
`WidgetData.placeholder` has departures, so it resolves to nil there anyway.

### B3 · The disrupted board printed the severity twice
`resolve` titled the message with the live TfL severity, matching the app. But
the widget's status strip sits **directly beneath** that cell and always renders
on an empty board (`cells < maxRows` is what puts it there):

```
Severe Delays                          ← fallback title
No departures expected here            ← fallback detail
Severe Delays : signal failure…        ← status strip
```

Two adjacent cells saying the same two words, on a panel with three lines to
spend.

**Fix** DISRUPTED takes the table's generic title. Generic title plus the
strip's specific one is strictly more information in the same space, and it is
what `BoardMessageCell` already documented: *"the reason, when there is one, is
in the status strip directly beneath."*

### B4 · A blank lit strip sat directly under the station name
The platform header was drawn unconditionally so the cell count stays fixed
(§6.2). With no platform to name, `section.group` is nil, the variants are
`[""]`, and it rendered **a lit cell with nothing in it** as the first thing
under the header — so an empty board opened with a gap and the message it was
meant to introduce started one cell late.

**Fix** the cell *moves* rather than disappearing: no header means one extra
dark cell at the end. Count identical, nothing resizes. Floors differ by half a
point across all three families, inside the rounding of an equal-share layout.

Generalised as a rule: **trailing dark cells read as a board with room left; a
leading one reads as a fault.**

Keyed on whether there is a header to write, deliberately **not** on whether the
board is speaking a message — a station whose platform block exists but has
emptied out still has a real name to show, and that header is useful precisely
then.

### B5 · "Live updates paused" was reported for correct overnight behaviour
Reported off the device:

> "when the service ended for night the text also says the widget hasn't updated
> since last update … but the fact is we did check with the backend so our
> update is recent but the trains are not there"

`SIGNAL_LOST` was tested **above** the closed-network windows. After the last
train nothing fetches, because there is nothing to fetch: the app is shut, the
stream has nothing to push, and the widget's schedule tapers overnight by design.
Five hours later the last sync is five hours old — *true* — and it was being
described as a fault.

**The timestamp was never wrong and is untouched.** It already measures the last
*check*, not the last train: `SqlStorage.saveSyncTimestamp` stamps every sync
including a zero-row one, and the extension's REST path writes `now`
unconditionally in `writeBack`.

**Fix, two parts:**
1. `computeBoardFallbackState` tests the windows before SIGNAL_LOST. Outside
   00:00–06:00 nothing changes; a stale board at 14:00 still says "Live updates
   paused", because then it genuinely is one. DISRUPTED keeps its place above
   the windows.
2. The freshness colour ladder is suppressed inside those windows
   (`BoardFallbackResult.freshnessMatters` → `LiveAgo.staleColor`). The reading
   stays — it is true, and the only thing on the panel saying how old this is —
   it just stops being drawn in alarm red.

### B6 · The status strip claimed "Good Service" over a blank status
`data.status.isEmpty ? "Good Service" : data.status`, so a board with no status
record told the user their line was running fine. Nothing had checked.

Same class as B7 below, and it showed up worst on the board least able to afford
it: a station the app has not written yet carries `status: ""`, so a widget that
knew nothing announced good service on a line it had never asked about.

**Fix** unknown status renders a dark cell. The **cell stays** — dropping it
would change the count.

### B7 · "No departures right now" was a claim nobody had checked
A board the app has never written carries an epoch timestamp and no rows. That is
not "the platform is quiet", it is "nothing has arrived yet". The distinction
already existed one layer down (`stateName` has called them `waiting` and
`quiet`); it never reached the glass.

**Fix** subsumed by the fallback table — CONNECTING before the first payload,
and the correct one of six after.

---

## 3. Performance

### P1 · Per-entry work that was per-batch work
`BoardFallbackResolver` was a free function taking `(data, now, table)`, so every
one of ~20 entries re-split the status string, re-read the same fields, and did
it **inside WidgetKit's archiving pass** — the path that stands between a tap
and the pixels.

Only the clock and the row count actually move between entries. Verified against
`ticked(at:)`, which carries `status` and `lastUpdated` through unchanged.

**Fix** it is a value now, built once per timeline with the invariants taken
apart in `init`; `result(hasDepartures:at:)` is the only thing that runs per
entry. The table read moved with it.

### P2 · `publishFallbackCopy` did full work on every stream frame
`refreshAllBoards` runs several times a minute per station. `putIfChanged`
spared the write and the reload, but everything before it — decoding the SDUI
map, resolving seven kinds, re-encoding — ran every time to produce a
byte-identical result.

**Fix** memoised on the raw SDUI blob, which is the string `loadHomeConfigCache`
would decode anyway, so the test costs one storage read and skips the rest.
`IosWidgetManager` is a singleton, so the memo survives between frames.

Races are benign by construction: the work is pure and the output identical, so
a double miss costs one duplicated computation — which is what happened on
*every* call before. No lock, deliberately; one would put contention on the
board-write path to prevent a duplicate no-op.

The memo is set **after** a successful encode and write. Set before, one failed
encode would memoise the failure and the table would never publish for the life
of the process.

### P3 · Earlier passes, carried forward
- `DotMatrixStatusStrip` split its status string three times per render; now
  once, via `StatusParts`.
- `HeaderLadder` paid up to five text layouts for a single-variant header.
- `RowID` is a `Hashable` struct rather than an interpolated string (~11 per
  entry, per widget).
- `motionKey` is `[Int]` rather than a joined string.

---

## 4. Code quality

### The theme is four colours
Deleted `textPrimary`, `textSecondary`, `textMuted`, `surface`, `stationlyRed`,
and `etaColor(eta:isDue:)` — all unused or reachable only from static text.

`etaColor` is worth naming because it was **a trap rather than merely dead**: it
returned amber / white / grey by parsed minutes, a *different colour policy*
from the one `DotMatrixRow` actually applies (red when due, amber when live,
amberDim once departed). Two rules for one thing, with the unused one looking
authoritative because it sat in the theme.

**The rule, stated:** text on this board is `amber`; hierarchy is size and
weight. `amberDim` means *spent* (a departed row), which an instruction the user
is meant to act on is not.

### One source for the words
`BoardFallback.kt` moved `composeApp` → `core`, following `TimeWindow.kt`. The
widget extension cannot link the KMP framework, so `IosWidgetManager` resolves
every kind and publishes the table for it to render.

**Rule: the wording is decided in `core`, once.** A surface may decide *which
kind* applies — that needs a clock and a render time only the surface has — but
never what it says. Same division as `headerVariants`.

### Duplication removed
- `minutesOfDay` reimplemented core's `parseHHmmOrNull`, with its own copy of the
  0..23 / 0..59 validation. Two answers to "is this a valid time" that would
  eventually disagree, silently, in a window nobody is awake to see. Now calls
  `parseHHmm`.
- Swift's `inWindow` is written to mirror `inTimeWindow` statement for statement,
  so the two can be checked by eye rather than proved equivalent. It had the
  degenerate `start == end` case split out first — same answer, different shape.
- `StatusParts` replaced the colon-splitting that existed inline in
  `DotMatrixStatusStrip` and would have been written a second time in the
  resolver.
- One empty-state copy table instead of two. The small family had its own four
  shorter strings and drew **no title**, so a deleted-station widget could say
  "Station removed" and never *which*.
- `WidgetSize` and `BoardMetrics.size` deleted — a parallel notion of "how big is
  this widget" that existed solely to feed `EmptyWidgetView`, running beside the
  one that does the work.

### The layout rule, corrected mid-session
The empty states were briefly given the board's header cell, and it was reverted
the same day on the owner's correction:

> The board layout is for a widget that HAS a station. It can be empty and it is
> still that station's board. A widget with no station behind it is not a board
> with nothing on it; it is not a board.

| the widget has | shows |
|---|---|
| a configured, tracked station | the board, message in a row |
| no station behind it | a centred mark and a sentence |

`.removed` sits on the no-station side: there is a name, but the station is gone
and the ask is identical to `.needsStation`. The name goes in the title, where it
does the one job it can still do — say *which* widget needs attention.

Recorded in §6.4 with a "do not re-extend the board layout to these states"
marker, because the reasoning is not visible from the code.

---

## 5. Considered and deliberately not changed

- **OFFLINE is not detected.** It needs reachability an extension cannot get
  cheaply, and the one available signal (`lastRefreshFailed`) only moves when the
  user taps refresh. SIGNAL_LOST says the part the user can act on without
  claiming a cause we cannot verify. The copy is still published so the day
  reachability exists, nothing else changes.
- **`ticked()` can drop a group mid-timeline**, so a board can lose its platform
  header between entries. The cell *count* is preserved by the `+1` rule, and the
  0.5pt floor difference redistributes to ~0.08pt per cell across six. Not
  visible; checked rather than assumed.
- **The three ambers, two reds and one grey** in the *state* palette
  (`#FFC819` vs `#FFB300`, `DueRed` vs `#FF3B30`, `#888888`) are untouched. Real
  findings, out of scope for placeholder work, and the stale ladder is matched to
  Android's. Written up separately.
- **`DotMatrixRow` applies its state colour to half a due row** (the ETA reddens,
  the destination stays bright) while a departed row dims whole — the file's own
  comment argues for the second and the code does not do it for the first. Same
  scope call.

---

## 6. ⚠️ Android divergence, deliberate and recorded

`android/.../ui/util/BoardFallbackState.kt` still tests SIGNAL_LOST above the
closed-network windows, so an Android board whose app has been shut all night
still says "Live updates paused · Last refresh 5h ago" at 04:00.

Left divergent because the fix was found on the iOS widget and this work is
iOS-only. **It is a straight port:** move the SIGNAL_LOST test under the time
windows. Nothing else moves. Noted in the kdoc of the core file too.

---

## 7. Still open

**Nothing here is device-verified.** It builds and installs; no one has looked at
it.

Three layouts I am least sure of:
- **Small, `.removed`.** "Highbury & Islington" at 13pt bold is ~136pt against
  ~135pt usable, so it truncates almost immediately. Probably fine, right on the
  line.
- **Large, any empty state.** A 44pt mark and two lines centred in 345×357 may
  read as sparse.
- **Small, `.needsStation`.** The message wraps to three or four lines under the
  title. Calculated to fit, not seen.

**The overnight states are hard to force.** `LATE_NIGHT` needs London time
00:00–04:30, `EARLY_MORNING` 04:30–06:00. `SIGNAL_LOST` is reachable by leaving a
widget alone for six minutes during the day. `CONNECTING` shows on a station
whose board has not been written yet.

**Open the app once before judging the widgets.** The copy table only lands on a
board write; until then they fall back to the baked-in Swift defaults, which are
transcribed from `BoardFallbackDefaults` and say the same thing.

Also still unrun: the six widget-assignment checks in
`docs/SESSION_2026-08-17_WIDGET_STATION.md` §10.
