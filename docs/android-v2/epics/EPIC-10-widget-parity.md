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

**Built, S019 — and the flicker was not where any of T3's candidates said.**

*The measurement first, because it is the whole story.* A stack trace at each
redraw entry point, one refresh tap, counted per widget:

| | renders per tap |
|---|---|
| as found | 44 |
| after scoping the refresh fetch | **156** — worse |
| after scoping the broadcast | 132 |
| after the fix below | **4** (two Bank widgets, twice each) |

Two independent causes, and the second one is why the first three rows of that
table changed nothing the owner could see.

**Cause 1 — a redraw storm.** `ProcessPredictionsUseCase` broadcasts
`ACTION_UPDATE_WIDGET` on every processed payload and the receiver had no station
id to go on, so it redrew every placed widget every time. `notifyLineStatus` did
the same per line, and a refresh fetched every tracked stop rather than the one
the tapped widget is bound to — which is exactly the "refreshing one widget is
affecting others" the owner reported separately. `WidgetState` now carries the
naptan, the broadcast carries it, and the receiver draws the widgets showing
THAT board. An 80-second ambient capture afterwards: **zero** unscoped fan-outs.

**Cause 2 — and this was the flashing.** `ViewAnimator.showOnly` animates when
`!mFirstTime || mAnimateFirstTime`, and `mAnimateFirstTime` defaults to TRUE. The
render did `removeAllViews` (which sets `mFirstTime`) and then
`setDisplayedChild(flipper, 0)`, under a comment explaining that index 0 could
not animate. It animated. **Every single redraw played the in-animation** — so
one redraw was already one flash, and no amount of scoping could have fixed it.
`android:animateFirstView="false"` plus not calling `setDisplayedChild` at all on
an ambient redraw is the fix.

**T1, two distinct animations — and it turned out THREE are possible.** AV2-9.5
concluded a directional slide was impossible because `setInAnimation` is not
remotable, so one flipper has one animation pair fixed at inflate time. True, and
the wrong conclusion: the layout can hold **three flippers stacked in one slot**,
one per motion, with exactly one VISIBLE. Choosing a motion is choosing a
flipper. Because the outgoing frame is a fresh copy of what is already on screen,
swapping which flipper is visible is invisible.

- forward step — slides in from the right (`widget_page_in`)
- back step — slides in from the left (`widget_page_back_in`)
- refresh — rises and squashes open from the bottom, a split-flap turning over
  (`widget_refresh_in`), deliberately a third of the height so it cannot be
  mistaken for the full-width slide

All three caught mid-flight on the Pixel and confirmed to travel the right way.

**T2, ambient redraws animate nothing.** A refresh press arms a one-shot flag
(`WidgetMotion.armRefresh`) that the first redraw after it spends. Everything
else — push, minute tick, sync, another widget's refresh — leaves it null and
gets `NONE`. The flag expires after `REFRESH_IN_FLIGHT_CEILING_MS`, so a tap
whose redraw never came does not flip the board minutes later.

**The step is now a FULL redraw, not a partial**, since the motion carries both
frames. It also picks up fresh SQL, so stepping no longer slides onto a board
built whenever the last push landed.

*Still open:* a multi-line station pushes once per line, all naming the same
naptan, so King's Cross redraws its one widget ~6 times per 30s cycle. Silent
now, and wasteful. Coalescing is the fix and it was not attempted.

---

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

**Built, S019. The answer to the open question is: it gets the arrows AND it
does not wait for them.**

**T1, rendered and looked at** — via the Settings preview eye, the only way a CLI
session can make a `DreamService` run (see memory `android-dream-preview`). It
renders correctly, and the platform sort landed on it: Metropolitan Platform 1,
Metropolitan Platform 2, Piccadilly Platform 5, Piccadilly Platform 6, in that
order, where the old soonest-first rule would have read 6, 2, 5, 1.

