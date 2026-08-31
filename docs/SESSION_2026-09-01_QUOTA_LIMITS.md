# Session 2026-09-01 — SDUI Quota & Limits

**Branches:** `StationlyUI@ios-parity`, `stationly-backend@dev_13Jul`
**Outcome:** feature complete, built and installed on Nick's iPhone, **not yet
verified on device**, backend **not yet deployed**.

---

## 1. What the feature is now

**Two limits. There is deliberately no third.**

| # | Limit | Default | Counted in | Refusal |
|---|---|---|---|---|
| 1 | Stations per user | 4 | distinct station hubs (`UserSelection.groupingId`) | error haptic + "Station Limit Reached" modal |
| 2 | Lines per station | 4 | distinct line ids on one station card | error haptic + "Line Limit Reached" modal |

Limit 1 counts **hubs, not saved rows**, because Home draws one card per hub.
Two lines at King's Cross plus both directions at Victoria is two stations, not
four.

**Directions are unlimited.** A line runs inbound and outbound and nothing else,
so limit 2 already bounds a station card at 8 rows on its own — the exact value
of the row cap that used to exist. A ceiling counted in rows could therefore only
ever fire *before* the line limit, refusing three lines with both ways ticked: a
board limit 2 calls legal.

> **Residual risk, flagged not fixed.** Nothing structurally enforces "2
> directions per line". That is a property of the backend's direction feed, not
> of the client, which renders `directionsByLine` generically and would happily
> draw five. If that feed ever returns more than two directions for a line, a
> station can exceed 8 rows with no gate anywhere.

### The complete SDUI surface — seven keys

```
limits.boards.max                = "4"
limits.boards.reached.title      = "Station Limit Reached"
limits.boards.reached.message    = "You have used your full quota of 4 stations. …"
limits.boards.reached.cta        = "Got it"                # shared by both modals

limits.lines_per_board.max       = "4"
limits.lines.reached.title       = "Line Limit Reached"    # added this session
limits.lines.reached.message     = "Maximum of 4 lines reached for this station. …"
```

Every key has a compiled default, so a cold or offline launch enforces the same
two limits with the same copy. A blank served value is treated as absent, not as
an empty string, so a bad config cannot produce a blank modal.

`limits.boards.*` predates the vocabulary settling on "station". The key says
boards, the concept is stations, and they are the same thing: one board per hub.
Not renamed — the key is the contract.

### Where each limit is enforced

| Limit | Point | Why there |
|---|---|---|
| Stations | `SummaryViewModel.onAddBoardClicked` | Refuses the `+` **before** it becomes `navigate("selection")`. Once navigation runs, the screen that would host the modal is gone. This was the one correct observation in the original handover. |
| Stations | `SelectionViewModel.onDropdownSelected("station", …)` | The last point where refusing is free, before lines are picked. Runs before any state is mutated. |
| Stations | `SelectionViewModel.saveSelection`, after `repoReady.join()` | The same question once the repository has settled; the last gate before rows reach SQL. The station step must answer before the repo has loaded, this one need not. |
| Lines | `toggleLine`, `toggleAllLines` | `toggleAllLines` fills **up to** the cap rather than refusing the whole gesture — at a six-line interchange a "Select all" that did nothing because six exceeds four reads as a broken button. |

A hub the user **already holds** always passes, or a user at the cap could never
edit the boards they have.

---

## 2. Root cause of "the limits still don't work"

**The device was running a two-hour-old XCFramework.** The feature was never
broken.

```
quota source                       written 23:37–23:41
composeApp.xcframework/ios-arm64   built   21:28   <-- what Xcode linked
iosApp.app                         built   23:42   <-- BUILD SUCCEEDED
```

`strings` on that framework matched zero of the new symbols. `iosApp` links
whatever binary sits in `composeApp/build/XCFrameworks/debug/`; nothing in the
Xcode build graph rebuilds it, and `xcodebuild` reports success either way.

