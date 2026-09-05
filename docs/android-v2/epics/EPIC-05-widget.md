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

---

## AV2-5.1 — Per-instance widget binding · `L` · Backlog

**Depends on:** AV2-4.3 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.5
**Files:** `widget/`, `widget_prefs`, `AndroidManifest.xml`, `res/xml/departure_widget_info.xml`

### Tasks
- [ ] **a.** An `appWidgetId`-keyed station binding in `widget_prefs` (which
      already exists, holding `last_refresh_ms`).
- [ ] **b.** An AppWidget configuration Activity, declared via
      `android:configure` in `departure_widget_info.xml`, for the placement-time
      flow.
- [ ] **c.** Clean up on `onDeleted` — a removed widget's binding must go with
      it, or the store accumulates dead ids forever.
- [ ] **d.** An honest empty state for an unbound widget, in the app's own words.
      Not a guess, and not a blank board.

### Watch for
Widget ids outlive what you expect. On iOS, reinstalls left phantom widget
registrations that only a **device reboot** cleared, and the only honest count
came from what was actually observed rather than what the system claimed.
Android's `AppWidgetManager.getAppWidgetIds()` is more trustworthy — but treat a
binding whose station no longer exists as unbound, not as an error.

### Acceptance criteria
- [ ] Two widgets, two stations, simultaneously.
- [ ] Deleting a widget leaves no orphan binding.
- [ ] A binding pointing at a deleted board renders the empty state, never
      another station's departures.

### Handoff notes
_(none yet)_

---

## AV2-5.2 — In-app widget manager · `L` · Backlog

**Depends on:** AV2-5.1 **Files:** a new screen, plus the binding store from AV2-5.1

**The Android-only surface, explicitly requested by the owner.**

iOS cannot do this at all. `getCurrentConfigurations` returns `[]` when called
inside `timeline(for:in:)`, and the app can never read a widget's AppIntent
configuration — which is why the iOS flow is long-press-and-edit and why the iOS
docs are full of rules about not guessing. Android has
`AppWidgetManager.getAppWidgetIds()` and can both **read and write** its own
widget bindings.

### Tasks
- [ ] **a.** A screen listing every placed widget, each with the station it is
      bound to.
- [ ] **b.** Rebind any of them to another of the user's boards.
- [ ] **c.** Reflect the change immediately — update the binding and push a
      redraw for that `appWidgetId` only.
- [ ] **d.** Handle the empty case: no widgets placed. Point at the widget guide
      (AV2-5.4) rather than showing an empty list.

### Acceptance criteria
- [ ] A user changes a widget's station from inside the app and the home screen
      updates without being touched.
- [ ] The list matches reality after a widget is removed from the home screen.

### Handoff notes
_(none yet)_

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
