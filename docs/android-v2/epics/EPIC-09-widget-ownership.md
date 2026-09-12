# EPIC-09 — The widget is the app's to run

**Raised by the owner, 2026-09-12, after walking S017's work on the phone.**

Android lets an app enumerate, bind and configure its own placed widgets. iOS
cannot do any of it. S017 used half of that and left the other half looking like
an iOS port: an empty manager screen, settings that silently edited the in-app
board, a pager bar that does not line up with the rows under it, and no sense of
where the widget went after you added one.

This epic finishes the job. It is scoped by one sentence: **the widget is a
thing the app owns, and the app should act like it.**

---

## What is NOT possible, verified before planning

**An app cannot remove a placed widget.** `AppWidgetHost.deleteAppWidgetId`
affects only widgets the CALLER hosts; placed widgets belong to the launcher's
host and no API exposes deletion to anyone else. Any story that says "remove
from the app" is unbuildable, so none does. AV2-9.2 covers the nearest honest
thing: say where the widget is and how to remove it, rather than offering a
button that cannot work.

**An app cannot scroll the launcher to a page.** There is no API. AV2-9.3 does
the achievable half: leave the app so the user is looking at their home screen
when the widget appears, rather than at the screen they added it from.

---

## The dependency graph, and what it says about order

```
        ┌──────────────────────────────────────────┐
        │  9.1  widget settings stop editing the   │   the architectural one:
        │       user's board   (M)                 │   everything about what a
        └───────────────┬──────────────────────────┘   widget SHOWS depends on
                        │                              where its settings live
             ┌──────────┴──────────┐
             ▼                     ▼
    ┌──────────────────┐  ┌──────────────────┐
    │ 9.2 the manager  │  │ 9.3 adding ends  │
    │     earns its    │  │     on the home  │
    │     place  (M)   │  │     screen  (S)  │
    └──────────────────┘  └──────────────────┘

    ── independent of 9.1, and of each other ──

    ┌──────────────────┐       ┌──────────────────┐   ┌──────────────────┐
    │ 9.4 pager bar    │──────▶│ 9.5 motion  (M)  │   │ 9.6 copy    (S)  │
    │     belongs (S)  │       └──────────────────┘   └──────────────────┘
    └──────────────────┘                              ┌──────────────────┐
                                                      │ 9.7 model    (S) │
                                                      └──────────────────┘
```

**What the graph is for.** 9.1 is the only story anything else waits on, and it
is the only one that can make the other six wrong if it lands late: 9.2 renders
settings, so it has to know whose settings they are. Everything on the bottom
row touches different files and can run at once.

**9.4 before 9.5** because animating a control that is misaligned just animates
the misalignment.

**9.6 last among the independents** only because it sweeps strings the others
write. Its test lands first so the others cannot reintroduce what it removes.

---

## AV2-9.1 — Widget settings stop editing the user's board · `M`

**The bug.** `WidgetConfigureActivity` writes `rowsPerPlatform`, `pin` and
`platformNav` through `UserSettings.update(groupingId)`, which is the SAME
`BoardConfig` the station's own settings screen edits and the home screen
renders from. Changing how a widget looks silently changes the card in the app.

The owner's rule: **widget settings are the widget's alone.**

- **T1** `WidgetSettings` — a per-`appWidgetId` record (depth, pin, nav), stored
  beside the binding in `widget_prefs`. Pure model + store, tested.
- **T2** `DepartureWidgetProvider` reads it instead of `UserSettings.configOf`.
- **T3** The configure screen writes it instead of `UserSettings.update`.
- **T4** Migration: a widget with no record of its own inherits its board's
  CURRENT values once, so nobody's existing widget changes shape on upgrade.
- **T5** Copy: the caption that says settings "apply everywhere" is now false.

**Done when** changing a widget's depth leaves the in-app board untouched, and
the reverse, proven on the device by reading both stores.

---

## AV2-9.2 — The manager earns its place · `M`

**The bug.** With no widgets it says "No widgets on your Home Screen yet." and
stops. That is a dead end on the one screen that exists to get you out of it.

- **T1** Empty state that teaches: what a widget is, what it will look like, the
  two ways to add one, and which of them this launcher supports.
- **T2** A real preview of the widget rather than a description of one.
- **T3** Every entry point the platform allows, in one place: add, choose
  station, edit settings, and find an existing one.
- **T4** Removal, stated honestly (see above): where it is and the gesture, not
  a button that cannot work.