Both reported symptoms were the **pre-change behaviour**, which is what made the
misdiagnosis so convincing:

- *"`+` still navigates."* `onAddStation` was wired straight to
  `onNavigateToSelection`. There was no guard in the running binary.
- *"Directions only vibrate, no dialog."* `claimRowBudget` had fired
  `HapticType.ERROR` on refusal since long before this work. The old haptic was
  read as proof the new code was running and only the modal was broken.

Three UI approaches — `ModalBottomSheet`, `AlertDialog`, and an in-tree overlay —
were each abandoned as "failed on iOS". **None of the three ever executed.** The
third was correct all along and is what ships.

### The check that prevents a repeat

```bash
./gradlew :composeApp:assembleComposeAppDebugXCFramework   # before every xcodebuild

B=composeApp/build/XCFrameworks/debug/composeApp.xcframework/ios-arm64/composeApp.framework/composeApp
ls -la "$B"                          # newer than your sources?
strings -a "$B" | grep -c linesLimitTitle
```

Pick a symbol that survives compilation — a `data class` property or a public
function. Private functions and objects are not emitted, so grepping one returns
0 on a framework that definitely contains it.

---

## 3. Bugs fixed

**B1 · Refusing an over-quota station destroyed the user's line picks.** *High.*
`SelectionViewModel.onDropdownSelected` — the quota gate sat **after** the
`when (componentId)` block, which calls `clearLinePicks()` and wipes `_linePicks`,
`_boardFilters`, `_directionsByLine` and `_expandedLine`. Pick a station, choose
three lines, navigate back, tap a fifth station: the three lines were destroyed,
*then* the refusal appeared. The user lost work to a rejected action. The same
path also fired `TAP` at the top of the function and `ERROR` on refusal, so one
rejected tap buzzed twice in two different vocabularies.
**Fix:** the gate is the first thing after the blank-value check, before the
haptic and before any state is touched. A refusal is now inert.

**B2 · "Select all" never auto-ticked a one-way line.** *Medium. Pre-existing,
predates this feature.* `toggleAllLines` called `autoSelectSoleDirection` while
`picks` was still a local copy. That routes through `toggleDirection`, whose first
statement is `val current = _linePicks.value[lineId] ?: return` — the read
returned `null` and the auto-tick silently no-opped. A line with a single
direction, ticked via "Select all", was left with an empty direction set, so
`isSelectionComplete` stayed false and the CTA stayed disabled with no visible
reason.
**Fix:** commit `_linePicks.value` first, then resolve directions for the lines
added. `_expandedLine` now derives from the settled map, not the stale local one.

**B3 · The modal's enter and exit animations never ran.** *Low.*
`QuotaLimitOverlay` wrapped its body in `AnimatedVisibility(visible = true)` while
the call site wrapped the whole composable in `if (flag)`. A node that enters
composition already visible plays no enter transition, and one removed the instant
the flag clears plays no exit. Both transitions existed in the source and never
appeared on screen.
**Fix:** `visible` is a parameter and the call sites render unconditionally. Scrim
fades, card fades and scales.

---

## 4. Reverted from the previous attempt

All three would have shipped.

- **`runCatching` around most of both ViewModels' `init` bodies.** Added so that
  unit tests instantiating real ViewModels would not crash on `Platform`. It
  swallowed every coroutine failure in production: a failed
  `selectionRepository.initialize()` would have left the board permanently empty
  with no crash, no log, and no way to tell.
- **`selections.size >= maxBoards` as a second gate.** Explicitly requested by
  the handover, and a bug: it counts rows, so it would have told a user with two
  hubs they were full at two.
- **A global `QuotaAlertManager` above `NavHost`, plus a synchronous main-thread
  `SqlStorage.getAllSelections()` at the navigation boundary.** Both were fixes
  for a race that was not happening. The tap is refused *before* it navigates, so
  there is nothing for a global overlay to survive.

