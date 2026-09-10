# Review: station settings, home promos, expand/collapse

Staff review of `5d2b336` — *"a home screen that stops advertising, and settings
that say which station"* — 34 files, ~2,150 added lines.

The corrections are folded into `5d2b336` itself, and were **verified on device**
(iPhone 11, staging) before it was packed.

The commit was sound in shape: the decision to name a board from the SAVED
SELECTION rather than from cached departures is right, the `BoardFocus.Kind`
split that fixed expand/collapse is the correct fix at the correct layer, and the
promo removals are clean with no dangling references (checked every removed
symbol, both Kotlin and Swift). What follows is what did not survive reading.

Everything below is applied. `:core:testDebugUnitTest` and `:composeApp:testDebugUnitTest` green (285 tests,
0 failures),
`:composeApp:compileDebugKotlinAndroid` and `:composeApp:compileKotlinIosArm64`
both clean.

---

## 1. Bugs fixed

### 1.1 A compass-bearing board hid the destination it was there to name
`core/src/commonMain/kotlin/util/BoardLabels.kt`

`detailLabel` suppressed the destination line whenever the direction's sole
destination equalled `directionTowards`, on the reasoning that a bus titled
"Towards Putney Bridge" going only to Putney Bridge has nothing left to say.
True for that case. The test was written against `directionTowards` rather than
against the title, and **`towards` is served for rail too** — where it loses to a
compass bearing in `directionLabel`.

So a Victoria line board came out:

| | before | after |
|---|---|---|
| title | `Southbound` | `Southbound` |
| detail | `All destinations` | `To Brixton` |

The row said "all destinations" about a direction with exactly one, because a
string the user never sees happened to match. `forBoard` now resolves the title
once and hands it down, and the repetition test asks whether **the title** names
the destination. Stated against the thing it is actually about, it cannot come
apart from `directionLabel` again when the precedence there changes.

Regression test added: `a bearing that does not name the destination still lists it`.

### 1.2 Re-saving a board wiped route text it had already resolved
`composeApp/.../selection/SelectionViewModel.kt`

`buildSelection` wrote the three new display fields straight off `dirOption`:

```kotlin
directionName = dirOption?.directionName.orEmpty(),
```

`dirOption` is null whenever that line's direction list is not loaded — an edit
save on a line the user never opened, or any save made while the directions fetch
was failing offline. Three blanks then went **over** text a previous save had
resolved, and the settings screen dropped back to TfL's raw "Inbound" for a board
it had been naming properly.

It did not self-heal on the way back either: the backfill runs from the settings
screen's `init`, and returning from the picker is an `ON_RESUME`, so the row
stayed wrong until the screen was left and re-entered.

`buildSelection` now takes the whole `existing: UserSelection?` instead of just
`knownStation: String?` — the caller already had it — and each field falls back to
what is stored. Blank is written only for a board that has nothing stored yet,
which is exactly the case the backfill exists for. One parameter now carries both
jobs it was doing separately.

### 1.3 The route backfill could revert a filter edit made while it was in flight
`composeApp/.../station/StationSettingsViewModel.kt`

`backfillDirectionDetail` snapshotted the stale boards, **awaited the network**,
then wrote `board.copy(...)` of those snapshots back. Inside that window the user
can reach the line picker, change a filter, and come back — the settings
ViewModel is still alive on the back stack, so its coroutine is still running.
The write-back then reverted the edit they had just made.

It now collects the resolved text keyed by `(station, line, direction)` and
applies it to the repository's rows **as they stand at write time**. A board
deleted or edited meanwhile is picked up as it is now, or not at all.

### 1.4 A pending expand/collapse request outlived the station it named
`composeApp/.../station/StationSettingsViewModel.kt`

`BoardExpansion` deliberately leaves a request PENDING when it names a station
the home screen cannot see, so one still loading is not dropped for being early.
A station that has just been **deleted** is the other reason a request goes
unmatched, and nothing cleared it — it sat in the map for the rest of the session
and would apply itself if the user added that station back (ids are TfL naptans,
so it can also be a station added on the same trip). Both delete paths now
withdraw their own request.

### 1.5 Expand/Collapse did nothing for a user with one station
`composeApp/.../summary/SummaryScreen.kt` — *raised during review*

Two rules combined into a dead setting:

```kotlin
stationIds.size == 1 -> stationIds.toSet()          // always expanded
stationGroups.size > 1 && !isCarousel               // no chevron
```

The settings screen offered Expanded/Collapsed, stored the choice, said "Applies
when you switch it to List" — and the home screen drew the card open regardless,
with no chevron to express the choice with either. The user with one station is
exactly the user most likely to go looking for why.

Both removed. A card in a list is collapsible however many stations are in the
list, and the stored choice is honoured. Checked the height budget for the new
`expandedCount == 0` case: `boardMaxHeight` clamps at `MIN_BOARD_HEIGHT` and
`primaryStationId` going null only affects a card that is collapsed anyway.

---

## 2. Loaders

*Raised during review.*

### 2.1 The delete was covered, the arrival was not
`LoadingOverlay` is a composable inside the screen that raises it, so it dies with
that screen. Right for work that finishes where it started; wrong for work that
**ends in a navigation**.