- **T5** Layout that holds at 320dp, 400dp, a foldable's inner screen and a
  tablet. Width-capped and centred is not enough on its own.

---

## AV2-9.3 — Adding a widget ends on the home screen · `S`

After `requestPinAppWidget` the app stays in front, so the widget the user just
added is behind the screen they added it from, and the flow ends with them
pressing back twice to see whether it worked.

- **T1** On a confirmed pin, finish and hand control to the launcher.
- **T2** Do NOT do this when the launcher refused the pin.
- **T3** Verify on the device that the home screen is what is on screen.

---

## AV2-9.4 — The pager bar belongs to the board · `S`

**Measured, not guessed.** The platform header insets 2dp, a departure row
insets 4dp, the pager bar insets 0 and uses 34dp tap targets. So it runs flush
to the edges while every row is inset, and stands taller than the header it
replaces.

- **T1** Match the header's inset and height exactly.
- **T2** Chevrons that read as part of a departure board, not as Material
  buttons dropped onto one.
- **T3** Keep the target tappable while the glyph is small: padding, not size.

---

## AV2-9.5 — Motion · `M`

The board is static. iOS moves when it refreshes and when it changes.

- **T1** Stepping platforms animates rather than cutting.
- **T2** A refresh reads as a refresh.
- **T3** Within RemoteViews' actual limits, which are severe: no Compose, no
  property animators driven by the app, only what a `RemoteViews` can carry.
  The limit is the design constraint, and the story says what was possible.

---

## AV2-9.6 — Copy · `S`

**Owner: no AI sloppiness, no em dashes, in any text the user reads.**

Five confirmed in S017's own new copy: the widget manager's intro, the scroll
caption, the "applies everywhere" caption, the no-boards line, and the
screensaver's explanation. The rule is user-facing strings only; the house
comment style is unchanged and stays.

- **T1** Sweep every user-facing string added in S016 and S017.
- **T2** A test that fails on an em dash in a user-facing string, so this cannot
  come back.

---

## AV2-9.7 — Confirm the widget work fits the post-iOS model · `S`

The user and station model changed during iOS development: `boards` on the
profile, `BoardConfig` on the board, `UserSelection` in SQL keyed by grouping id,
settings device-local per uid. The widget work was written against it but has
not been checked against it deliberately.

- **T1** Confirm the binding key is the grouping id everywhere, including bus
  hubs where pole and hub differ.
- **T2** Confirm a widget survives a board list reconcile that reorders or
  re-adds boards.
- **T3** Confirm sign-out and sign-in as another account does not leave one
  person's widget showing another's station.

### Findings, 2026-09-12

Verification only. Nothing was redesigned and one thing that is wrong was
deliberately not fixed here, because its fix is not small — it is written up as
AV2-9.8 at the bottom.

**How it was checked.** Every site that resolves a binding was read, the
sign-out and sign-in paths were traced end to end, and the parts that are pure
logic were pinned in `android/app/src/test/java/com/stationly/mobile/widget/WidgetModelFitTest.kt`
(11 tests). The parts that need a `Context` cannot be unit tested on this module
— there is no Robolectric and adding one for three tests is a bigger change than
the thing it proves — so the placement rule is guarded STRUCTURALLY instead, by
reading the source the way `UserFacingCopyTest` reads it. The guard was checked
by mutation: rewriting `renderWidget`'s filter to `it.station == boundTo` fails
it, and reverting passes.

---

#### T1 — the binding key is the grouping id everywhere. **HOLDS.**

Six resolution sites, all against `groupingId`:

| site | line |
| --- | --- |
| `DepartureWidgetProvider.renderWidget` — which board | `DepartureWidgetProvider.kt:470` |
| `DepartureWidgetProvider.renderWidget` — the whole hub | `DepartureWidgetProvider.kt:524` |
| `DepartureWidgetProvider.boardRowsFor` | `DepartureWidgetProvider.kt:763` |
| `DepartureWidgetProvider.stepPlatform` | `DepartureWidgetProvider.kt:803` |
| `WidgetConfigureActivity.placedWidgets` | `WidgetConfigureActivity.kt:313` |
| `WidgetConfigureActivity.hubs` / `ConfigureScreen` | `WidgetConfigureActivity.kt:332`, `:768` |

