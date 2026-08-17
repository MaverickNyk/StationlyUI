# Widget station assignment: one rule, six states, no guessing

_Sessions 2026-08-16 and 2026-08-17, branch `ios-parity`. **Uncommitted** — the
whole change is in the working tree._

_Durable design lives in `IOS_WIDGET_DESIGN.md` §9. This document is the handover:
what changed, why, what is verified, and what is deliberately left._

---

## 1. The rule

> **A widget shows the station in its own configuration, or it shows why it
> cannot. It never picks one, and it never substitutes another station's board.**

Everything below follows from it.

---

## 2. What a widget shows

`StationResolver.board(for:)` — one ordered ladder, first match wins, run on every
timeline build, persisting nothing.

| # | condition | medium / large (**bold** = title slot) | small |
|---|---|---|---|
| 1 | signed out | **Stationly** / Sign in to see departures | Sign in |
| 2 | no stations tracked | **Stationly** / Open the app to add a station | Add a station |
| 2b | no stations tracked, **and no configuration** | the legacy flat-key board | same |
| 3 | no station chosen | **Choose a station** / Touch and hold, then tap Edit Widget | Choose a station |
| 4 | its station was deleted | **_Name_** / Removed from Stationly. Touch and hold, then tap Edit Widget to choose another. | Station removed |
| 5 | station tracked, board not written yet | that station, no departures | same |
| 6 | otherwise | the live board | same |

Rung 3 puts the **instruction** in the title slot rather than the app's name: it
is the state a user meets during setup, and it can surface on several widgets at
once (adding the first station after a spell with none un-masks every stale
configuration at the same moment), so it reads as a setup step and never as an
error. Rung 4 keeps the station's own name there — that is what says which widget
needs attention without opening any of them. The small family drops the name:
at that size it truncates, and half a station name is worse than the state it
was explaining.

Rung 1 is first because every rung below looks for a station, which after a
sign-out would hand a board to a widget the previous account left behind.

Rung 2b is the pre-multi-station board, and it is offered **only** to a widget
with no configuration. Those flat keys hold whichever station led the home screen
when an old build wrote them, so a configured widget reaching them would render
one station's departures under another's name.

Rung 4 is reached only when iOS passes the **stored** entity for a station the
directory no longer lists. It usually passes nil instead, and the widget lands on
rung 3. Both are safe and ask for the same tap; rung 4 can also name what went.

---

## 3. Which station a NEW widget gets

The first station on the home screen. `defaultResult()` is
`readStations().first`; `recommendations()` is the directory, unrotated. They are
the same expression, so they cannot disagree.

The gallery shows one labelled preview per station, so choosing a different one is
a swipe before tapping Add. After that, only the user changes it.

---

## 4. Bugs fixed

| # | Symptom | Root cause |
|---|---|---|
| 1 | Tapping a widget on staging opened the app but never focused the station | `ContentView.onOpenURL` compared `url.scheme` against the literal `"stationly"`; staging registers `stationly-staging`. Pre-existing since the environment split |
| 2 | A widget showed a station it was not configured for | Resolver rung 3 guessed a station when the configuration named none |
| 3 | Deleting station A moved A's widget onto station B | iOS nils an unresolvable parameter, so a deleted station's widget arrives at rung 3 looking unconfigured, and rung 3 guessed |
| 4 | Adding a station moved every unconfigured widget onto it | Rung 3 guessed `stations[0]`, and a new station goes to the **top** of the home screen |
| 5 | Deleted stations reappeared on widgets the user never set to them; one capture had 5 of 6 widgets on a station that no longer existed | `entities(for:)` returned a synthetic "tombstone" entity. **iOS persists whatever that method returns into widget configurations and its recently-used cache**, so a dead station became an assignable one |
| 6 | Adding a third widget to two healthy stations showed "Removed station" | Same tombstone, built from an id alone. A new widget's parameter is an id that was never a station |
| 7 | "Your first station" was undefined and could change after a cloud sync | `selectAllSelections` had no `ORDER BY`. Boards never dragged all tie at `UNPOSITIONED` under a stable sort, and `restoreFromCloud` clears and re-inserts |
| 8 | The extension's own refresh could write one station's departures under another station's key | `targetStations` fell through to the legacy flat feed for any board with no feeds of its own |
| 9 | Delete dialog promised the widget would switch to another station | It no longer does |
| 10 | A small widget showed a bare roundel in every empty state | `EmptyWidgetView` drew no text below medium |

