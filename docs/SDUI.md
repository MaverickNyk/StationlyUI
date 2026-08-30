# Server-driven UI — the config channel

How Stationly's clients get their words, thresholds and rules from the backend
instead of the binary. One document: the mechanism, everything shipped, the
Android safety proof, what is deliberately NOT server-driven, and what remains.

Replaces three earlier documents — the migration plan, the audit and the change
review — which were folded into this one and deleted. Nothing was dropped in the
merge; if you are looking for any of them, it is here.
Backend companion: `stationly-backend/docs/SDUI_CONFIG.md`.
Live key inventory: `docs/CONFIG_KEYS.md` (generated — do not hand-edit).

Last updated 2026-08-31.

---

## Contents

1. [Ground rules](#1-ground-rules)
2. [The mechanism](#2-the-mechanism)
3. [The test a value has to pass](#3-the-test-a-value-has-to-pass)
4. [What shipped](#4-what-shipped)
5. [Android safety](#5-android-safety)
6. [The key inventory tool](#6-the-key-inventory-tool)
7. [Deliberate non-changes](#7-deliberate-non-changes)
8. [What remains](#8-what-remains)
9. [Mistakes, and the guards they produced](#9-mistakes-and-the-guards-they-produced)
10. [Verification](#10-verification)
11. [Commit and deploy](#11-commit-and-deploy)
12. [File map](#12-file-map)

---

## 1. Ground rules

Decided 2026-08-30. These outrank everything below.

### The payload is additive. No key is ever deleted.

An unread key costs bytes. A removed key that a shipped client still reads is a
blank string in production, and **Android is frozen at versionCode 2 with no
rollback** — there is no release to fix it with. Retire a key by documenting it
as unread, never by deleting it.

This rule exists because the first pass of the audit called 37 keys "dead
payload" when **30 were live reads in the production Android app**.

### Add keys freely for iOS.

Serving a key no client reads is harmless, so iOS work never waits on Android.

### iOS first. Android is phase 2.

Make iOS fully SDUI now. Bringing Android to the same level is a separate later
phase, explicitly not this one. "Does Android read this?" is therefore only ever
a question about **not breaking** Android, never about blocking an iOS change.

---

## 2. The mechanism

`GET /sdui/app/home-config` returns a flat `Map<String,String>`. It carries
strings, numbers encoded as strings, comma-separated lists, and one whole JSON
document (`support_money.card.json`).

```
backend  sduiService.getHomeConfig()
   │       (several services spread their own keys in)
   ▼
fetch    SummaryViewModel.fetchHomeConfig()   ← home screen
         LoginViewModel.loadStrings()          ← auth flow, first screen on a cold install
   ▼
cache    HomeConfigCache  →  ConfigKeys.HOME_CONFIG_CACHE_KEY
   ├────────────────────────────────────────────┐
   ▼                                            ▼
read     RemoteConfig.text/int/long/list    republish
         clamped, blank-is-absent           IosWidgetManager.publishFallbackCopy
                                                ▼
                                            App Group  →  widget extension
                                            (never fetches anything itself)
```

**Other SDUI endpoints**, unchanged by this work: `/sdui/app/theme-tokens`
(palette), `/sdui/app/refresh-policy` (widget refresh tiers),
`/sdui/app/{login,register,forgot-password,about,selection}` (screen layouts),
`/sdui/app/home-announcement`.

### Why the cache is read before the fetch

The auth screen is the FIRST thing a cold install shows. Waiting for a network
round trip would mean every new user sees compiled copy no matter what the
backend says, which is most of the reason for serving it. Cache first, network
second, and the network result seeds the cache for the home screen.

### Why the widget gets a copy, not a fetch

The extension is a separate process that never calls the network. Everything it
knows, the app republished into the App Group. `publishFallbackCopy` is memoised
on the raw config blob, so it does real work only when the backend copy changes —
it runs on every stream frame, several times a minute.

---

## 3. The test a value has to pass

A constant moves to the backend only if all three hold.

| | Test | Example of a pass | Example of a fail |
|---|---|---|---|
| 1 | It is a judgement, not arithmetic | "how long a departed train stays up" | "sixty seconds is a minute" |
| 2 | Wrong degrades, never breaks | a bad grace period shows a train slightly too long | a bad fetch timeout kills the board |
| 3 | A safe default ships in the binary | every field of `BoardPolicy` | — |

Test 3 is not a nicety. A cold install paints before any fetch returns, and an
offline launch never gets one.

---

## 4. What shipped

### P0 — the clamped accessor

**`core/config/RemoteConfig.kt`**

Typed reads out of the flat map. Three failure modes, each with a deliberate
answer:

| Input | Result | Why |
|---|---|---|
| key missing | the default | the common case: never fetched, offline, or an older backend |
| unparseable | the default | `"soon"` is not a number; guessing is worse than ignoring |
| out of range | **clamped to the bound**, not the default | `5000` against a max of `120` means "as much as possible"; honouring the intent at the limit beats discarding it |
| blank string | treated as **absent** | an empty error box on a screen where someone is stuck is worse than unimproved wording |

`list()` falls back rather than returning empty, so `","` cannot erase an
ordering the app depends on.

### P1 / P3 — board policy

**`core/config/BoardPolicy.kt`, `core/config/BoardPolicyStore.kt`**

| Key | Default | Clamp | Owns |
|---|---|---|---|
| `board.tick.departedGraceMs` | 30000 | 0 … 120000 | how long a departed train stays up |
| `board.tick.retentionMinAgeMs` | 60000 | 15000 … 600000 | when a stale board starts saying "Gone" |
| `board.tick.departedLabel` | `Gone` | ≤ 6 chars | the retained-row word |
| `board.tick.rowReserve` | 10 | `MAX_ROWS_PER_PLATFORM` … 20 | ingest depth per platform |
| `board.stale.freshMs` | 60000 | 15000 … 600000 | footer chronometer amber → grey |
| `board.stale.staleMs` | 180000 | freshMs … 3600000 | footer chronometer grey → red |
| `board.status.severityOrder` | 19 entries | — | which disruption a board leads with |
| `board.status.redSeverities` | 8 entries | — | which severities make the dot red |

**Design decisions worth keeping:**

- **`BoardTicker` takes a defaulted policy parameter, not a store read.** It stays
  a pure function of (rows, now, policy); its test suite depends on that.
- **`Board.kt` resolves the policy ONCE per composition.** Not an optimisation, a
  correctness requirement: rows are labelled by the tick and then filtered by
  that label, so a config refresh landing between the two would have the tick
  write "Gone" while the filter looked for "Left", handing every retained row to
  the hero as a live train.
- **`freshMs` and `retentionMinAgeMs` are coupled.** Serving one moves the other;
  serving both explicitly is honoured as a deliberate decision that they differ.
  The footer going grey and the rows going "Gone" are one statement about one
  payload.
- **`departedLabel` is length-capped** because the ETA column is the widest-label
  column and it decides where the destination truncates. That is why it reads
  "Gone" and not "Departed".
- **`rowReserve` is floored at `MAX_ROWS_PER_PLATFORM`.** A reserve shallower than
  the deepest board a user can ask for renders that board short whatever they set.
- **`rowReserve` applies at INGEST.** Changing it affects only rows written after
  the change; existing SQL keeps the old depth until the next fetch.

**`BoardPolicyStore`** holds the one resolved value. It exists because the policy
is needed both at render (where a map is in scope) and at ingest —
`SyncPredictionsUseCase` runs on a stream frame in `core`, several times a
minute, with no UI above it. Threading a map from the ViewModel into the stream
path to serve one integer would invert the dependency for nothing.

`loadFromCache()` covers the background-refresh path, which runs with no UI at
all; without it a background refresh would write SQL at the compiled depth while
the foreground app wrote at the served one.

### S1 — the dead knobs

36 keys the clients read that the backend never sent, seeded from the clients'
own fallbacks so the first deploy renders identical words.

- 32 iOS keys: the whole `explore.status.*` card and sheet, the whole
  `explore.fares.sheet.*`, `board.hero.*`, `empty.hint`, `dream.settings.start`.
- 4 Android keys: `explore.status.dialog.*`, found by the same sweep — read by
  the **production** client and never served.

Three seeded values keep **straight** apostrophes where the backend's house style
is typographic. That was the point: the deploy had to be provably a no-op.
Upgrading them is now a backend edit rather than a release, which is the whole
capability this bought.

### S2 — the auth vocabulary

**`composeApp/ui/login/AuthStrings.kt`** — 22 messages, the password rule, and the
resend cooldown.

**The mapping stays in the client.** Which Firebase code means "wrong password"
is a fact about Firebase, not a preference. A server able to re-point
`wrong-password` at the "no such account" line could only make the app lie about
what just happened. The server owns the words; the client owns which words apply.

- `auth.password.min_length` is **floored at 6** because Firebase enforces 6. A
  lower served value would have the form accept a password the network then
  rejects as too short — the form contradicting itself in front of the user.
- `LoginScreen`'s duplicate `length < 6` was deleted. Two copies meant a served
  length would move the submit check and leave the form still refusing at six.
- `auth.verify.resend_cooldown_sec` is clamped 10–600. It gates a button: a
  served zero is an unlimited send loop, a served hour is a dead control.
- The verify screen's `LaunchedEffect` is keyed on `cooldownSec`, not `Unit`, so a
  served value that arrives after the first frame is adopted immediately rather
  than from the next visit.

### S3 — the widget's configuration states

**`WidgetStateCopy`** in `core/iosMain`, plus the Swift half.

Four states — signed out, no stations, never configured, configured for a removed
station — riding the table `publishFallbackCopy` already writes, as a **separate
map**. Merging them with the board fallback copy would let a board state answer
for a configuration one; they are total switches over different enums.

Resolved once per timeline build and carried on `DepartureEntry`. The view runs
inside WidgetKit's archiving pass, so reading the App Group there would decode the
same four sentences ~20 times per widget.

`widget.state.removed.title` cannot be overridden away: the removed station's own **name**
takes that slot, because it is what tells a user which of several widgets needs
attention without opening any of them. The served string is reached only when iOS
hands over a configuration with an id and no entity around it.

Swift's `DepartureRow` gained a local `departed` flag set by the tick, replacing a
comparison against the literal `"Gone"`. Once the label became configurable, a
view testing for the literal would have silently stopped dimming retained rows.

### S5 — the severity vocabulary

**`stationly-backend/src/services/lineSeverityService.ts`** — one array, each row
carrying the TfL string, its display name and its tone. Three wire forms out of
it, so ordering, tone and wording cannot drift.

It replaced three separate enumerations of one vocabulary:

| Was | Where | Now |
|---|---|---|
| ordering | `BoardPolicy.severityOrder` | generated |
| display names | `LineStatusSheet.SEVERITY_WORDS` (read per key, never served) | generated + served |
| the red set | `LineStatusRanker.RED_SEVERITIES` (not configurable) | `BoardPolicy.redSeverities` |

Serving `explore.status.severity.*` lights those keys up on **production
Android** with no Android release — both clients have read them since they were
written and nothing ever sent them.

---

## 5. Android safety

The critical check, because the backend serves production Android the same
payload it serves iOS.

### Method

HEAD's `getHomeConfig()` was built in an **isolated git worktree** (not a stash,
which raced with the probe) and its key set and values compared against the
working tree's, under `LC_ALL=C`.

### Results

| Question | Answer |
|---|---|
| Keys removed from the payload | **0** |
| Existing values changed | **0** |
| Keys added | 102 |
| New keys Android READS | 12 |
| Of those 12, matching Android's own fallbacks | **12 / 12** |

The 12 are `auth.verify.*` (8) and `explore.status.dialog.*` (4). Android's
rendering switches from compiled fallback to served value, so they had to match
**Android's** fallbacks, not iOS's. `auth.verify.*` was seeded from the iOS
client during S2 and happened to match exactly — luck, not design. The check is
what establishes it.

### Shared `core` code

`android/app` depends on `:core`. Its entire use of the changed APIs is:

```
StaleColor.colorForAge   (3 call sites)
StaleColor.AMBER         (2 call sites)
```

`colorForAge` gained a **defaulted** parameter, so every call site compiles
unchanged. The default resolves through `BoardPolicyStore.current`, which on
Android is always `BoardPolicy.DEFAULT` — nothing on Android calls `refresh()` or
`loadFromCache()`, both of which live in modules Android does not build. The
defaults are the same 60s/180s the old constants held.

**Android behaviour is bit-identical whether or not it is rebuilt.**

Android reads none of `board.tick.*`, `board.stale.*`, `widget.state.*`,
`board.status.redSeverities`, `explore.status.severity.*` or
`auth.verify.resend_cooldown_sec`.

### A false alarm, recorded so it is not re-raised

An earlier run of this comparison reported `board.status_label` and
`board.status_failed_label` as removed. They were not. The two key lists had been
sorted under the shell's default locale, where `.` and `_` collate differently
from `comm`'s byte comparison. Under `LC_ALL=C` the difference vanishes, and a
direct probe confirms both present with original values.

**Any re-run of this check must force `LC_ALL=C`.**

---

## 6. The key inventory tool

**`scripts/sdui_keys.py`** → **`docs/CONFIG_KEYS.md`**

```
scripts/sdui_keys.py            regenerate the inventory
scripts/sdui_keys.py --check    exit 1 if a key has left the payload
```

It scans three source trees and records, per key, whether the backend serves it
and whether each client reads it. `--check` fails on removal and **names the
client that reads it**, because the risk is not that a key goes missing — it is
that whoever removes it is not thinking about the frozen client.

### Two design rules, both learned the hard way

**It asks the backend rather than regexing it.** `getHomeConfig()` spreads in keys
that several services generate in loops. A source scan reported 177 where the
payload has 239. It warns loudly if it has to fall back to scanning.

**It is generous about client reads.** Any dotted literal in non-test client
source counts, provided its namespace is one the backend serves. A precise
matcher is the wrong tool here, because the error directions are not symmetric:

| | Consequence |
|---|---|
| false positive (marked read, is not) | key becomes undeletable. Costs bytes. Harmless. |
| false negative (live key looks unread) | someone deletes it; a shipped client renders blank. |

Hence the rule in its own comments: **when in doubt, match more.** Known and
accepted false positives: `widget.refresh` (a push-envelope constant that shares
the now-served `widget` namespace).

`--check` reads the **committed** inventory via `git show`, not the working file,
so running regenerate first cannot make the check vacuous.

---

## 7. Deliberate non-changes

| Thing | Where | Why it stays in the client |
|---|---|---|
| `if (secs < 60) 0` | `BoardTicker.minutesAt` | The definition of a minute. Changing it makes the label lie: "Due" would mean "under 90s" while "1 min" still meant a minute. |
| the 90s "Due" window | derived, `60 + grace` | Emergent, not a value. Exposing it gives two ways to set one thing. |
| the minute-tick loop | `MinuteTick.kt` | A function of *now*. No server can render it. |
| Firebase code → message mapping | `LoginViewModel.friendlyAuthError` | A fact about Firebase, not a preference. |
| the auth state machine | `AppNavigation.kt:37` | Auth state, not a setting. |
| card geometry | `HomeBoardBudget.kt` | dp measurements of things drawn elsewhere in the same binary. |
| fetch timeouts, debounces | `DepartureRepository`, various | Plumbing. A bad value is a dead screen — fails test 2. |
| animation durations | `Board.kt` | Feel, tuned on the real device. |
| support nag cadence | `SupportStore.kt:98,111` | Depends on device-local history the server deliberately does not hold. |
| `board.good_service_sub` | read, not served | An optional override that intentionally chains to `explore.good_service_sub`, so one phrase covers the state everywhere. Serving it breaks the link. |
| `explore.status.dialog.title.disrupted` | read by Android, not served | Android composes it with singular/plural agreement, which a flat string cannot express. |
| brand strings, a11y labels, preview data | various | Not config. |

---

## 8. What remains

| | Scope | Size | What it closes |
|---|---|---|---|
| **S4** | Selection / onboarding copy | 54 strings | Nothing broken. Makes the first screen a new user sees tunable. |
| **S6** | Settings, profile, offline copy | ~85 strings | Nothing broken. Includes destructive-action dialogs worth being able to soften. |
| **P2** | Line palette consolidation | medium | 4 copies of the TfL colours, none authoritative: `TflLineColors.kt` (no consumer here), `Board.kt:160-175` (3 maps), `WidgetTheme.swift:84`, backend `lineIconService.ts`. Belongs in `/sdui/app/theme-tokens`. Needs all four edited in one commit. |

**Loose thresholds**, one key each, no phase of their own: dropdown cache TTL
(duplicated inline twice in `SelectionViewModel`), route text max age, support
fetch interval, hero urgency `<= 1 min`, explore peak horizon, weather refresh
interval and its hardcoded London coordinates.

S4 and S6 are ~140 strings of mechanical transcription with no defect behind
them. The honest advice is to do a screen's copy when that screen is open for
another reason, not as a project. They become worth doing as a batch only if a
period is coming where client builds cannot ship.

---

## 9. Mistakes, and the guards they produced

Recorded because the guards only make sense against them.

### A regex deleted 36 keys

The edit introducing `LineSeverityService` replaced the old order block by regex.
The comment it anchored on sat **above** the entire S1 block, so a `.*?` meant to
span one key spanned thirty-seven. `tsc` was perfectly happy. Recovered from the
deployed staging payload — the only remaining record, the file being uncommitted.

**Guard:** `--check`, now non-vacuous (below). **Habit:** commit before a
structural edit; a deployed server is not a backup.

### The guard that should have caught it was vacuous

`--check` was run *after* a regenerate, making it compare the payload against a
record just written from that same payload.

**Fixed:** it reads the committed inventory from git, so command order no longer
matters.

### The scanner under-reported four times

Four shapes it could not see, each one marking live keys droppable:

| Shape | Example | Fix |
|---|---|---|
| key behind a helper | `str("auth.verify.title", …)` | match the key, not the call |
| Kotlin string template | `"widget.state.${s}.$part"` | template-aware pattern |
| generated backend keys | `out[keyFor(e.tfl)] = …` | run the backend, don't regex it |
| locale collation | `comm` on default-sorted lists | force `LC_ALL=C` |

### Duplication introduced and then removed

| Defect | Risk | Fix |
|---|---|---|
| `home_config_strings_cache` as a literal in three files | a rename compiles everywhere and silently splits the cache — app writing one key, widget publisher reading another | `ConfigKeys.HOME_CONFIG_CACHE_KEY`, defined once in `core` |
| `ROW_RESERVE` constant beside a `rowReserve` getter | both compile at every call site; picking the constant silently ignores the served value | constant deleted; `BoardPolicy.rowReserve` holds the default, the getter is the only way to ask |
| `LoginViewModel` and `VerifyEmailScreen` each fetching `getHomeConfig()` | the auth flow made the same request twice within a second and the halves could render different payloads | `LoginViewModel.configStrings` as a StateFlow; the screen collects it |

---

## 10. Verification

| Check | Result |
|---|---|
| `:core:testDebugUnitTest` | **315 passed, 0 failed** (288 at session start) |
| Backend `npm test` | **180 / 180** |
| Backend `tsc --noEmit` | clean |
| `:composeApp:assembleComposeAppDebugXCFramework` | BUILD SUCCESSFUL |
| `xcodebuild` iosApp Staging | BUILD SUCCEEDED |
| Keys removed from payload vs HEAD | **0** |
| Existing values changed vs HEAD | **0** |
| New Android-read keys matching Android fallbacks | **12 / 12** |
| Newly-added imports, all used | 18 / 18 |
| Duplicate keys in the payload literal | none |
| Deployed to staging and re-queried | 239 keys live |

**New tests:** `BoardPolicyTest` (14), `RemoteConfigTest` (7),
`PolicyDrivenTickTest` (4). The policy tests assert both that a served value takes
effect **and** that the compiled default still says the opposite — the proof the
value travelled rather than the test agreeing with itself.

**Use `:core:testDebugUnitTest`**, not `allTests`, which dies on wasmJs.

**Not visually confirmed:** the four widget configuration panels. This phone has
no CLI screenshot path, so they want a human eye.

---

## 11. Commit and deploy

Backend first, so the client's new keys are never briefly absent. (Harmless
either way — they fall back — but the order makes the deploy meaningful.)

**`stationly-backend`**
```
feat(sdui): serve the board policy, auth vocabulary, widget states and severity table

102 new keys, none removed. Values transcribed from each client's own fallbacks
so the deploy renders identically on iOS and on production Android. Adds
lineSeverityService as the single source for severity ordering, tone and
display names.
```

**`StationlyUI`**
```
feat(sdui): board policy, auth vocabulary and widget states from config

core/config: RemoteConfig (clamped reads), BoardPolicy, BoardPolicyStore,
ConfigKeys. BoardTicker, StaleColor, LineStatusRanker and MultiLineBoardProcessor
take a policy as a defaulted parameter. AuthStrings covers the auth flow's
messages. The widget's four configuration states ride the published table.
scripts/sdui_keys.py + docs/CONFIG_KEYS.md track which client reads each key.
```

**After the first commit**, run `python3 scripts/sdui_keys.py --check` once to
establish the baseline. It currently reports "no committed inventory", which is
correct until then.

**Deploy:** `bash .scripts/staging_deploy.sh` in the backend;
`bash scripts/ios-dev.sh staging --kotlin` for the app. Verify with a live query
against `/api/v1/sdui/app/home-config` using the key from
`iosApp/Config/Staging.xcconfig`.

---

## 12. File map

### New

| File | Purpose |
|---|---|
| `core/config/RemoteConfig.kt` | clamped typed reads out of the config map |
| `core/config/BoardPolicy.kt` | the board's eight tunable values + their keys and defaults |
| `core/config/BoardPolicyStore.kt` | resolves one policy; read by render and ingest alike |
| `core/config/ConfigKeys.kt` | the storage key, defined once |
| `composeApp/ui/login/AuthStrings.kt` | every message the auth flow can produce |
| `core/commonTest/config/BoardPolicyTest.kt` | absence, clamping, the freshness pair, the red set |
| `core/commonTest/config/RemoteConfigTest.kt` | the accessor's guarantees |
| `core/commonTest/config/PolicyDrivenTickTest.kt` | end to end: a served value changing the board |
| `scripts/sdui_keys.py` | the inventory generator and removal guard |
| `docs/CONFIG_KEYS.md` | generated inventory: key × served × iOS × Android |
| `docs/SDUI.md` | this document |

### Changed

| File | Change |
|---|---|
| `core/util/BoardTicker.kt` | constants → `BoardPolicy`; defaulted policy parameter |
| `core/util/MultiLineBoardProcessor.kt` | `rowReserve` getter; policy through `buildGroups` / `rowsFrom` |
| `core/util/StaleColor.kt` | thresholds from the policy (defaulted) |
| `core/util/LineStatusRanker.kt` | `RED_SEVERITIES` deleted; `toneOf` / `rankOf` take a policy |
| `core/usecase/SyncPredictionsUseCase.kt` | ingest depth from `rowReserve` |
| `core/iosMain/platform/WidgetAppGroup.kt` | table gains tick policy + `WidgetStateCopy` |
| `core/iosMain/platform/Platform.ios.kt` | publishes policy and state copy to the App Group |
| `core/iosMain/platform/BackgroundBoardRefresher.kt` | loads the policy on the no-UI path |
| `composeApp/ui/summary/components/Board.kt` | one policy per composition |
| `composeApp/ui/summary/SummaryViewModel.kt` | refreshes the store from cache and network |
| `composeApp/ui/login/LoginViewModel.kt` | `AuthStrings`, `configStrings`, password rule |
| `composeApp/ui/login/LoginScreen.kt` | duplicate password rule removed |
| `composeApp/ui/login/VerifyEmailScreen.kt` | strings from the view model; served cooldown |
| `composeApp/ui/util/PredictionTicker.kt` | `const val` alias deleted |
| `composeApp/ui/util/HomeConfigCache.kt` | key from `ConfigKeys` |
| `iosApp/StationlyWidget/BoardFallback.swift` | tick policy, stale ladder, config copy |
| `iosApp/StationlyWidget/AppGroupStorage.swift` | tick takes a policy; `departed` flag |
| `iosApp/StationlyWidget/DepartureEntry.swift` | carries the config copy |
| `iosApp/StationlyWidget/StationlyWidget.swift` | one table read per timeline build |
| `iosApp/StationlyWidget/WidgetViews.swift` | empty view + stale ladder from the table |
