# Promos out, profile trimmed, station settings rebuilt — session log

**2026-08-23.** iOS only. `composeApp`, `core`, `iosApp`, plus one schema change.

**252 tests, 0 failures.** `:core` and `:composeApp` compile for `iosArm64`,
`:core:compileDebugKotlinAndroid` and `:android:app:compileProdReleaseKotlin`
green. Built, signed and installed on the iPhone 11 against staging.

Android is untouched throughout. Every `core` change is additive with defaults,
and `:android:app` does not depend on `:composeApp` — see `IOS_HANDOVER.md` §1.

---

## 1. The one that shipped broken, and what it cost

Two columns were added to `UserSelectionEntity` and the build was deployed with
no migration. **The app crashed on launch**: `Schema.create` applies the `.sq`
only to an EMPTY database, every device already had one, and the home screen's
first read hit `no such column: directionName`.

This is the exact hazard spelled out in the banner at the top of
`StationlyDatabase.sq` — a banner edited in the same change without acting on it.

**The fix was measured, not guessed.** The database was pulled off the device
before anything was written:

```
PRAGMA user_version                 -> 1
UserSelectionEntity                 -> complete through patternNames
PredictionEntity.viaKey             -> present
ActivityEventEntity                 -> present
```

So exactly two columns were missing. `migrations/1.sqm` adds those two, taking
the schema to v2. Applied to a copy of the real database first: **20 boards
preserved**, both columns present, launch query clean.

### How it was resolved, and where it landed

Migrations were reinstated the same day to rescue the install, and then **removed
again** once the release posture was settled: Android is live but frozen, iOS v1
has never shipped, and the only databases in existence are on development
devices. Carrying `.sqm` files for a schema that moves daily, with no reader, is
overhead.

**The policy is now: change the schema, delete the app, reinstall.**
`Schema.create` applies the `.sq` in full to an empty database, so a fresh
install is correct by construction. Installing *over* an app keeps its container
and its old database, which is precisely what caused the crash.

Android's gap is unchanged and deferred. It is one migration whenever Android is
picked back up, written against the last released build as a table rebuild rather
than a list of `ALTER`s. The list it must cross lives in the `.sq` banner and is
kept by hand. See `PENDING_BRANCH_WORK.md`.

## 2. Bugs fixed

Ordered by how wrong the user-visible result was.

### B1 · "Default view" could never take effect on return — the feature was dead
The home screen holds which stations are open as session state
(`expandedIdsState`), which outranks the stored default once the user has touched
a chevron. Changing the setting therefore had to be applied explicitly.

The first attempt inferred it: remember every station's `expanded` flag, react
when one moved. **It could not fire.** `SummaryScreen` leaves composition while
the settings screen is open, so a plain `remember` holding the previous values is
gone by the time the user returns — the reconciler sees its own first read, hits
the "first read is not a change" guard, and records the change instead of
applying it. The single path the feature existed for was the one path it could
never work on.

**Fix:** `BoardExpansion`, a signal raised by the ViewModel and consumed by the
screen. It lives outside the composition, which is the whole point — the same
reason `BoardFocus` is a singleton. A map rather than one slot, because two
stations can be waiting; consumed only for the keys actually applied, so a
request for a station still loading is held rather than dropped.

### B2 · The Expanded/Collapsed setting was invisible on the Carousel layout
Pre-existing (`if (homeLayout != HomeLayout.CAROUSEL)`, in `HEAD` untouched), and
reported as "it's gone". True that a station with a page to itself has nothing to
collapse; still the wrong call, because a user who went looking for a setting
found the section simply absent with nothing to say why.

**Fix:** shown on both layouts, with a line stating that the home screen is a
Carousel and the choice applies when it is switched to List. A setting not in
force is one to explain, not one to hide.

### B3 · Coming back from a station's own screens landed on the wrong station
Editing station C's settings in a four-station carousel and tapping back returned
to station A. Two separate causes:

- The pager's saved state is not enough: the summary re-reads its repository on
  resume, and on any frame where the list is momentarily empty both the pager and
  the scroll position clamp to zero.
- Saving in the line picker rebuilds the summary destination outright
  (`popUpTo … inclusive`), discarding everything.

**Fix:** `BoardFocus.restore()` — the existing focus mechanism, with a new
`animate = false`. A widget tap still slides, because the user is watching the
app arrive; a restore snaps, because the user believes they never left. Raised on
back from settings and on save from the picker, but **not** for a newly added
station, which goes to the top of the home screen and is already the first card.

