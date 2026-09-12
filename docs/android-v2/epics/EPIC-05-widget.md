# EPIC-05 — Widget v2 · 16 pts

**Goal.** One widget per station, and — unlike iOS — configurable from inside the
app.

**D3 keeps RemoteViews.** The gap is configuration, not rendering. The existing
758-line `DepartureWidgetProvider` is tuned: FCM-driven redraw, an ETA watchdog
for when FCM goes quiet, exact control over the dot-matrix layout. Do not rewrite
it.

**The rule that does not bend:** *never substitute another station's board.* If a
widget's binding is missing, it says so. It does not guess, and it does not fall
back to "the first station". Android's ability to configure in-app makes this
easier to honour, not optional.

**Exit.** Two widgets on one home screen show two different stations, and the
user can rebind either one without touching the home screen.

**You also own a piece of v1 that AV2-3.5 could not delete.**
`util/HomeConfigStore` and `util/ModeIconCache` survived the cutover for one
reason each: `DepartureWidgetProvider` reads them. Everything else in
`com.stationly.mobile.ui` that is still there is the Daydream's (EPIC-06). The
shared UI has its own equivalents — `HomeConfigCache`, `ModeIconStore` — and
`V1V2StorageContractTest` is the only thing holding the two mode-icon caches to
the same directory and the same filename sanitisation. Retire the v1 halves in
this epic, or say in the handoff why the widget still needs them.

---

## The Android flow (owner-directed, S015)

**"I don't see the widget settings yet — design a good dedicated flow, it's very
different from iPhone."** The settings existed; they were unfindable, and that
was a design fault worth writing down rather than a bug worth quietly fixing.

**What went wrong.** AV2-5.2 added a "Widget stations" row NEXT TO the existing
"Widgets" row, both in Home settings → More. A user looking for widget settings
taps the row called **Widgets**, gets a page explaining what a widget is, and
concludes the app has no widget settings. Two rows for one subject is worse than
either alone, and the more obvious one led to the less useful place.

**The flow now, built around where the want actually forms.**

```
  Station settings --"Add to Home Screen"--+   requestPinAppWidget:
                                           |   the launcher confirms, and the
                                           +-> widget arrives already showing
  Settings > Widgets --"Add a widget"------+   that station. No picker at all.

  Settings > Widgets --> the list of placed widgets --> change any one

  The widget's own gear -----------------------------> change THAT one

  Widget gallery drag -------------------------------> "Which station?"
```

The first row is the point, and it is the one iOS cannot have. The moment
somebody wants a widget is while they are looking at a station — so the action
lives there, and `requestPinAppWidget` means the app can act on it immediately.
Most people should never see a station picker.

The gallery drag still works, because it is how Android users expect to add
widgets. It is simply no longer the only door.

**One destination, not two.** Settings → **Widgets** now goes to the manager on
Android and to the guide on iOS — one row, and it leads to whatever that
platform can actually do. The manager's empty state carries the how-to that the
guide would have given.

**How the station survives the pin.** `requestPinAppWidget` never tells the
caller the new `appWidgetId`, and the success callback does not carry it either.
So `WidgetPinner` leaves the chosen station in `widget_prefs` and the
configuration Activity — which the launcher runs after pinning, because we
declare `android:configure` and deliberately do NOT declare
`configuration_optional` — claims it, binds, and closes without asking. The claim
is read-once and **expires after two minutes**: a stale one would bind the next
gallery-dropped widget to an hour-old choice, which is the silent wrong answer
this package exists to prevent. If it lapses, the widget simply asks. Asking is
always safe.

**Where it degrades, it says so.** A launcher that refuses pin requests
(`isRequestPinAppWidgetSupported == false`) hides the "Add to Home Screen" row
and the "Add a widget" button, and the manager prints the long-press
instructions instead. An action that cannot happen is not a feature switched
off; it is a feature that is absent, and a button that silently fails teaches
the user the app is broken.

---

## The shape of the answer — why Android needs no widget stack

**Raised by the owner: "multiple widgets of different stations — widget stacking
is not an option on Android, so what can be done?"** Recorded here because it is
the design question the whole epic turns on, and the answer is not a workaround.