**The through-line for 2, 3, 4, 5 and 6:** each fix made a guess smarter and left
the guessing in place. A timeline provider is handed **no widget identity**, so it
cannot know which widget is asking or what it showed last. Any station it picks is
a guess about a widget it cannot see. Rung 3 now asks the user.

---

## 5. Performance

| Change | Effect |
|---|---|
| `WidgetData.isFetchable` guards the staleness branch | A tracked station whose payload is not yet written is dated to the epoch, so it was **permanently stale**. Every timeline build started a task group, a timeout timer and an async WidgetKit enumeration to produce zero targets. Now skipped |
| Removed `refreshCache()` from `timeline(for:in:)` | Drops a `getCurrentConfigurations` round trip plus a JSON encode of the placement snapshot from every build, on the one path where latency is user-visible |
| Removed `lastKnown()` from the picker path | Drops a JSON decode per resolved identifier |
| Deleted the placement snapshot entirely | Two App Group keys no longer written or read |

---

## 6. Code quality

- **Deleted `StationAssignment.swift` and `WidgetPlacementRegistry.swift`** (~322
  lines), the `widget_placed` / `widget_placed_at` / `widget_adopted_station`
  keys, `StationEntity.tombstone`, and the `claims=` trace field.
- **`WidgetData.EmptyReason`** replaces three parallel flags read in three places.
  The empty view's copy variants and the trace's state name are total switches
  over it, so a state added later cannot silently inherit another's wording.
  (The review pass went further and made it the only stored value — §13.3 — so
  there is no longer a precedence rule between flags to get wrong.)
- **`WidgetData.isFetchable`** replaces the same predicate written twice, in two
  different shapes, in the provider and the refresh service. They must agree or
  one fetches what the other will not render.
- `StationResolver.displayName` was a one-line passthrough; inlined.
- File headers describe what the code does now, with the history compressed to
  the argument that must be answered before reversing it.

---

## 7. iOS behaviours this cost us, worth knowing before touching it again

1. **`EntityQuery.entities(for:)` is not a display hook.** What it returns is
   persisted into widget configurations and cached as a recently-used value.
   Never return a placeholder, "removed", "unknown" or "loading" entity. Answer
   only for things that exist.
2. **iOS pre-fills a new widget's parameter from its own cache.** A pre-filled
   parameter is *resolved*, so `defaultResult()` is never consulted and no
   assignment logic gets a say. There is no API to invalidate that cache.
3. **An unresolvable parameter arrives at the provider as nil**, while the Edit
   sheet keeps painting the name iOS cached. The board and the sheet therefore
   disagree until the user picks anything, which writes a real configuration.
4. **`WidgetCenter.getCurrentConfigurations` returns an empty list when called
   inside `timeline(for:in:)`**, on a phone with widgets placed, repeatably. It
   works from `defaultResult()`. This is why any home-screen snapshot needs a
   "never overwrite with empty" rule, and part of why the snapshot is gone.
5. **A timeline provider is handed no widget identity.** Two widgets of the same
   family are indistinguishable to the extension.

---

## 8. Deliberately unchanged

**Sign-out and sign-in.** The `widget_signed_out` flag, rung 1 checking it ahead
of everything, the suppression of the extension's REST refresh while signed out,
and configurations surviving a sign-out so every widget returns to its own station
on sign-in. None of it was implicated in these bugs.

Also untouched: board rendering, ticking, paging, the refresh button, the deep
link target, `widget_placements` (still written for `HomeStateProbe`'s delete
dialog).

---

## 9. Files changed

**New**
- `iosApp/StationlyWidget/StationResolution.swift` — the ladder

**Deleted**
- `iosApp/StationlyWidget/StationAssignment.swift`
- `iosApp/StationlyWidget/WidgetPlacementRegistry.swift`

⚠️ Both were written and removed inside the same uncommitted stretch, so they
were **never committed** and `git log` will not find them. This section is the
only record that they existed. §12 is the argument against writing them again.