### B4 · The direction rows described cached predictions, not the saved board
`BoardLabels.forBoard` took a `towards` string derived from whichever destination
appeared most often in the SQLite prediction cache. Consequences, both seen:

- A board saved as plain "Inbound" — every destination — was labelled "Towards
  Ealing Broadway" because most cached trains happened to end there. It reads as
  a filter the user never set.
- The same board said different things on different days, and nothing at all
  before its service had run.

**Fix:** every label now comes from the saved selection. Title is the direction,
detail is what that direction shows. The `_towards` flow and its per-board
grouping pass are deleted.

### B5 · "Inbound" and "Outbound" reached the screen at all
TfL's raw direction is an operational fact about the network, not about anyone's
journey. The picker never showed it — the backend computes a compass name in
`getCompassDirection` (`lineController.ts`) and serves it as
`SduiDropdownOption.directionName` — and **we saved only the raw id and discarded
the rest.**

**Fix, three parts:**

1. `UserSelection` gains `directionName` and `directionDestinations`, populated
   from the direction option at save time.
2. Existing boards have neither, so `StationSettingsViewModel` backfills them,
   resolving the dropdown URL exactly as the picker does so it hits the picker's
   own 24-hour cache and usually costs no network. Once per board; silent on
   failure.
3. `BoardLabels.compassFallback`, a small table mirroring the backend's, so a
   board reads "Southbound" instantly and offline before any backfill lands.
   **Documented as a mirror, not a source: if the two disagree, the backend is
   right.**

### B6 · "All destinations" where the destinations were knowable
An unfiltered board said nothing useful. It now names them — "To Richmond and
Ealing Broadway" — with `EVERY_DESTINATION` demoted to a last resort for a board
whose route data has never been stored or backfilled.

### B7 · The per-board delete dialog could not identify its board
It read "Remove Victoria?". That row is one *direction* of a line, and a line
tracked both ways gives two rows — so on the one dialog where being wrong deletes
the other board, the user could not tell which was which. Now "Remove Victoria
southbound?", matching the icon's own accessibility label.

### B8 · The header hopped on the final frame of its own animation
The collapsing header swapped `Modifier.weight(1f, fill = false)` for `Modifier`
at exactly `collapse == 1f`, re-measuring the row on the last frame — a visible
jump precisely where the eye had followed the movement to. The weight is now
unconditional.

### B9 · The collapse threshold was a different distance on every device
`HEADER_COLLAPSE_PX = 12` was raw pixels, roughly 3× tighter on a 3× screen than
on the 1× one it was chosen against. Now `6.dp`, converted at the call site.

### B10 · A pending expansion request outlived sign-out
It names a station id, and ids are TfL naptans — shared across accounts. Left
behind, it would apply to whoever signed in next if they tracked the same
station. `BoardExpansion.clear()` on both the sign-out and account-deletion
paths.

### B11 · The About card's version was a literal
`APP_VERSION = "1.0"` was hardcoded and rendered as `v1.0` while
`appVersionName()` sat unused reading the real `CFBundleShortVersionString`. It
would have lied on every TestFlight build.

### B12 · The profile card announced a date it did not have
"Since Recently" was the fallback string showing through: `member_since` is only
written when Firebase reports a creation date. The provider chip next to it put a
mail glyph beside the word "Apple", because Material has no Apple mark and the
code branched only on Google. Both chips removed.

### B13 · Dead copy that read as live copy
The layout picker passed **three** caption variants to a two-value enum, and
`selected` is an index into `BoardView.entries` — so the third could never
render. Left behind when a third view was removed.

---

## 3. Removed: the two home-screen promos

Both were ports of Android nudges that do not survive the crossing.

- **"Add a home screen widget"** asked for a gesture iOS gives the app no way to
  help with. There is no API that places a widget, so the card shipped with no
  CTA and could only recite Home Screen instructions at someone who had not
  asked.
- **"Set as Screensaver"** advertised a surface iOS has no system slot for. On
  Android the dream is chosen in system Settings and the promo takes you there;
  here it is an ordinary in-app screen reachable from home settings like every
  other setting. A banner for one row of the settings screen is an advert.

Gone with them: both ViewModel flows and their two foreground re-checks, the
`hasHomeScreenWidget()` expect/actual seam and all three actuals, the
`home_widget_installed` App Group key, `DreamSettings.hasEverStarted()` /
`markStarted()` and their `ever_started` key, `SummaryScreen`'s now-unused
`onOpenScreensaver` parameter, and the dead `member_since` write in
`AuthBridge.swift`.