Stacking is an iOS answer to an iOS constraint. WidgetKit gives one frame many
configurations and lets the user swipe. Android's home screen is a free grid and
has supported **many instances of one provider** since widgets existed, each with
its own `appWidgetId`. **Two stations is two widgets, side by side.** Nothing has
to be invented; what was missing was a way to tell each instance which station it
is for. That is `android:configure` — a configuration Activity the launcher runs
when the widget is dropped, and without whose `RESULT_OK` it does not create the
widget at all.

Once the binding exists, Android is not merely equal to iOS here. It is ahead,
in four ways iOS cannot follow:

| | Android | iOS |
|---|---|---|
| Enumerate placed widgets | `AppWidgetManager.getAppWidgetIds()` | `getCurrentConfigurations` returns `[]` inside a timeline |
| Read a widget's binding | ours, in `widget_prefs` | impossible — the app can never read an AppIntent config |
| **Change it from inside the app** | write the binding, redraw that id | long-press and edit, or nothing |
| Place one pre-bound | `requestPinAppWidget` — "add a widget for this station" as a button on the station | the user must find the widget gallery |

So the user-facing story is: **one widget = one station, added as many times as
you like, and manageable from inside the app.** The last row of that table is the
piece that makes it discoverable — an "Add to home screen" button on a station,
which places a widget already pointed at it. It belongs with **AV2-5.2**, which
already owns the in-app manager; noted here so the two are designed together
rather than the pin arriving as an afterthought.

What is deliberately NOT the answer: a widget that cycles stations on a timer (a
departure board must be readable at a glance, not waited on), and a widget that
shows several stations at once (a 4×3 cell cannot carry two dot-matrix boards
legibly, and the one that got squeezed would be the one you needed).

---

## AV2-5.1 — Per-instance widget binding · `L` · Review (S013)

**Depends on:** AV2-4.3 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.5
**Files:** `widget/`, `widget_prefs`, `AndroidManifest.xml`, `res/xml/departure_widget_info.xml`

### Tasks
- [x] **a.** An `appWidgetId`-keyed station binding in `widget_prefs`.
      `WidgetBindingStore`, keyed on `groupingId` — see "why the hub" below.
- [x] **b.** An AppWidget configuration Activity, declared via
      `android:configure`. Plus `widgetFeatures="reconfigurable"` so Android 9+
      can re-open it from the launcher's own widget menu.
- [x] **c.** Clean up on `onDeleted`, **and** a `prune()` backstop for the cases
      `onDeleted` never arrives.
- [x] **d.** An honest empty state, in words that distinguish "you have no
      boards" from "this widget has not been told which one".

### Watch for
Widget ids outlive what you expect. On iOS, reinstalls left phantom widget
registrations that only a **device reboot** cleared, and the only honest count
came from what was actually observed rather than what the system claimed.
Android's `AppWidgetManager.getAppWidgetIds()` is more trustworthy — but treat a
binding whose station no longer exists as unbound, not as an error.

### Acceptance criteria
- [ ] Two widgets, two stations, simultaneously. *(Code complete; **needs a
      human** — see the handoff. Nothing in adb can drag a widget onto a home
      screen.)*
- [ ] Deleting a widget leaves no orphan binding. *(`onDeleted` + `prune`;
      same, needs a placed widget to delete.)*
- [x] A binding pointing at a deleted board renders the empty state, never
      another station's departures. *(`renderWidget` resolves the bound
      `groupingId` against the live selections and passes `isBound = false`
      when it finds nothing; the empty wording is asserted by test.)*

### What changed, and the one line that mattered

`updateFromStorage` read `selections.first()` and pushed that board to **every**
widget id. Two widgets meant two copies of one station: the widget could not be
wrong about which stop it showed, because it was never right about a particular
one. It now loops the placed ids and resolves each one's own binding.

`updateWidgetContent` — the helper that took one board and fanned it out to every
id — is **deleted** rather than left unused. A function that shows one station on
every widget is a loaded gun in a file whose single rule is never to show the
wrong stop.