**Modified**

| File | Change |
|---|---|
| `iosApp/iosApp/ContentView.swift` | Deep-link scheme read from Info.plist, not a literal |
| `iosApp/StationlyWidget/StationConfiguration.swift` | `entities(for:)` resolves only live stations; `defaultResult()` is one line |
| `iosApp/StationlyWidget/StationlyWidget.swift` | Provider uses the resolver; `isFetchable` guard; `recommendations()` unrotated; `notePlacement` stamps the RENDERED station (§13.3 bug 11); one trace line and one `os_log` line per build, carrying `cfg=` / `shown=` / `state=` |
| `iosApp/StationlyWidget/AppGroupStorage.swift` | One private `reason: EmptyReason?` behind `emptyReason`; one `empty(_:)` factory; `stateName`; `isFetchable`; `readWidgetData()` renamed `legacyBoard()`; `os_log` replaces two dead `print`s; placement stamp accessors private |
| `iosApp/StationlyWidget/StationResolution.swift` | **New.** The ladder. Rung 2 hands the legacy board only to an unconfigured widget (§13.3 bug 12) |
| `iosApp/StationlyWidget/AppGroupKeys.swift` | Removed keys, with a note on why they must not return |
| `iosApp/StationlyWidget/WidgetRefreshService.swift` | Resolves whole entities; skips unfetchable boards; legacy feed only for the legacy board |
| `iosApp/StationlyWidget/WidgetViews.swift` | `EmptyWidgetView` takes one `EmptyReason`; small family carries text; copy per §2 |
| `core/…/platform/Platform.kt` | **New** `WidgetRestore` gate (§13.1) |
| `core/…/platform/Platform.ios.kt` | `refreshAllBoards` skips the empty-state wipe during a restore; stale comment |
| `core/…/StationlyDatabase.sq` | `ORDER BY id` on `selectAllSelections` |
| `composeApp/…/login/LoginViewModel.kt` | `WidgetRestore.during { }` around the login restore |
| `composeApp/…/StationSettingsScreen.kt` | Delete dialog copy, in the widget's own words |
| `docs/IOS_WIDGET_DESIGN.md` | §9 rewritten; §9.1 gained the add-time rule; §1 file map; §7 key table |
| `docs/IOS_HANDOVER.md`, `docs/USER_STATE_AND_ACTIVITY.md` | Superseded notes |

⚠️ **Two shared-core files carry Android risk and neither changes Android
behaviour.**

- `StationlyDatabase.sq` — `selectAllSelections` has one consumer,
  `SqlStorage.getAllSelections()`, so the ordering guarantee applies to Android
  too, which is an improvement there for the same reason. It is a query, not
  schema, so **no migration is needed.**
- `Platform.kt` — `WidgetRestore` is read only by `Platform.ios.kt`. Android has
  its own `LoginViewModel` and never raises the flag, so its widget path is
  byte-identical.

---

## 10. Verification status

**Built and installed** on the iPhone 11, staging, `scripts/ios-dev.sh staging`.
BUILD SUCCEEDED, no new warnings. `:core:compileKotlinIosArm64` green.

**Confirmed on device**
- Deleted stations no longer propagate onto other widgets (bug 5, 6)
- `cfg=`/`state=` trace works; the empty→removed transition was read from it

**Not yet verified — start here**
1. Add a widget from the gallery without swiping. Expect the first station.
2. Long-press → Edit Widget on each widget; expect that widget's own station.
3. Delete a station that has a widget. Expect rung 3 or 4, never another station.
4. Re-add it; a rung-4 widget should return by itself.
5. Tap a widget on staging; expect the app to focus that station (bug 1).
6. Sign out and back in; every widget returns to its own station.

**Pull the evidence**

```bash
xcrun devicectl device copy from --device AB7B04C8-F9D6-5C05-8388-5767BC96C059 \
  --domain-type appGroupDataContainer --domain-identifier group.com.stationly.staging \
  --source Library/Preferences/group.com.stationly.staging.plist --destination /tmp/g.plist
plutil -extract widget_refresh_trace json -o - /tmp/g.plist
plutil -extract widget_stations       raw  -o - /tmp/g.plist
```