Deleting a station covered its own teardown — subscriptions torn down, widget rows
cleared, the selection table rewritten — and then popped itself, at which point
the overlay went with it and the user watched the home screen assemble: an empty
board list for a frame, the remaining cards re-flowing into the deleted one's
space, the pager clamping to page zero and scrolling back.

New `AppBusy` (`ui/common/AppBusy.kt`) — a signal held outside the composition,
same reason `BoardFocus` is a singleton: **the screen that raises it is not the
screen that resolves it.** The overlay is hosted in `App.kt`, at the app root:
above the whole `NavHost` so it spans the pop, and inside `StationlyThemeHost`
because it paints with theme colours. Hosting it inside `AppNavigation` would
have meant re-indenting 230 lines to wrap the `NavHost`, for a worse position.
The settings screen hands over before the pop; `SummaryScreen` clears it on entry.

Cleared by ARRIVING, not by a timer, which is what makes it self-limiting: every
path that raises it ends on the home screen, so the one screen that clears it is
the one screen every path ends on. There is no state in which it can be left
standing.

### 2.2 Seven weights of one mark
The app had seven hand-rolled `CircularProgressIndicator` configurations —
16dp/2dp in a dialog button, 18dp/2dp in the sign-out row, 20dp/2dp on verify,
22dp/2dp in the line picker, 28dp/2.5dp on selection, 32dp/2dp on login, 36dp/3dp
in the overlay — so the app appeared to be waiting in seven different ways
depending on where you were.

One `StationlySpinner`, with the **stroke derived from the size** (≈ 1/12, the
ratio the overlay already had) rather than passed alongside it. That is what makes
them one mark at different sizes instead of different marks. Size is the only knob.

### 2.3 One wait, told once
Sign-out and delete-account each raised the full-screen overlay **and** a spinner
inside the button underneath it. Two spinners for one operation is how the app came
to look like it was waiting on two different things. The inline ones are gone; the
button's job while the overlay is up is to be disabled, which it already was.

The remaining inline spinners are the ones that are genuinely not blocking — a
line row still fetching its directions, a step whose own content has not arrived,
a name save that leaves Cancel reachable — and each now says at its call site why
it is not an overlay.

---

## 3. Code quality

- **`runCatching { return … }`** in `directionOptions` — a non-local return out of
  an inline block that swallows `Throwable`. It works (the return compiles to a
  plain return, not an exception), but it reads as if a decode failure could
  silently skip the network fallback. Rewritten as `.getOrNull()?.let { return it }`.
- **A duplicated identity helper.** The backfill's rekey introduced a
  `Triple(station, line, direction)`, which is exactly `UserSelection.boardKey` —
  defined once in the model with a KDoc warning about it being rebuilt by hand at
  three call sites. Removed in favour of the real one.
- **`buildSelection(knownStation:)` → `buildSelection(existing:)`** — the caller
  already held the whole row and was passing one field of it; two responsibilities
  now travel on one parameter with one explanation.
- **Three KDoc references that do not resolve**: `[Target.animate]` (it is
  `kind`), `autoExpandFirstIncompleteLine` (no such function — the real reason the
  ordering matters is `clearLinePicks()`), and `StationSettingsScreen`'s own KDoc,
  orphaned by a second KDoc block landing between it and the function.
- **A doc that claimed a guarantee the code does not give**: `backfillDirectionDetail`
  said it "runs once per board and then not again for a fortnight". A board that
  cannot be resolved (a direction TfL has withdrawn) is retried on every visit —
  cheap, one storage read inside the dropdown cache's window, no network. Corrected
  rather than papered over: stamping `routeResolvedAt` anyway would be recording
  an answer we never got.

---

## 4. Confirmed sound (checked, not changed)

- **Removals are complete.** Every removed symbol — `hasHomeScreenWidget`,
  `MEMBER_SINCE` / `member_since`, `hasEverStarted` / `markStarted`,
  `showWidgetPromo` / `showDreamPromo`, `onOpenScreensaver`,
  `home_widget_installed` — has no live reference left in Kotlin or Swift.
  `HomeStateProbe` correctly survives; its two real consumers are unrelated to
  the promo it also fed.
- **`updateSelectionsInPlace` is not torn by a concurrent read.** `SqlStorage`'s
  selection methods are synchronous, so there is no suspension point between the
  `clearSelections()` and the re-inserts and no coroutine can observe the table
  half-written.
- **`openForStation(focusLine)` ordering is correct**, though not for the reason
  the comment gave: `onDropdownSelected("station", …)` runs `clearLinePicks()`
  synchronously, which nulls `_expandedLine`; `prefillPicksForStation` launches
  async but never touches it.
- **The header collapse does not recompose the screen per frame.** `headerT` is a
  `by` delegate read *inside* the `topBar` lambda, so only that scope invalidates;
  the outer scope subscribes to the `derivedStateOf` boolean, which moves twice
  per gesture.
- **`.sq` and `SqlStorage` agree.** The three new columns are passed by name, and
  the deferred-Android-migration list in the banner is current.

---

## 5. Known and accepted

**Route text does not cross devices.** The cloud board model (`Board` /
`BoardSelection`) carries no `directionName` / `directionDestinations` /
`directionTowards`, so a fresh install restoring from Firestore gets boards
labelled by `compassFallback` until the settings screen backfills them. That is
the documented design and the backfill handles it; adding the fields to the wire
model is a backend contract change and out of scope for this commit.