**`HomeStateProbe` stays, and must.** It looked like promo machinery and is not:
the activity trail derives `widget.added` / `widget.removed` / `widget.count`
from its snapshots, and the extension's refresh ledger reaps deleted widgets
against it. Its doc was written entirely around the promo and has been re-headed
around the two consumers that were always the load-bearing ones.

Kept on the home screen: the announcement banner and the notification-denied
banner. Both are things a user can act on.

**Nothing records "has this device ever run the dream" any more.** If that is
wanted it belongs in the activity trail beside `settings.dream_changed`, not in a
preferences store — the trail is where "what do people actually use" is answered,
and a boolean in dream prefs could only ever answer it for one phone.

---

## 4. Performance

| Fix | Was |
|---|---|
| The route backfill writes **once** (`updateSelectionsInPlace`) | Every replacement rewrites the whole selection table — `clearSelections()` then a re-insert of every row, because insertion order IS list order. One call per board made a four-board station cost four clears and eighty inserts on a twenty-board account, to change eight fields |
| Header collapse is a **two-state animation** | A scroll-offset-driven transform would recompose the header on every frame of every scroll, for a header only ever read at one end or the other |
| Header sizes are **interpolated**, not `graphicsLayer`-scaled | A scaled font is a soft font, and this header comes to rest small |
| `appVersionName()` read once | A bundle lookup on every recomposition of the About card |
| `_towards` flow deleted | A grouping-and-counting pass over every board's cached predictions on every load, to produce a label now taken from the saved row |
| Two promo flows and their foreground re-checks deleted | `checkWidgetPromo()` + `checkDreamPromo()` ran on init and on every resume |

---

## 5. Code quality

- **One source for a board's name.** `BoardLabels` reads the saved selection and
  nothing else, with the reasoning pinned in its own kdoc: *do not reintroduce a
  departures-derived label here.*
- **`updateSelectionInPlace` delegates to `updateSelectionsInPlace`** rather than
  keeping two copies of the match-and-rewrite loop.
- **`BoardView.label` is what the tiles render.** The comment above them claimed
  "the labels are the enum's own"; the code hardcoded `"Next dept. + board"`, an
  abbreviation of a label already sitting unused on the enum. Two names for one
  view, and the shorter was the one people read. `FULL` is now "Next departure" —
  the drawing beneath it already shows the board.
- **Dead imports** left by the header rewrite removed (`mutableIntStateOf`,
  `onSizeChanged`, `draw.alpha`).
- **The copy pass**, in full:

| Was | Now |
|---|---|
| Show up to 5 per stop | Up to 5 departures per stop |
| A quiet stop shows fewer — TfL sends what it sends. Applies once the station is open. | A maximum, not a target: a quiet stop shows fewer. Applies to the full board, not the collapsed card. |
| Default view | How this station opens |
| Opens showing just the next departure each way. Tap it for the board. | Shows the next departure each way. Tap the card for the full board. |
| Layout | What the card shows |
| Next dept. + board | Next departure |
| No countdown. Those rows go back to the board as real departures. | No countdown above the board. That space becomes more departures. |
| Lines, directions and filters | Add a line, change a direction, or set a filter |
| Remove *(section)* | Delete |
| Removes its board and stops updates | Its board and its live departures both go |
| Every platform Victoria calls at leads the board. | Every platform the Victoria calls at moves to the top. |
| Nothing pinned. Whichever platform has the soonest departure leads… | Nothing pinned. The platform with the soonest departure comes first. |
| 2 boards *(per line)* | 2 directions |

"Show up to 5 per stop" never parsed on first read: *up to 5 of what*, and *per
stop* attached to nothing. The noun has to be in the sentence.

**Expanded / Collapsed was left alone.** "Open / Closed" is plainer English, but
"Closed" beside a station name reads as the station being closed, which is the
one misreading a departures app cannot afford.

---

## 6. Deliberately not changed

- **A bus board still titles as Inbound/Outbound.** TfL publishes no bearing for
  a route and the backend returns the literal `"Towards"` as its direction name,
  which is not a direction. The destination line beneath carries the meaning.
  Making the title "Towards Putney Bridge" means storing the option's `towards`
  as a third column — worth doing, not done here.
- **`BoardExpansion` lives in `ui/summary`** and is raised from `ui/station` and
  `ui/profile`. Slightly awkward direction, and it follows `BoardFocus`, which
  sits in the same package and is raised from the platform URL callback. Moving
  both to a neutral package is a separate change.