`cfg=nil` in the trace means iOS gave the provider no station: either a widget
nobody has chosen for, or one whose station was deleted underneath it.

---

## 11. Known limits

| Limit | Why it stands |
|---|---|
| Two widgets added without swiping take the same station | The gallery leads with the first station for both. One swipe or one edit separates them |
| The Edit sheet can show a deleted station's name for a while | iOS paints it from its own cache; no API reaches it. Picking any station ends it |
| ~~A cloud restore briefly shows "Open the app to add a station" on every widget~~ | **Fixed 2026-08-17.** `WidgetRestore.during { … }` wraps the login restore and `refreshAllBoards` skips the empty-state wipe while it is raised — see §13 |
| `HomeStateProbe` cannot count two widgets on one station | It matches placement stamps by size and recency, so the delete dialog always says "A widget" |
| `profile.delete_station.bullets` still says "Widget will be cleared" | Backend-served, not changeable from this repo |

---

## 12. If you are tempted to reintroduce auto-assignment

Read §4's through-line and `IOS_WIDGET_DESIGN.md` §9.1 first. It was built,
shipped, and removed at the user's request after producing five distinct
substitution defects. The machinery needed the exact home screen; the exact home
screen is not cheaply or reliably knowable from an extension; and everything
derived from an approximation of it moves. **Nothing a board renders may depend on
it.**

---

## 13. Design review, and the two changes it produced (2026-08-17, later)

The ladder was re-examined against a plain-language requirement: *"a new widget
must never be empty when I have stations, but deleting a station may empty its
widget."* Those assign **opposite** outcomes to the two situations that reach the
provider as the same `nil`, so at first reading the ladder had to break one of
them.

**It does not, and the reason is the whole finding:** the two halves are served
by two different mechanisms, and only one of them is the ladder's job. "Never
empty on add" is decided at **add time** by `defaultResult()` / `recommendations()`,
so a normally-added widget is born configured and never passes through nil at
all. "Empty only when I deleted it" is rung 3's job. No single nil-rule has to
serve both. Rendering the first station at nil would therefore add nothing to the
add path while restoring bugs 3 and 4 to the delete path. Recorded in
`IOS_WIDGET_DESIGN.md` §9.1 so it does not get relitigated.

**The ladder's shape is unchanged.** A review pass then hardened it; §13.3 is the
full log.

### 13.1 The login restore no longer publishes "you have no stations"

`syncUserAndGetSavedStations` calls `clearAllData()` and then re-inserts from the
cloud profile, and `IosWidgetManager.refreshAllBoards` reads that same table.
Told nothing, an empty table means "the user deleted their last board", so it
wiped the App Group and put every placed widget on rung 2 — during a sign-in, for
a user who had done nothing.

Storage cannot separate the two; only the caller's intent can. So:

| File | Change |
|---|---|
| `core/…/platform/Platform.kt` | New `WidgetRestore` object: a `@Volatile` flag and a `during { }` scope |
| `core/…/platform/Platform.ios.kt` | `refreshAllBoards` returns early instead of wiping while the flag is raised |
| `composeApp/…/login/LoginViewModel.kt` | `WidgetRestore.during { }` wraps the clear, the re-insert **and** `restoreBoards` |

It suppresses **only the empty-state wipe**, not the whole write — so boards
still publish one at a time as `restoreBoards` sets each of them up, and no
"publish once at the end" step exists to be forgotten by a future restore path.
A plain flag rather than a counter: restores do not nest, and two overlapping
ones would degrade to exactly today's behaviour rather than to something new.
It is in-memory, so a process killed mid-restore cannot leave it stuck raised.

⚠️ Deliberately **not** raised by sign-out. `cleanupAll()`'s wipe is intended, and
it is the one that raises `widget_signed_out`.

### 13.2 Empty-state copy

Rewritten per the table in §2, in `WidgetViews.swift`'s `EmptyWidgetView`. The
substantive change is rung 3 promoting its instruction into the title slot
instead of leading with the app's name; the rest is wording ("Choose" over
"Pick", "tap Edit Widget" as the menu spells it, no state phrased as a fault).