---

## 5. Removed

**The row cap, entirely** (product call this session): `MAX_ROWS_PER_STATION`,
`claimRowBudget`, `isAtCap`, `showRowLimitDialog`, `RowLimitSheet`,
`BoardQuota.canAddRows`, `BoardPolicy.maxRowsPerStation`, `rowsLimitMessage`, and
the two backend keys.

**`PickSummaryBar`**, the pinned bottom bar on the line step. Pre-existing (it was
at HEAD), but it overlapped the line list once pinned and its unit was wrong: it
read "8 Boards" for what is one station board. Removed with it — `lineLimitNotice`
and its whole thread through the screen, `pickedRowCount` (no other reader), and
the 120dp bottom content padding that existed only to clear the bar (back to the
original 2dp). The line step now states the quota in exactly two places: the
`n / max` counter beside the "Lines" header, and the modal on a refused tap.

**Dead state and duplication:** `pickedLineCount` and `isAtLineCap` (two eagerly
collected `stateIn` flows per screen instance that nothing read),
`SummaryViewModel.boardCount` / `canAddBoard` (unread getters), a redundant
`onAddBoardClicked` parameter that passed the very flow the function reads, 9 dead
imports left by the abandoned sheet attempts, `println("🚨 …")` debug spam, and an
in-list banner that repeated the summary bar's sentence verbatim.

---

## 6. Structure and quality

**`core/config/BoardQuota.kt` (new)** — the two predicates as pure functions over
a `BoardPolicy`, taking the policy explicitly so a test can state the limits it is
testing rather than mutating global state:

```kotlin
fun stationCount(groupingIds: List<String>): Int
fun canAddStation(groupingIds: List<String>, candidate: String? = null, policy: BoardPolicy = …): Boolean
fun canAddLine(pickedLines: Int, policy: BoardPolicy = …): Boolean
```

Asked from four call sites, three of which live in a ViewModel that cannot be
constructed without a `Platform` behind it. Answered here, the rules are testable
alone and the home screen and selection flow cannot drift about what "full" means.
`canAddStation` began as two overloads; collapsed to one with a nullable
`candidate` — `null` asks the general "is there a free slot", which is what the
`+` needs, having no candidate yet.

**DRY** — `SummaryScreen`'s support banner counted hubs with its own
`selections.map { it.groupingId }.distinct().size`; it now calls
`BoardQuota.stationCount`, so it cannot drift from the quota.

**Theming** — `QuotaLimitOverlay` hardcoded `Color(0xFF141414)`, `Color.White`,
`Color(0xB3FFFFFF)` and `Color(0x33FFFFFF)` in an app with a full SDUI token set.
Now `t.cardElevated`, `t.textPrimary`, `t.textMuted`, `t.borderSubtle`, `t.scrim`,
so it follows light/dark and any served palette override.

**SDUI completeness** — `LineLimitSheet` hardcoded its title, the one hardcoded
string in a surface specified as 100% SDUI. Added `BoardPolicy.linesLimitTitle`
reading `limits.lines.reached.title`, and the backend now serves it. The
`.ifBlank { "Got it" }` guards at the call sites were removed as dead:
`RemoteConfig.text` already treats a blank served value as absent.

---

## 7. Tests

`composeApp/commonTest/{SelectionLimitTest, SummaryLimitTest}.kt` **deleted** —
they constructed real ViewModels, which is what forced the `runCatching` damage in
§4. Every other test in that source set is pure-logic; these were the outliers.

Replaced by `core/commonTest/config/BoardQuotaTest.kt` (8 tests): station counted
per hub not per row, blank grouping ids dropped, the fifth station refused, an
already-held hub always allowed, a null candidate refused when full, served limits
overriding compiled ones, the line cap.