The two entry points that come from OUTSIDE the widget package pass a hub as
well, and that was the part worth checking rather than assuming: the shared home
screen keys its cards on `currentSelections.map { it.groupingId }`
(`SummaryScreen.kt:310`) and `HomeSettingsScreen` builds its rows from
`groupBy { it.groupingId }` (`HomeSettingsScreen.kt:115`), so the `stationId`
that reaches `WidgetPinner.pin` and `onOpenWidgetSettingsForStation`
(`MainActivity.kt:155`, `:169`) is the hub and never a pole.

`WidgetRedrawTargets.hubsFedBy` matches the PUSH against `station`
(`WidgetRedrawTargets.kt:37`), which is the opposite rule and the correct one:
the push names the naptan departures were fetched from. That inversion is what
makes the whole thing hard to eyeball, and it is why the guard is scoped to
comparisons whose other side is a binding-valued name (`boundTo`, `bound`,
`wasBoundTo`) rather than to `.station` in general.

A bus widget bound to Smithwood Close renders both poles. Tested directly, and
the failure is tested too: matching the same rows against `station` returns one
of the two, which is a board that looks entirely correct while showing one side
of the road.

---

#### T2 — a widget survives a board list reconcile. **HOLDS.**

Three separate things have to be true and all three are.

1. **The rebuilt rows carry the same hub.** `reconcileBoards` discards local
   rows and sets cloud ones up (`UserSyncRepository.kt:202-234`); the rows it
   sets up come from `Board.toSelections()`, and `BoardSelection.toUserSelection`
   writes the board's own id into `parentStationId` (`Board.kt:200`), which
   `groupingId` reads back. Order is not part of it, so a reorder on another
   device moves nothing. Tested.
2. **The gap is covered.** The rewrite runs inside `WidgetRestore.during`, and
   `renderWidget` returns without drawing while that flag is raised
   (`DepartureWidgetProvider.kt:489`). The risk in that early return is the
   opposite of the one it fixes — a flag left raised freezes every widget on the
   phone at whatever it last drew, with no error anywhere — so the property
   tested is that `during` lowers it when the block THROWS, which is the case
   nobody exercises by hand.
3. **The gap is closed deterministically.** `UserSyncCoordinator` calls
   `FreshDataNotifier.notifyAll(context)` immediately after `reconcileBoards`
   returns (`UserSyncCoordinator.kt:179`), which is `updateFromStorage` with the
   flag already lowered. So every reconcile ends with a full redraw and a widget
   cannot be left frozen by one. **This is the load-bearing line and it is easy
   to delete by accident**, because it reads like a cache-invalidation nicety.

A board that genuinely disappears renders the honest unbound state and never
borrows another. Tested with the account still holding two renderable boards,
because `firstOrNull` returning null on an empty list proves nothing.

**One narrower divergence, not a widget bug.** The reconcile keys a board on
`(station, line, direction)` (`UserSyncRepository.kt:186`) and its
`filterChanged` comparison (`:227-231`) does not include `parentStationId`. A
board whose HUB changed on another device therefore matches, compares equal, and
is left alone, so the two devices keep different grouping ids for the same queue
forever. It does not break a widget on this device — the binding was made from
this device's own hub list — but it is a silent permanent divergence and belongs
in whatever epic owns the sync diff, not this one.

---

#### T3 — sign out, sign in as another account. **DOES NOT HOLD.**

`widget_prefs` is not namespaced by uid, and **no sign-out path clears it**.
`UserSettings` keys every row it writes `"$base::$uid"`
(`UserSettings.kt:112`); `binding_<id>`, `page_<id>`, `wpin_<id>` and
`wnav_<id>` are keyed by widget id alone. `cleanupAll`
(`StationLifecycleUseCase.kt:205`) clears topics, SQL, the widget CONTENT and
`StationlyPrefs`, and `FirebaseAuthManager.logout` does the same set by hand.
Neither touches the widget's own file — grep confirms nothing outside the widget
package names it.

**What the second account actually sees.** Traced, and it is three different
answers depending on the path:

- **The station name is never the previous person's.** The name is read off the
  resolved `UserSelection` (`DepartureWidgetProvider.kt:673`), never off the
  store, so a binding that resolves to nothing has no name to print. Tested.
- **New account does not track that hub: honestly unbound.** Correct, and
  correct for the right reason — the resolution rule, not the store.