**T2, it auto-advances.** A screensaver is the one surface nobody is holding. It
is on a desk or a bedside table being read from across a room, and a page control
that only moves when tapped shows one platform of four to somebody who is never
going to walk over and tap it — the widget's behaviour transplanted into a place
where the gesture it assumes does not happen. The referent is the board hanging
over a concourse: it cycles, steadily, and nobody operates it.

`DREAM_PAGE_DWELL_MS` is 8s, and the number is a reading speed: a page is a
header plus up to five departures, a glance takes two to three seconds to find
your line on it, and the eye lands more than once because nobody watches a
screensaver continuously. The chevrons stay for somebody who IS standing there,
and pressing one restarts the dwell rather than fighting it — `page` is a key of
the effect. A prediction push is NOT a key, or a busy station would sit on
platform one forever.

**T3, reachable across a room.** The chevron target goes 44dp → 60dp on the
fullscreen dream only. 44 is the minimum for a phone in the hand and a dock at
arm's length in the dark is not that. It stays 44 on the in-app card, which IS a
phone in the hand.

*Not verified on device:* the auto-advance itself. The preview renders a single
frame and the session's captures cannot span 8 seconds of dream reliably.

---

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

**T1 + the highest-consequence adoption, S019. NOT finished — see what is left.**

**The inventory is smaller than "audit the SDUI surface" suggests, because the
adoption mechanism already exists and is shared.** `SduiConfig.refresh(map)` is
the one place a config map is adopted; it feeds `BoardPolicyStore` (how the board
behaves) and `LinePaletteStore` (what it is painted in). It is called from
`SummaryViewModel` and `LoginViewModel`, both in `composeApp/commonMain` — which
IS the Android app's UI. **So an Android user with the app open is already on
served rules.** The refresh tiers and windows that memory `ios-widget-refresh-policy`
describes are the other big SDUI surface and they are deliberately iOS-only:
README rule 3, Android has FCM and does not need a budget.

**What the audit actually found is a gap in WHERE, not in WHAT.**

`BoardPolicy` is read by `MultiLineBoardProcessor`, `BoardTicker`, `StaleColor`,
`LineStatusRanker` and `SyncPredictionsUseCase` — how long a departed train stays
up and what it says (`departedGraceMs`, `departedLabel`), how deep SQL is written
(`rowReserve`), when a timestamp starts going amber (`freshMs`/`staleMs`), which
line status counts as the worst (`severityOrder`, `redSeverities`). All of it
defaults to compiled values until something calls `refresh`.

On iOS every path with no UI warms itself first (`BackgroundBoardRefresher`). On
Android **the paths with no UI are the ones that run most**:

| surface | ran on | how often |
|---|---|---|
| home-screen widget | compiled defaults | several redraws a minute, per push |
| `FcmMessagingService` prediction sync | compiled `rowReserve` | every push |
| `FcmMessagingService` status handler | compiled `severityOrder` | every status push |
| screensaver | compiled everything | whole session |

The screensaver is the sharpest case: `DreamHost` loaded the config cache for its
COPY and threw the rules away, so it printed served strings while ticking on
compiled behaviour.

**Adopted:** `SduiConfig.ensureLoaded()` — idempotent, guarded by an `adopted`
flag so a widget push can never put a stale cache back on top of a live fetch.
Called from all four surfaces above, and before the SQL write rather than after
it (`rowReserve` decides the shape of what gets stored). `DreamHost` now calls
`SduiConfig.refresh` with the same map it takes its strings from.

**What is left, and why:**

- **A full key-by-key inventory (T1 proper).** What is above is the consequence
  path, traced from `BoardPolicyStore.current` outwards. Nobody has yet diffed
  the backend's served key list against what either client reads, which is the
  only way to find a key the backend sends that Android ignores entirely.
- **T2's ranking beyond the board policy.** Copy, feature gates and the release
  gate were not examined.
- **`support_money`** is switched off by owner decision (memory
  `monetisation-strategy`) and was left alone.
- Consciously not attempted: anything that would DELETE a key. The strategy
  memory is explicit that thirty keys believed dead were live Android.