### 13.3 Review pass

Staff review of the whole uncommitted change set. Everything below is in the
working tree.

#### Bugs fixed

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 11 | The delete dialog silently stops warning "a widget is showing this station", for the one station whose id has moved | The provider stamped `widget_placements` with `configuration.station?.id`. That equals the rendered id **except** when a station's grouping id changed underneath a placed widget, which `directoryEntry` deliberately still resolves by name. The configuration then holds the old pole naptan while the app and the directory use the new StopArea, so `HomeStateProbe` matched nothing | Stamp `data.stationId`, the station actually rendered. Also drops the `?? ""` arm: every empty state has an empty id and `notePlacement` already ignores those, which is right — a widget with nothing on it is not holding a station |
| 12 | A **configured** widget could render another station's departures under its own name | Rung 2 handed `legacyBoard()` to any widget when the directory was empty. Those flat `widget_*` keys hold whichever station led the home screen when a pre-multi-station build wrote them. The two branches agree in practice (`wipe()` clears both), but not on an upgrade whose legacy keys are populated before the directory is written | Rung 2 offers the legacy board **only** when `configured == nil`. A configured widget gets `.noStationsTracked`, which is honest and self-corrects the moment the app writes the directory. Verified the legacy refresh path still reaches those keys: a legacy widget contributes no configuration and no tapped id, so `targetStations` still resolves it through the nil branch |

#### Performance

| Change | Effect |
|---|---|
| Deleted the mid-`timeline` `providerLog.notice` | It carried `deps=\(data.departureCount)`, which walked every row of every block **on every timeline build**, to produce a number that duplicated what `state=` now says. The file's own comment warns that instrumentation must never be the most expensive thing on the path it measures; this was. Two `os_log` calls per build become one, and `family` moved onto the survivor so nothing is lost |
| Removed `WidgetData.departureCount` | Dead with its only caller |

#### Code quality

| Change | Effect |
|---|---|
| **Three parallel empty-state flags collapse to one value.** `isSignedOut` + `removedStationName` + `needsStation` + a computed `emptyReason` that ordered them become one private `reason: EmptyReason?` | Nothing ever read the flags directly — every consumer already went through `emptyReason` — so they bought only the ability to set two at once and the question of which wins. The precedence ladder they needed is gone with them |
| **Three empty-board factories collapse to one.** `signedOut` / `needsStation` / `removed(station:)` were three 12-line copies differing in one assignment | One private `empty(_:)` plus three one-liners. ~30 lines out, and the shared `lastUpdated: Date()` rationale is stated once instead of three times |
| **`stateName` moves from `StationResolver` to `WidgetData`** | Every input was a property of the data. The resolver now only resolves, and the trace cannot name a rung the renderer did not take |
| **Two dead `print()` calls replaced with `os_log`** | `print` goes to stdout, which is unattached in a widget extension on a device — the provider's own header says so. Both sat on failure paths with a silent `.empty` fallback, which is exactly the shape that gets reported as "the widget is just blank" |
| **`writePlacementStamps` had no caller**, while `notePlacement` inlined a byte-identical encode-and-write | `notePlacement` calls it; both stamp accessors are now `private` |
| Removed the `WidgetData.needsStation` (static) vs `data.needsStation` (Bool) name collision | Fell out of the flag consolidation. The one remaining static/case pair is documented at `noStationsTracked` |

#### Deliberately not changed

- **`StationResolver` reads `readStations()` on every call**, so a refresh sweep
  decodes the directory JSON once per placed widget (~6 max, ~1ms total).
  Caching it would need cross-process invalidation — the app writes that key —
  and buying a microsecond by making the resolver stateful is the wrong trade in
  the one file whose whole value is being a pure function.
- **`targetStations` rebuilds the tapped station as a bare `StationEntity`** with
  no name, so it can only match by exact id. That is always enough: `tapped`
  arrives from `data.stationId`, which is already a resolved directory id.
- **`widget_placed` / `widget_placed_at`** stay inert on devices that ran a build
  between 2026-08-16 and 2026-08-17. Actively deleting another build's keys costs
  more than it returns.

### 13.4 Still open

The six device checks in §10 are unaffected by any of this and still need
running.