- **New account DOES track that hub: the widget silently re-attaches.** Station
  ids are TfL naptans, shared by everybody, so two Londoners with a King's Cross
  board is ordinary rather than a coincidence. B's home screen then carries a
  live Stationly widget B never placed, showing a station B never chose for it,
  **in A's configuration**: A's nav mode (`wnav_`), A's platform page (`page_`)
  and A's pin (`wpin_`) all survived with the binding. The one code path that
  resets the page and the pin is `WidgetConfigureActivity.commit`, and it fires
  only when the binding STRING changes (`WidgetConfigureActivity.kt:388`). A new
  account with the same hub is not a rebind by that rule, and `commit` is not
  called at all.
- The app's own view drifts with it. `WidgetPlacementProbe.observe`
  (`MainActivity.kt:235`) writes the stale hub into B's per-uid
  `UserSettings.widgets`, so B's station settings screen offers "widget settings"
  for a widget B did not add.

**The worst variant, and the one that does show the previous person's board.**
Two of the four sign-outs tear local state down. The third does not:
`Platform.signOutFromAuthExpiry` on Android is `auth.signOut()` and a log line
(`core/src/androidMain/kotlin/platform/Platform.kt:378`) — no SQL wipe, no
widget update, no storage clear. So a forced sign-out leaves the widget
rendering the signed-out account's board, live, off SQL rows nobody removed. If
somebody else then signs in, their restore wipes SQL INSIDE
`WidgetRestore.during` (`LoginViewModel.kt:406-446`) and the widget's unresolved
binding takes the mid-rewrite early return, so it stays on the previous
account's last render.

**And the login path has no `notifyAll`.** The reconcile path closes its gap
explicitly (`UserSyncCoordinator.kt:179`); the login path ends its
`WidgetRestore.during` block and does nothing after it. What actually corrects
the widget is incidental: the per-board broadcasts `completeSetupAsync` fires
inside the block are delivered asynchronously and usually land after the flag
drops, a line-status push calls `updateFromStorage`, or the next foreground
reconcile does — but the first of those is a race and the other two are FCM and
a 15-minute debounce. The symmetry is the fix, and it is one line.

---

#### Two smaller things found on the way

**The ETA watchdog can be left unarmed by the paths that need it most.**
`scheduleEtaTickWatchdog` is reachable only from inside `updateAppWidget`, and
only under `if (hasSelection)` (`DepartureWidgetProvider.kt:1007`, `:1040`).
The mid-rewrite early return never reaches `updateAppWidget`, so a pass where
EVERY placed widget takes it consumes the pending alarm and schedules no
successor. The widget then stops ticking until an FCM line-status push or a
foreground reconcile redraws it — which is bounded, but the watchdog exists
precisely for when FCM has gone quiet, so the recovery leans on the thing whose
absence is the problem. Single-widget phones are the exposed case.

**The SDUI template is looked up by the first pole.** `renderWidget` reads
`sdui_layout_${selection.station}` (`:650`) where `selection` is the hub's first
board, while the rows it binds are the union across every pole. On a bus hub
that means pole A's template renders pole A and pole B's departures, and a hub
where only the SECOND pole has a stored template falls back to the legacy path.
Harmless today and not a leak — that key lives in `StationlyPrefs`, which the
logout wipe does clear — but it is one more place where "the hub" and "the pole"
are the same variable name on rail and not on a bus.

---

## AV2-9.8 — A widget belongs to the account that placed it · `M`

From AV2-9.7's T3. Split out rather than fixed there because the fix is not
small and the obvious version of it is worse than the bug: clearing
`widget_prefs` on sign-out would un-configure every widget on the home screen of
the very common user who signs out and back in as THEMSELVES, and Android gives
an app no way to re-point a placed widget except by the user opening its gear.

- **T1** Stamp the uid on the binding when it is written, and decide what a
  binding with no uid means (every existing one, so it cannot mean "discard").
- **T2** A binding whose uid is not the signed-in one resolves to the unbound
  state, whatever the hub says — the same hub on two accounts is the case that
  makes this necessary, not an edge.
- **T3** The page, the pin and the nav mode follow the binding: they are already
  reset on a rebind, and an account change is a rebind.
- **T4** Symmetry with the reconcile: the login restore ends with the same full
  redraw `UserSyncCoordinator.kt:179` does, so no widget depends on a broadcast
  race to leave a stale render.
- **T5** `Platform.signOutFromAuthExpiry` on Android tears local state down like
  the other three sign-outs do, or says in one line why it must not. Today it is
  the only one that leaves a signed-out account's board live on the home screen.
- **T6** The ETA watchdog is re-armed on every pass through `updateFromStorage`,
  not only from inside `updateAppWidget`.