`BoardPolicyTest` updated for the removed keys, with a test asserting a served
`limits.rows_per_board.max` is now **ignored** — so a future edit that re-reads it
fails loudly rather than silently resurrecting the ceiling. `src/tests/run.ts`
asserts both row keys are `undefined` in the payload, for the same reason.

```
:core:testDebugUnitTest             BoardQuotaTest 8/8 · BoardPolicyTest 23/23 · 0 failures
:composeApp:compileKotlinIosArm64   BUILD SUCCESSFUL
stationly-backend  npm test         210/210
```

---

## 8. Corrections made during this session

Recorded because both were stated confidently and both were wrong.

1. **"Staging returned an empty response."** I probed
   `http://79.72.94.209:3000/api/v1/sdui/app/home-config`, the wrong host and
   port, and concluded staging was unreachable. It is up and healthy at
   `https://staging-api.stationly.co.uk`, which answers `401` without an
   `X-Stationly-Key` header and `200` with one.

2. **"The row keys were never deployed."** I inferred this from `git log -S`
   finding no commit and `sduiService.ts` being uncommitted, and did not check the
   running server. **They are live on staging right now** — the file was deployed
   from a dirty tree. Deleting them is still correct, but for a different reason:
   the additive-only rule protects keys a *shipped* client reads, and no shipped
   client reads these. Android is frozen at versionCode 2 and predates them; iOS
   has never been released. Staging is not "shipped".

---

## 9. Outstanding

- **Device verification.** Installed on Nick's iPhone; open it by tapping the
  icon (a `devicectl` launch breaks the Keychain read and lands you signed out).
  Test: `+` with 4 stations; a 5th line at an interchange; "Select all" at a
  six-line station, which should fill to exactly 4 and say why.
- **Backend not deployed.** `.scripts/staging_deploy.sh` builds, rsyncs and
  `pm2 reload`s the live staging host. Until it runs, staging keeps serving the
  two row keys (harmless — no client reads them) and does not yet serve
  `limits.lines.reached.title` (harmless — the client uses its compiled default,
  which is the same string).
- **Both repos uncommitted.** See §10.

---

## 10. Change inventory

**StationlyUI** (`ios-parity`)

```
M  composeApp/.../ui/selection/SelectionScreen.kt        line quota UI, PickSummaryBar removed
M  composeApp/.../ui/selection/SelectionUiState.kt       showRowLimitDialog removed
M  composeApp/.../ui/selection/SelectionViewModel.kt     B1, B2, gates, row cap removed
M  composeApp/.../ui/summary/SummaryScreen.kt            + gated, dead imports, DRY count
M  composeApp/.../ui/summary/SummaryUiState.kt           showStationLimitDialog
M  composeApp/.../ui/summary/SummaryViewModel.kt         onAddBoardClicked
M  core/.../config/BoardPolicy.kt                        two limits, linesLimitTitle
M  core/.../config/BoardPolicyStore.kt                   resolve the two limits
M  core/src/commonTest/.../BoardPolicyTest.kt            row keys ignored
M  docs/CONFIG_KEYS.md                                   regenerated
A  composeApp/.../ui/summary/components/StationLimitSheet.kt   QuotaLimitOverlay + 2 sheets
A  core/.../config/BoardQuota.kt                         the predicates
A  core/src/commonTest/.../BoardQuotaTest.kt             8 tests
A  docs/SESSION_2026-09-01_QUOTA_LIMITS.md               this file
```

Three files were removed and appear in no diff, because the previous session had
left them **untracked** — git never held them:
`composeApp/src/commonTest/kotlin/SelectionLimitTest.kt`,
`SummaryLimitTest.kt`, and `docs/LIMITS_AND_QUOTA_INVESTIGATION.md`.

**stationly-backend** (`dev_13Jul`)

```
M  src/services/sduiService.ts       row keys deleted, lines.reached.title added
M  src/tests/run.ts                  asserts row keys absent
A  docs/LIMITS_AND_QUOTA_SPEC.md     two-limit schema
```