- **The two new columns do not sync.** They are display metadata re-derivable
  from route data, and `sanitiseBoards` on the backend is an allow-list — adding
  fields there is backend work. A board restored on a second device falls back to
  the compass table and backfills on first visit to its settings.

---

## 6b. The second half of the session

Work that landed after §1–§6 were written, in the order it was asked for.

### The Expand/Collapse saga — three wrong diagnoses, and the actual cause

Reported broken four times. Worth recording in full, because each attempt fixed a
real defect and none of them was the one that mattered.

| # | What I believed | What was true |
|---|---|---|
| 1 | The setting needed applying on return | Correct, but the reconciler compared against a `remember` that `SummaryScreen` discards when it leaves composition. It saw its own first read and recorded the change instead of applying it. **Could never fire.** |
| 2 | It needed a signal outside the composition | Correct, and `BoardExpansion` was built. Still nothing moved. |
| 3 | The picker guarded the tap | Correct and real: `if (it != startExpanded)` compared against the stored DEFAULT while the card shows the SESSION. Tapping "Collapsed" on a board already stored as collapsed dispatched nothing. Fixed. Still nothing moved. |
| 4 | — | **`BoardFocus.restore()`, added earlier the same day, force-expanded the station on the way back.** Declared after the expansion effect, so it always won. |

The last one is the lesson: two features added in one session, each correct
alone, cancelling out. Nothing in either file mentioned the other. The fix names
the distinction that was implicit — `BoardFocus.Target` now carries a `Kind`:

- **`REVEAL`** (widget tap) animates the page and may open the card. The user
  tapped a board; a collapsed card is not what they tapped.