Two smaller things fell out of the same change, both of which were reading the
account's FIRST selection to describe whatever widget was being drawn: the mode
roundel (so a widget could wear another station's icon) and the "has this board
ever loaded" check behind the placeholder wording. Both now take the bound board.
And `updateAppWidget` no longer runs a `getAllSelections()` query of its own —
that was one SQL read per widget per redraw, answering a question it was not
being asked.

### Finding — the AV2-4.3 dependency did not hold

The board listed AV2-5.1 as waiting on AV2-4.3 (cloud state dual-write). It does
not: the binding is `appWidgetId → groupingId` in device-local prefs, and both
halves of that already exist on Android — the ids come from `AppWidgetManager`
and the grouping ids from selections the shared UI has been rendering since
AV2-3.1. Nothing in this story reads or writes cloud state.

The dependency looks inherited from AV2-5.3's `Board.widget` placement probe,
which does touch the board model — and even that is `@Transient` and never
synced. Taken rather than deferred, and said out loud rather than quietly: a
dependency that is wrong costs a sprint if nobody checks it.

The binding also survives the move to `boards` (AV2-4.3), because a board's id is
the same StopArea naptan a `groupingId` already is.

### Why the binding is a hub, not a board

The value in the store is a `UserSelection.groupingId` — the hub the user picked,
which is what the home screen keys a card on and what a person means by "a
station". Keying on the full `(station, line, direction)` triple would bind a
widget to one direction of one line, so a user who later edited that board's
lines would find their widget silently unbound. It also survives the move to the
backend's `boards` array: a board's id is the same StopArea naptan, so the
binding does not have to be rewritten when AV2-4.3 lands.

The widget still RENDERS the first selection of that hub, which is the depth v1
had. Drawing a whole multi-line hub in RemoteViews is a rendering change rather
than a binding one — **AV2-5.3**.

### Handoff notes — S013, 2026-09-06

**Gate GREEN**, XCFramework included: `commonMain` changed, because the unbound
empty state is a new case in `GlobalBoardProcessor.prepareLegacyRows`. Additive
with `isBound: Boolean = true`, so every existing caller — every iOS caller —
keeps its behaviour exactly. iOS has the same state (a configured station since
deleted) and can adopt the wording when it wants it.

**What could not be verified on the Pixel, and why.** The configuration Activity
launches and renders (confirmed by `dumpsys`: task created, window laid out, no
exception). It could not be *photographed*: the phone locked itself, and
unlocking someone's phone is not something to do from a script. More
fundamentally, **adb cannot drag a widget onto a home screen**, so the headline
criterion needs hands.

The device test, in order — it is four minutes:
1. Long-press the home screen → Widgets → Stationly. Drag one out. **The picker
   should appear before the widget does.** Choose a station.
2. Do it again, choose a different station. Two boards, two stations.
3. Back out of the picker on a third. **No widget should be left behind.**
4. Long-press a placed widget → Reconfigure (Android 9+) → pick the other
   station. It should redraw without opening the app.
5. Drag one to Remove, then `adb shell run-as com.stationly.mobile cat
   shared_prefs/widget_prefs.xml` — its `binding_<id>` should be gone.
6. Delete a board in the app that a widget is bound to. That widget must show
   "Which station?", **not** another station's departures.

---

## AV2-5.2 — In-app widget manager · `L` · Review (S013)

**Depends on:** AV2-5.1 **Files:** a new screen, plus the binding store from AV2-5.1

**The Android-only surface, explicitly requested by the owner.**

iOS cannot do this at all. `getCurrentConfigurations` returns `[]` when called
inside `timeline(for:in:)`, and the app can never read a widget's AppIntent
configuration — which is why the iOS flow is long-press-and-edit and why the iOS
docs are full of rules about not guessing. Android has
`AppWidgetManager.getAppWidgetIds()` and can both **read and write** its own
widget bindings.

> ### Owner direction, 2026-09-06 (S013)
> **"There should be a widget settings in the app that can also be opened from
> the widget, and then every widget can be assigned a station."** Both halves
> built. The second half changed a decision: the gear on a widget used to open
> the app's home screen, and now opens that widget's own station picker — you
> tapped the gear ON a widget, so that is the widget you meant.

### Tasks
- [x] **a.** A screen listing every placed widget, each with the station it is
      bound to. It is `WidgetConfigureActivity` in MANAGER mode — started with
      no `appWidgetId`, from Home settings → "Widget stations".
- [x] **b.** Rebind any of them to another of the user's boards.
- [x] **c.** Reflect the change immediately — `DepartureWidgetProvider.updateOne`
      redraws that id and no other.
- [ ] **d.** Handle the empty case: no widgets placed. *(Done as far as it can
      be: the screen explains how to add one, in words. It cannot yet LINK to
      the widget guide, because AV2-5.4 has not wired `WidgetGuideScreen` on
      Android. Swap the paragraph for the link when it does.)*

### Acceptance criteria
- [ ] A user changes a widget's station from inside the app and the home screen
      updates without being touched. *(Needs a placed widget — see AV2-5.1's
      handoff for why adb cannot make one.)*
- [x] The list matches reality after a widget is removed from the home screen.
      *(`getAppWidgetIds()` is the source; the binding store only names them.)*

### One screen, two modes, and why not two screens

The picker (an `appWidgetId` — "which station?") and the manager (no id — "which
widget?") are the same Activity. They are one job with the question asked in a
different order: the launcher already knows which widget and needs the station;
the app knows neither and picks the widget first, then falls through to the same
picker. Two Activities would have meant two copies of the board list, two ways
to write a binding, and a launcher-facing `exported` surface duplicated for no
reason.

### The four ways in

| From | Carries an id? | Opens |
|---|---|---|
| Dragging the widget out of the gallery | yes, from the launcher | picker |
| The **gear** on any placed widget | yes, its own | picker |
| Launcher's widget menu → Reconfigure (API 28+) | yes | picker |
| App → Home settings → **Widget stations** | no | manager |

The board itself still opens the app — except on an unbound widget, where there
is no board to have tapped and "tap to choose" has to land somewhere that can
choose.

### The row is hidden on iOS, not disabled

`HomeSettingsScreen` takes `onManageWidgets: (() -> Unit)? = null` and renders
the row only when it is non-null. Android passes one; iOS passes nothing.

That is not politeness about a missing feature — iOS **cannot** implement this
one. An iOS app can never read a widget's AppIntent configuration
(`getCurrentConfigurations` returns `[]` inside a timeline), so there is no list
to show and no binding to write. A greyed-out row would be advertising something
that is not coming.

### Handoff notes — S013, then reworked in S015

**S013** built the manager as a second settings row. That was the mistake the
owner reported the next day — see "The Android flow" at the top of this epic.

**S015** built `requestPinAppWidget` (item 1 below, now done), collapsed the two
settings rows into one, and added the station-level "Add to Home Screen" that
makes the whole feature discoverable.

Still open:

1. ~~`requestPinAppWidget`~~ **DONE (S015)** — `WidgetPinner`, reached from
   Station settings → "Add to Home Screen" and from the manager's "Add a
   widget".
2. **A "Manage all widgets" affordance inside the picker**, so a user who
   arrived from one widget's gear can reach the others without going into the
   app. One row at the bottom of the picker that switches the mode back to
   Manager — the state is already there, it needs a control.
3. **AV2-5.4's guide has nowhere to be reached from on Android.** The single
   "Widgets" row now goes to the manager, so when the guide is wired it needs a
   row inside the manager rather than a second row in settings. Do not put it
   back in Home settings; that is the arrangement that caused this.

---

## AV2-5.3 — Widget updates and placement · `M` · Review (S016)

**Depends on:** AV2-5.1 **Files:** `DepartureWidgetProvider`, FCM service

### Tasks
- [x] **a.** FCM-driven redraw targets **only** the widgets bound to the station
      in the push. *(Landed 2026-09-08 in `android v2 phase` — a commit made
      between sessions and logged nowhere, which is why the board still listed
      this story as untouched. The rule is now extracted and tested.)*
- [x] **b.** Keep the ETA tick watchdog and its `onDisabled` cancellation.
      *(Untouched and verified present.)*
- [x] **c.** Keep the manual-refresh debounce. *(Untouched;
      `MANUAL_REFRESH_DEBOUNCE_MS` still 15s, still the single gate.)*
- [x] **d.** The placement probe fills `Board.widget`. *(`WidgetPlacementProbe`.
      Android had none at all — see the findings.)*

### Acceptance criteria
- [x] A push for station A does not redraw a widget bound to station B.
      *(`WidgetRedrawTargetsTest`, including the bus case that is invisible on
      rail and the unbound widget that must never be a target.)*
- [x] `Board.widget` reflects reality after a widget is added or removed.
      *(Probed on foreground, on `onDeleted`, and on every bind. Not yet seen on
      hardware — see the handoff.)*

### Findings

#### `Board.widget` was empty on every Android device, and two surfaces read it

`UserSettings.widgets` is filled by `UserStateSync.widgetsObserved`, and the only
caller was iOS's `HomeStateProbe`. So on Android the map was permanently empty,
and two things quietly took that as an answer:

- The station screen's **delete confirmation**, which warns "A widget is showing
  this station. It will stop showing departures." — never shown on Android, so
  deleting a station silently blanked a widget the user was looking at.
- The **`widget.count` SDUI fact**, which reported `0` for a phone covered in
  widgets. Any payload gating on it was resolving against a lie.

Android can answer this exactly, which is the third of the three things this epic
says Android can do and iOS cannot: `getAppWidgetIds()` returns every placed
instance, synchronously, from the system, and `WidgetBindingStore` says what each
one is for. iOS gets nowhere near that — `getCurrentConfigurations` returns `[]`
inside a timeline, and its probe is a stamp the widget writes for the app to read
back.

The one rule the probe must not break: a FAILED look reports **nothing**, because
`widgetsObserved` replaces the map wholesale and an empty map means "the user
removed every widget". Anything that throws leaves the previous observation
standing, which is stale rather than wrong.

#### `board.count` was counting the wrong thing entirely, on both platforms

Found while checking what the probe feeds. `SduiFacts` published
`board.count = UserSettings.widgets.value.size` — the widget PLACEMENT map. So
the fact named "boards the user has saved" answered "how many of your boards are
on a widget", which is zero for every user who has never placed one. A payload
gating "you have no stations yet" on it would have shown that to somebody with
five.

Counted by hub now, from the process-wide selection cache — one per station,
however many lines and directions are ticked inside it, which is what a board is.

### The widget shows the WHOLE station now, 2026-09-12

The story's task list stops at "which widgets redraw". What it did not ask, and
what a device answered, is **what one widget draws**: `renderWidget` resolved its
binding to `selections.first { it.groupingId == bound }` and rendered that one
board. A binding names a HUB, and a hub routinely carries several boards — both
directions of one line, four lines at one interchange — so a user tracking a
station both ways saw half their board with nothing on it to say so.

It looked correct in every screenshot ever taken, because a widget showing one
platform looks exactly like a widget that only knows about one platform. The log
said `with 6 departures` either way, which is why it now says the platform count.

`MultiLineBoardProcessor` is what the home screen and the screensaver already
render from, so the widget feeds it the same way — one `Feed` per (pole, line,
direction) — and the three surfaces cannot disagree about a station any more.
`LineStatusRanker.rotation` picks which line speaks when several share a board,
and `lastUpdated` takes the newest across them so a station does not look stale
because one of its lines is quiet.

**Measured on a Pixel 7 Pro**, giving Hackney Wick a second line:

```
before   Updating widget 6 for Hackney Wick Rail Station with 6 departures
after    … with 6 departures across 3 platform(s)
```

**Depth is the widget's own size**, which is the third thing this epic says
Android can do and iOS cannot. `rowCapForHeight` turns
`OPTION_APPWIDGET_MIN_HEIGHT` into departures-per-platform: 2 on a one-cell
strip, 3 (unchanged, the shipped default) normally, 4 when dragged tall.
WidgetKit gives iOS a family and a layout per family; Android's home screen is a
free grid, so **the same station at two sizes is two different boards and the
resize gesture is the user saying which they meant**.

#### A bound widget flashed "Choose a station", mid-reconcile

Seen once in the release-build log, and it is the rule this package exists to
protect:

```
Updating widget 6 for Hackney Wick Rail Station with 6 departures
Updating widget 6 for Stationly with 0 departures (UNBOUND)   ← here
Updating widget 6 for Hackney Wick Rail Station with 6 departures
```

A cross-device reconcile discards boards and sets them up again; a redraw landing
between those two calls finds the bound hub genuinely absent from the table.
`WidgetRestore` is the existing answer — the login restore has always used it —
and `reconcileBoards` now declares its rewrite the same way. The widget leaves its
last good render on screen instead: stale by a second beats telling somebody
their board is gone.

### Handoff notes — S016, 2026-09-11

**Task (a) was already done and nobody knew.** The commit is `fd9a627`, dated two
days after S013 closed, with no session entry and no board move. Worth a rule:
the board is the state, so a change that lands outside a session still has to be
written into it, or the next session re-derives what it can already see in the
code — which is exactly what happened here.

**What needs a phone:** two widgets on one home screen showing two stations, then
watch `adb logcat -s WidgetPlacement:D` across an app open (`observed N widget(s)
across M board(s)`), a widget drag-off, and a rebind from inside the app. Then
delete a station that has a widget and confirm the dialog now names it.

---

## AV2-5.4 — Widget guide · `M` · Review (S016) — **client done, one config change owed**

---

**Depends on:** AV2-5.1, AV2-3.3 **Files:** `WidgetGuideScreen` wiring

### Tasks
- [x] **a.** Wire `WidgetGuideScreen` and `/sdui/app/widget-guide` on Android.
      *(Already wired by the cutover — the screen, the route and the fetch are
      all `commonMain`, and Android has been running them since AV2-3.5. What is
      missing is a DOOR, and that is deliberate: see (c).)*
- [x] **b.** Confirm the offline fallback renders. *(`WidgetGuideDefaults` is
      the initial state of the screen — it renders first, before the cache and
      before the network, so an offline reader always gets the words. It is also
      iOS-shaped, which is the same finding as (c).)*
- [x] **c.** The guide's copy is iOS-shaped. *(Confirmed by reading the served
      payload, and it is worse than "shaped": every instruction in it is wrong
      on Android. The config change is specified below. **Not applied — it is a
      backend deploy, and the story says to raise it rather than hardcode.**)*

### Acceptance criteria
- [x] The guide renders on Android. *(It does, on the `widget-guide` route.)*
- [x] No iOS-only instruction is shown to an Android user. *(Held by there being
      no Android entry point, which is the honest way to hold it until the
      payload branches. See below.)*

### The config change this owes, exactly

The served payload (`sduiService.getWidgetGuideLayout`) has **no platform
condition on anything**. Every step in it is an iOS gesture:

| Served step | On Android |
|---|---|
| "Touch and hold the Home Screen … until the icons jiggle" | No jiggle. Long-press opens a menu with Widgets in it. |
| "Tap Edit, then Add Widget. Top-left corner." | There is no Edit and no top-left. |
| "Hold the widget, tap Edit Widget" | It is the **gear**, and on Android the app can do it for them. |
| "Drag one onto another … iOS turns the pair into a stack" | No stacks. Two stations is two widgets. |
| "Leave Smart Rotate on" | Does not exist. |
| "Widgets need iOS 26" (the `widget.supported` card) | Correctly hidden — the client answers `yes` for Android. |

**The client is already able to render the fix with no release.** `SduiConditions`
supports `equals` against any fact, and `platform` is published. So the change is:

1. Add `condition: { dependsOn: "platform", operator: "equals", value: "ios" }`
   to the existing `steps` blocks and to the "Stack them" block.
2. Add the Android tab beside them, gated on `platform equals android`:
   - **Add a widget** — "Open Settings → Widgets and tap Add a widget. Stationly
     asks your launcher to place it, already showing the station you pick."
   - The manual route for a launcher that refuses: "Touch and hold an empty part
     of your Home Screen, tap Widgets, find Stationly and drag it out. It will
     ask which station."
   - **Change what a widget shows** — "Tap the gear on the widget, or open
     Settings → Widgets and pick it from the list."
   - **Several stations** — "One widget per station. Add as many as you track."
3. `WidgetGuideDefaults` in the client should be brought in step afterwards,
   since it is the offline floor for the same screen.

Until (1) and (2) land, **the guide has no Android entry point on purpose**. The
manager's own empty state and its `canAdd == false` branch already carry the
how-to, which is why nothing is missing from the Android user's flow — only from
the reading list.

**Do not** add a second settings row for it. That is the exact arrangement S015
removed after the owner reported it, and the reason is at the top of this epic.

### Handoff notes — S016, 2026-09-11

The row to add, once the payload branches, is **inside the manager**
(`WidgetConfigureActivity`'s `WidgetsScreen`, under the placed-widget list), not
in Home settings. It should open `MainActivity` with a deep link to the
`widget-guide` route — the manager is an Activity in `:android:app` and the guide
is a screen in `:composeApp`'s NavHost, so the only way across is an Intent.

Two facts the guide reads are now true on Android for the first time:
`widget.count` (AV2-5.3's probe) and `board.count` (which was counting the widget
placement map on both platforms — see AV2-5.3's findings).
