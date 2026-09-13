# EPIC-10 — The widget behaves like the one on iOS

**Raised by the owner, 2026-09-13, after living with EPIC-09 on the phone.**

EPIC-09 gave the widget its own settings, its own height and a pager. This epic
is about how it BEHAVES: the refresh rule, the motion, the touch targets, and
the two screens either side of it. Most of these have an answer already written
down in `iosApp/StationlyWidget/`, arrived at on a device, and Android is simply
on the older side of a decision iOS already made.

Where that is true the story says so and points at the file, because the
reasoning is worth more than the diff.

---

## The dependency graph

```
   ┌───────────────────────┐   ┌───────────────────────┐
   │ 10.1 refresh: guard   │   │ 10.3 touch targets    │  independent, and the
   │      on CONCURRENCY   │   │      (S)              │  two most visible
   │      not time    (S)  │   └───────────────────────┘
   └───────────┬───────────┘
               │ both feed the motion rule
               ▼
   ┌───────────────────────────────────────┐
   │ 10.2 two animations, manual only,     │
   │      no flicker                 (M)   │
   └───────────────────────────────────────┘

   ┌───────────────────────┐   ┌───────────────────────┐
   │ 10.4 adding is ONE    │   │ 10.5 manager says     │   independent
   │      step        (S)  │   │      what a widget is │
   └───────────────────────┘   └───────────────────────┘

   ┌───────────────────────┐   ┌───────────────────────────────────┐
   │ 10.6 screensaver      │   │ 10.7 SDUI audit             (L)   │
   │      paging      (M)  │   │      the big one, and last        │
   └───────────────────────┘   └───────────────────────────────────┘
```

10.1 before 10.2 because the motion rule has to know what a refresh IS before it
can animate one, and the current answer ("a tap, maybe, unless it was swallowed")
is not something a view can key on.

---

## AV2-10.1 — The refresh button guards concurrency, not time · `S`

**Android is on the side of this decision that iOS abandoned.**
`MANUAL_REFRESH_DEBOUNCE_MS` drops every tap for N seconds after the last
refresh. `WidgetRefreshService.swift:27` records why that was removed on iOS, in
its own words: it "refuses the tap a user makes because they genuinely want
newer numbers — the control did nothing and said nothing, which is
indistinguishable from broken". And it is unnecessary, because the thing worth
preventing was never two refreshes close together, it was two refreshes AT ONCE.

- **T1** Replace the time lockout with an in-flight guard. A tap during a fetch
  coalesces into it; a tap after one completes goes straight through, however
  soon.
- **T2** A ceiling on the guard, so a refresh killed mid-flight cannot leave the
  button permanently inert. iOS uses 12s against a 10s network timeout.
- **T3** The guard must release on EVERY exit, including the early ones. A guard
  that leaks is a button that stops working and never says so.
- **T4** Tests for: tap during flight coalesces, tap after completion runs, a
  stale in-flight stamp expires.

## AV2-10.2 — Two animations, on manual action only, without flicker · `M`

The owner: "only play animation when manually updated or manually left righted
and these 2 are different animations", and when refresh is pressed on a
multi-page board it "does multiple flickerings".

iOS separates three cases in `boardTransition` (`WidgetViews.swift:1244`): a page
move is a directional push on `snappy(0.16)`; a payload under six seconds old is
a refresh the user is watching and gets `refreshFlip`, which is asymmetric —
rows in from the top, out to the bottom; anything older is ambient and must not
move, because nothing arrived and only the clock moved.

- **T1** Two distinct animations, not one shared cross-fade: a page move and a
  refresh must not look alike.
- **T2** Ambient redraws (minute tick, push, watchdog) animate NOTHING.
- **T3** **The flicker.** Find it before styling it. Candidates: the full
  rebuild replacing the flipper's children while an animation is mid-flight; the
  refresh spinner toggling visibility inside the same update; and
  `updateFromStorage` redrawing every widget where `updateOne` would do.
- **T4** Whatever RemoteViews cannot do, the story says so rather than
  approximating it. `setInAnimation` is not remotable, which already ruled out a
  directional page slide in AV2-9.5.

## AV2-10.3 — The touch targets are too small · `S`

**A real bug, not a preference.** The owner: tapping the chevrons "ended up
opening the app". The chevron ink is 28dp wide; a miss lands on
`departure_board`, whose click opens the app. So the arrow silently does the
opposite of what it says.

- **T1** Expand the CLICK AREA without resizing the glyphs. The bar is
  `match_parent` high already, so the room is horizontal: padding, not size.
- **T2** Only when stepping is on. In scroll mode the bar is gone and that area
  must go back to opening the app.
- **T3** Same for the settings and refresh buttons in the header, which are the
  same 30dp-ish targets with the same neighbour.
- **T4** Verify by tapping just inside and just outside each target on the
  device and recording which activity comes up.

## AV2-10.4 — Adding a widget is one step, not two · `S`

Today: place it, choose a station, and then land in the settings page. The
second step is a settings screen nobody asked for at the moment they are trying
to put a widget on their home screen.

- **T1** Placing a widget ends at the home screen once the station is chosen.
- **T2** It starts on that station's DEFAULTS: stepping when the station has
  several platforms, nothing pinned.
- **T3** The settings remain reachable from the gear and from the manager, which
  is where somebody who wants them will look.

## AV2-10.5 — The manager says what a widget is · `S`

The list and the button are right; a first-time reader still has to infer what
they are choosing. AV2-9.2 wrote that copy for the EMPTY state only, so the
person who already has one never sees it.

- **T1** A brief line about what the widget does, above the list, in both
  states.
- **T2** Not a duplicate of the empty state's paragraph. Shorter.

## AV2-10.6 — The screensaver's platforms · `M`

Open question from the owner: does the dream get the same arrows, alongside
scrolling?

It already has the setting (`DreamSettings.platformNav`) and the chevrons, added
in EPIC-09. What it has NOT had is a decision about whether that is right for a
surface read from across a room, nor a single look on hardware.

- **T1** Render it and look. It has been seen exactly once, in scroll mode.
- **T2** Decide whether a dream should auto-advance rather than wait to be
  tapped. A screensaver is the one surface nobody is holding.
- **T3** Whatever wins, the arrows must be reachable: the dream is
  `isInteractive = true`, but a target sized for a phone in the hand is wrong
  for one on a bedside table.

## AV2-10.7 — SDUI audit · `L`

**The big one.** Much of iOS moved to server-driven config during its
development, timings especially, and Android has not been audited against it.
Memory `sdui-config-strategy` says iOS went fully SDUI first and Android is
phase 2; this is phase 2.

- **T1** Inventory: every value iOS reads from SDUI, and what Android does at
  the same point. Refresh tiers and windows, board policy, copy, feature gates.
- **T2** Rank by consequence, not by count. A hardcoded timing that disagrees
  with the backend is worse than a hardcoded string.
- **T3** Adopt the highest-consequence ones. Additive only — the strategy memory
  is explicit that a key believed dead was live Android.
- **T4** Say plainly what was left, and why.
