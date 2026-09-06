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

### Handoff notes — S013, 2026-09-06

Still to build here, and both were designed but not written:

1. **`requestPinAppWidget`** — "Add to Home Screen" on a station's own settings,
   which places a widget already bound to it. It is the piece that makes the
   whole feature discoverable: today a user has to know the widget gallery
   exists. Guard on `isRequestPinAppWidgetSupported()` and hide the button where
   the launcher says no. Design note in this epic's header.
2. **A "Manage all widgets" affordance inside the picker**, so a user who
   arrived from one widget's gear can reach the others without going into the
   app. One row at the bottom of the picker that clears the target back to
   `INVALID_APPWIDGET_ID`.

---

## AV2-5.3 — Widget updates and placement · `M` · Backlog

**Depends on:** AV2-5.1 **Files:** `DepartureWidgetProvider`, FCM service

### Tasks
- [ ] **a.** FCM-driven redraw targets **only** the widgets bound to the station
      in the push. Today every widget redraws on every push.
- [ ] **b.** Keep the ETA tick watchdog and its `onDisabled` cancellation — it is
      what keeps ETAs honest when FCM goes quiet for ≥90s, and it is the reason
      Android does not need iOS's refresh budget.
- [ ] **c.** Keep the manual-refresh debounce. TfL rate-limits aggressive callers
      and the refresh button has no spam protection of its own.
- [ ] **d.** The placement probe fills `Board.widget` — `@Transient`,
      device-local, re-derived on every foreground, never synced. It is a fact
      about one phone; last-write-wins across devices would make it flap.

### Acceptance criteria
- [ ] A push for station A does not redraw a widget bound to station B.
- [ ] `Board.widget` reflects reality after a widget is added or removed.

### Handoff notes
_(none yet)_

---

## AV2-5.4 — Widget guide · `M` · Backlog

**Depends on:** AV2-5.1, AV2-3.3 **Files:** `WidgetGuideScreen` wiring

### Tasks
- [ ] **a.** Wire `WidgetGuideScreen` and `/sdui/app/widget-guide` on Android.
- [ ] **b.** Confirm the offline fallback (`WidgetGuideDefaults`) renders when
      the config cannot be fetched.
- [ ] **c.** The guide's copy is iOS-shaped — long-press, jiggle mode, the widget
      gallery. **Android's flow is different** and now includes an in-app manager
      that iOS does not have. The copy is server-driven, so this is a config
      change, not a code change: raise it with the owner rather than hardcoding
      Android copy in the client.

### Acceptance criteria
- [ ] The guide renders on Android. Video needs `SduiAssetCache` from AV2-3.3;
      posters work without it, which is the designed fallback.
- [ ] No iOS-only instruction is shown to an Android user.

### Handoff notes
_(none yet)_