- **`RESTORE`** (returning from that station's own screens) snaps, and **touches
  nothing else**.

`BoardFocusTest` pins the distinction so it cannot quietly regress.

### Buses are named by where they go

Eight of the thirteen lines on the test account are buses, and the compass work
in §2/B5 did nothing for them: TfL publishes no bearing for a route, so the
backend returns the literal `"Towards"` as the direction name.

New `directionTowards`, stored at save and backfilled, gives them the picker's
own headline — "Towards Putney Bridge". `detailLabel` suppresses the destination
line when it would only repeat the title back.

### A direction row is the way into its own filter

The rows were inert, and the only route to a board's filter was "Add or edit
lines", which reopens the whole picker on a collapsed list. Tapping a row now
opens the picker already expanded on that line, through the `expandedLine` state
that already existed. **No filter state was duplicated** — the sheet still lives
in `SelectionViewModel`, which is the only place that resolves a filter.

### `routeResolvedAt` finally has a reader

It was written and never read, which mattered little when the resolution was a
list of ids nobody displayed. It matters now that those fields are text on the
screen: a renamed destination would have sat there indefinitely. The backfill
re-resolves anything older than a fortnight, through the same cache-first path,
so the common case is still no network.

### The delete is covered

Deleting tears down subscriptions, unsubscribes push topics, clears widget rows
and rewrites the selection table, then pops the screen. Uncovered, that read as
the page juddering — rows vanishing one at a time, the list reflowing, the header
resizing as the line tags went.

`LoadingOverlay` now covers it, held up through `deleted` as well as
`isDeleting`: the work finishes slightly before the pop lands, and dropping it in
that gap shows the half-emptied screen, which is the thing it exists to hide. It
swallows gestures, so a second tap cannot race the teardown.

### Review pass findings

| Finding | |
|---|---|
| **The route backfill rewrote the whole table once per board** | Every replacement is `clearSelections()` plus a re-insert of every row. Four boards on a twenty-board account cost four clears and eighty inserts to change eight fields. `updateSelectionsInPlace` does it in one write; the single-board form delegates to it |
| **The collapsing header hopped on its final frame** | It swapped `weight(1f, fill = false)` for `Modifier` at exactly `collapse == 1f`, re-measuring the row on the last frame of the animation |
| **The collapse threshold was device-dependent** | A raw pixel constant, roughly 3x tighter on a 3x screen. Now `6.dp`, converted at the call site |
| **A pending expansion request outlived sign-out** | Station ids are TfL naptans, shared across accounts. `BoardExpansion.clear()` on both sign-out paths |
| **`appVersionName()` ran per recomposition** | A bundle lookup inside the About card |
| **`.lowercase()` on a direction that now contains a place name** | "Remove Bus 39 towards putney bridge?" — on the one dialog that deletes something. `BoardLabels.directionPhrase` lowercases a single-word bearing and leaves anything with a space alone |
| Dead imports from the header rewrite | `mutableIntStateOf`, `onSizeChanged`, `draw.alpha` |

### Identified, then done in this same commit

Both were written up as outstanding and both were closed before it was packed.
Left here because the reasoning is the record of why they matter:

- **`sduiApi.getSelectionLayout()` in the backfill was a network call for a
  string already on disk** (`cached_app_layout`, written by
  `SelectionViewModel`). A board that can never resolve re-fetched the whole
  layout on every settings visit. `directionUrlTemplate()` now reads the cache
  first and falls through to the API only on a miss.
- **`BoardFocus` had no `clear()`**, so unlike `BoardExpansion` a pending focus
  survived sign-out. It has one, called from both sign-out paths.

## 7. Verification

**Seen on device (iPhone 11, staging) on 2026-08-24**, after the review pass in
§8 — the settings screen, the expand/collapse round trip, the delete cover and
the direction rows all behave. What follows is the list that was outstanding
before that, kept because it is what was actually looked at:

1. The header collapsing and centring, on a short name and a long one.
2. Expanded → Collapsed → back, and the reverse, on the List layout.
3. Back from settings and from the line picker landing on the right carousel page.
4. The direction rows after backfill. The test account is mostly buses (39, 30,
   214, 205, 1, 17, 180, 161), which keep their operator word by design — the
   rail boards are where the change shows: Circle inbound → **Clockwise**,
   Metropolitan outbound → **Eastbound**, Northern inbound → **Southbound**,
   Piccadilly outbound → **Northbound**.
5. The backfill itself on a cold dropdown cache, which is the only path that
   reaches the network.

**No test covers `BoardExpansion`, the backfill or `updateSelectionsInPlace`.**
`core`'s suites are pure functions over models; a repository test needs a fake
`SqlStorage` that does not exist yet. `BoardLabels` is covered — 24 cases,
including the fallback table's per-line exceptions, the bus case that
deliberately declines, and the rail case §8 added.

---

## 8. Review pass, 2026-08-24

A full re-read of this commit before it was finalised. Five bugs, four of them
introduced by the work above. Findings and reasoning in
`REVIEW_2026-08-24_STATION_SETTINGS.md`; corrections folded into this commit.

| | Fix |
|---|---|
| **A compass bearing hid the destination it was there to name** | `detailLabel` tested the sole destination against `directionTowards` instead of against the TITLE. `towards` is served for rail too and loses to a bearing, so "Southbound / To Brixton" printed as "Southbound / All destinations" — a string the user never sees deleting the one they came to read |
| **Re-saving a board wiped route text it had already resolved** | `buildSelection` read the three new fields straight off `dirOption`, which is null on an edit save for a line the user never opened, or any save made offline. It now takes the whole `existing` row and falls back to what is stored |
| **The backfill could revert a filter edit made while it was in flight** | It snapshotted rows, awaited the network, then wrote the snapshots back. The settings ViewModel stays alive on the back stack, so a filter changed in the picker inside that window was silently reverted. Now keyed by `boardKey` and applied to the rows as they stand at write time |
| **A pending expansion request outlived the station it named** | The home screen leaves an unmatched request pending on purpose (a station may still be loading). A DELETED station is the other way a request goes unmatched, and nothing cleared it. Both delete paths now withdraw their own |
| **Expand/Collapse did nothing for a user with one station** | `stationIds.size == 1` forced the card open and `collapsible = size > 1` withheld the chevron, so the setting was stored, described, and ignored. Both removed |

### Loaders, in the same pass

`LoadingOverlay` belongs to the screen that raises it, so the station delete
covered its own teardown and then uncovered the home screen mid-assembly — cards
re-flowing into the deleted one's space, the pager clamping to page zero. New
`AppBusy`, hosted at the app ROOT in `App.kt` above the NavHost, raised before
the pop and cleared by `SummaryScreen` on entry. Cleared by *arriving* rather
than by a timer, which is what makes it self-limiting: every path that raises it
ends on that screen.

Seven hand-rolled spinner configurations (16/2, 18/2, 20/2, 22/2, 28/2.5, 32/2,
36/3) collapsed into one `StationlySpinner` whose stroke is DERIVED from its size
— that is what makes them one mark at several sizes rather than several marks.
The duplicate in-button spinners on sign-out and delete-account are gone; both
already sat under a full-screen overlay, which is one wait told twice.

### Also corrected

`runCatching { return … }` in `directionOptions` (a non-local return out of a
`Throwable`-swallowing block), a `Triple` identity helper that duplicated
`UserSelection.boardKey`, three KDoc references that do not resolve, and a doc
claiming the backfill runs "once per board" when an unresolvable board retries
every visit.
