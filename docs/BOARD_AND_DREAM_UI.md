# Board / Hero / Dream — presentation rules (cross-surface)

_Visual/presentation decisions for the departure board, next-train hero, status row,
and dream surfaces. Reworked on branch `dev_25Apr`. Pairs with `PLATFORM_DISPLAY.md`
(platform string) and `PERFORMANCE.md` (caching)._

The board is rendered on **three surfaces that must stay visually consistent**: the
home screen, the home-screen **widget**, and the **dreams** (cluster + fullscreen,
both via `dream/DreamBoard.kt`). Shared helpers/XML keep them in lockstep — change a
rule in the shared place, not per-surface.

## 1. Station name shows on the **board only**
The dot-matrix board strip (`R.id.line_name`) already shows the station name on every
surface, so the **redundant station-name headline above the hero was removed**:
- Home: removed from the card chrome in `ui/summary/components/Board.kt` (kept the line
  pill + delete button).
- Dream cluster: removed from `dream/DreamSummary.kt` `StationHeader` (kept the line
  pill + live status). Fullscreen dream never had it.

## 2. "Due" is calm — no flashing, no alarming red
Applies to **both** next-train heroes (home `Board.kt` `NextDepartureRow`, dream
`DreamSummary.kt` `NextTrainHero`):
- **No alpha pulse** on the "Due" number (the flashing read as stressful from across a
  room). The slow ambient board glow + the tiny live-dot pulse are kept (they're calm).
- **No urgency border pulse** on the home card (border alpha is static `0.22f`); a
  slightly thicker border when urgent is fine.
- **"Due" / "1 min" use the brand amber** (`primary` / `brandAccent`), **not** a danger
  red (`tokens.due` / `themeColors.danger`). Red on an arrival read as "something's
  wrong" rather than "your train's here."

> Reinforces the long-standing "no Due pulse" rule (see Stationly memory
> `feedback_no_due_pulse`) — it had crept back into the hero cards.

## 3. Status row is a notch smaller than departure rows (all surfaces)
The line-status row is **secondary** info, so it sits below the departures visually:
- Home + widget (shared `res/layout/widget_departure_board.xml`): status text **12sp**
  vs departure rows **15sp** (~0.8 ratio). Row height follows (wrap-content).
- Dream (`dream/DreamBoard.kt`): status baseline `ROW_BASE_SP * 0.8f` (vs departures at
  `ROW_BASE_SP`) — the dream flattens the XML's tiered sizes to a uniform baseline, so
  the ratio is applied there explicitly to match home/widget.

## 4. Dream temperature matches the date/day colour
`dream/DreamWeatherStrip.kt`: the temperature `°` uses **`onCanvas`** (white in dark
mode, black in light) — same as the date — instead of the brand amber. Keeps the
date/temperature line one coherent colour; brand amber is reserved for signage cues.

## 5. The home screen fits one viewport; only the ROWS scroll
`SummaryScreen.kt` + `Board.kt`. The home screen is a plain `Column` with
`verticalScroll`, **not** a `LazyColumn`, and a single station card is capped at
whatever height is left after everything else has taken what it needs:

```
budget = viewport − padding − bottomInset − chrome − Network − gaps
```

`chrome` (banners/promos) and `Network` are **measured** with `onSizeChanged`,
never assumed — promos are dismissible and Network grows with the number of live
disruptions, so any hardcoded allowance is wrong on some device or some session.

**Why not `LazyColumn`.** The board's height has to be derived from what the rest
of the screen needs, and a lazy list cannot supply that: off-screen items are
never composed, so Network's height is unknown *precisely when* the board is too
tall — and sizing the board off that unknown pushes Network further off-screen,
keeping it unmeasured. A circular dependency that never settles. Eager
composition costs little here (a handful of boards, and §6 removed the per-row
draw cost) and `verticalScroll` keeps pull-to-refresh and the iOS rubber-band
working. When the content fits, scrolling has nowhere to go and only bounce is
left.

Three earlier attempts at this were guesses (`viewport − padding`, then 82% of
viewport) and all failed, because the information needed simply was not available
in the lazy structure.

**Inside the card**, the pinned chrome — line pills, hero, station strip, status
strip, clock footer — takes what it needs and the departure rows get the
remainder via `weight(1f, fill = false)`. `fill = false` is load-bearing: a short
board still renders short and only an overflowing one scrolls inside the panel,
with `BoardScrollbar` as the only affordance that says so.

The rows run with `LocalOverscrollFactory provides null`. Without it they
rubber-band *inside* the panel at the last departure, swallowing the gesture, and
the page only moves after you lift and drag again.

**Height stability is a feature.** Two things used to resize the card under the
user's finger and both are gone: the expandable per-line disruption banner
(removed entirely — §8) and a content-sized hero (now pinned at `HERO_HEIGHT`,
with its lower slot reserved whether or not there is a platform chip).

**The board's look is fixed.** The ambient breathing glow, the amber, the
dot-matrix and the roundel are not performance knobs. Scroll cost is bought back
with layout and draw (§6), never by pinning the glow or simplifying the panel.

## 6. The dot grid is a tiled bitmap, not a loop of dots
`ActiveStrip` (`summary/components/Board.kt`) paints the faint unlit-dot texture
behind every departure row. It now does that with a `ShaderBrush` over a
`TileMode.Repeated` `ImageShader` — a single 3dp×3dp tile with one dot at its
centre, baked once per density in `rememberDotGridBrush()`.

It used to be a nested `while` loop issuing one `drawCircle` per dot inside
`drawBehind`. At a 3dp pitch one phone-width row is **~800 draw calls**; a
ten-row card is **~8,000**, re-issued on every repaint of that area. That — not
layout, not the nested scroller — was what made scrolling *inside* a card feel
clanky.

Two things to know:
- **The output is pixel-identical.** The loop started at `pitch / 2` and stepped
  by `pitch`, which is exactly a tile of side `pitch` with the dot centred. This
  changed how the pixels are produced, never which pixels.
- Android was always right here: `departure_board_active_row_background` is a
  tiled pixel bitmap. The Compose port reimplemented a tile as a loop. When
  porting a `<bitmap android:tileMode="repeat">`, reach for a repeated
  `ImageShader`, not a draw loop.

## 7. ONE status strip per board, rotating worst-first
`BoardStatusStrip` (`Board.kt`) + `LineStatusRanker` (core). There used to be one
"Severity : Reason" strip **per tracked line**, rendered inside the scrolling
rows — four lines at King's Cross meant four strips, mostly "Good Service",
pushing the real departures off the panel.

Now one strip, pinned between the departures and the clock footer:
- **Worst first**, ordered by TfL's own `statusSeverity` ordering rather than an
  invented one, then rotating every 8s so nothing is hidden.
- **Good Service never takes a rotation slot.** It appears only when every line
  is healthy, once, for the board — "Good Service" three times says nothing three
  times.
- **Lines sharing one incident are joined**, not repeated: the sub-surface lines
  share track, so "Circle, District  Minor Delays" is one fact, not three.
- An **unrecognised** severity sorts above Good Service — a new TfL wording is
  far more likely to be a new disruption than a new way of being fine.

`STATUS_MARQUEE = true`. It was briefly `false`, and the reason it is safe again
matters: `basicMarquee` animates every frame, and while this strip lived *inside*
the rows' scroller that kept the whole subtree dirty, repainting every row at
60/120fps — which, combined with the ~800 draw calls each row then cost (§6),
is what made the in-card scroll feel clanky. Both causes are fixed: the dot grid
is one tiled draw, and the strip is **outside** the scroller.

**The invariant: nothing that animates every frame may sit inside the rows'
scroller.** The 8s rotation is a state change, not a per-frame animation, which
is why it is fine.

## 8. No disruption banner above the hero
Removed, not hidden. It rendered one expandable "Severity : Reason" card per
disrupted line above the hero, and it was the worst thing on the screen for
stability: it appeared and vanished as statuses changed, and it **expanded on
tap**, so the card — and the whole page — changed height under the user's finger
while they were reading it. A departure board must not move.

Nothing is lost. The same severity and reason are on the rotating status strip
(§7), in the Network cards below, and — when a line has no departures at all — in
the hero itself.

## 9. The hero is per line, and never reflows
`NextDepartureRow` (`Board.kt`). One hero per tracked line, switched by tapping
the line pills above it. `selectedLine == null` means "no explicit choice" and
resolves to whichever line has the soonest departure, so the opening frame is
unchanged.

- **Fixed `HERO_HEIGHT` in every state**, including the "no departures" state, so
  switching lines animates the text and nothing else.
- **Both directions tracked → the hero splits in two**, sharing the width at the
  same height. It merges back to one when the two halves would be identical (no
  departures either side *and* the same status) — but **not** merely because the
  line is disrupted: a part closure often leaves one direction running, and that
  asymmetry is exactly what the user needs to see.
- Split halves are labelled by **direction** (`NORTHBOUND`), not the line — both
  halves share a line, so the direction is the entire reason there are two.
- Status text appears **only when there is no departure**. A closure notice beside
  a live countdown reads as a contradiction. A Good Service *reason* is discarded
  entirely: TfL puts standing advice ("Please offer your seat…") there, and under
  "No departures reported yet" it implies a connection that does not exist.
- Changes animate with a **split-flap** (`SplitFlapText`): per character,
  staggered, sliding up, with cells clipped — clipping is the effect, a real flap
  appears from behind its housing. Only the transition animates; nothing runs
  while the hero sits still, which is what makes it safe on the home screen.
- The ETA column has a **reserved width**. Measured from its own content it
  changes width mid-flip, and the destination was handed space the ETA then drew
  back over.

## 10. iOS home: the station header lives OUTSIDE the panel (2026-08-04)

§1 removed a duplicated station name at a time when a card was one station's one
line. With several stations on the page that inverted: the only place the name
appeared was **inside** the dot-matrix panel, so the two things above the panel —
the line pills and the hero — were unlabelled, and "3 min" belonged to no visible
station.

The roundel and station name now sit in a **card header above the pills**
(`Board.kt` `StationHeader`), and the panel's own strip is gone. Reading order is
station → lines → next train → board. This is iOS-only; Android still renders the
name in the panel and is untouched.

**No expand/collapse chevron.** The whole header row is the control. A chevron
next to a real button (settings) read as a second button, and it competed for the
width the station name needed. State is legible without it: legs mean collapsed,
a board means open.

**The pin marker uses the app accent, never the card's line colour.** `accent`
varies per card, so one shared meaning looked like several different marks.

## 11. iOS home: collapsed stations, ordering, and the height budget

With several stations the page was a long scroll of full-height boards. A station
is now **collapsible**, and collapsed it shows a **leg per direction** — the
soonest departure each way (`MultiLineBoardProcessor.collapsedLegs`).

- Legs group on the **same key the board groups on** (rail: platform; bus: pole),
  so a leg can never name a platform the open board would not show. At an
  ordinary bus stop both sides of the road fall out as separate legs with no
  direction special case.
- Ordered on `StationlyFormatters.arrivalSortKey`, never the `eta` label — the
  label is rounded and deliberately bumped, so ordering by it puts the wrong
  train first exactly when two are close.
- A leg shares ONE row with the station name, the destination and the countdown,
  so its label is **not** the board's header text (`legWhere`): short line name
  always, `Platform` → `Plat.`, **no compass** (the destination beside it already
  says which way), and buses drop the `Bus` prefix.
- Capped at **2** (`MAX_COLLAPSED_LEGS`). Three legs is a board again, at which
  point the user should open it.

**Order is a property of the LIST, not of a station** (2026-08-05).

There was a per-station `pinned` switch before this, and it could not work. Every
station's settings screen offered it, so every station could hold it — and a rank
that everything can claim ranks nothing. Pin all four and you have said exactly
what you said with none.

Ordering moved to home settings, where the whole list is present at once and the
user states the sequence directly: `StationPrefsRepository.order`, a list of
grouping ids under `station_order_v1`. It is **partial by design**. Stations
missing from it keep their natural position AFTER the ones in it, so a station
added since the last reorder appears at the bottom rather than vanishing; ids left
behind by a deleted station are ignored on read, so it never needs pruning to stay
correct, and re-adding a station puts it back where the user had it
(`orderedIds`).

**`startExpanded`** survives as a genuine per-station setting: it expands the card
on EVERY launch, not just the first, and several stations may set it. (It was
`openByDefault` until 2026-08-06 and the stored JSON key still is — see the
`@SerialName` on it.)

**It has no icon of its own, and must not get one back.** Three glyphs were tried
as a separate badge next to the chevron — `UnfoldMore` (reads as a second
chevron), a bolt (says "fast", not "already open"), `OpenInFull` (iOS's
fullscreen glyph, so on a card it promises a fullscreen board) — and the problem
was never the glyph. Two marks side by side about the same axis make the user
work out which one is a control and which one is a setting.

The two facts are not equals. Which way the card is open **right now** is a
state, and the chevron's rotation has always said it. Whether it opens itself is
a **mark on** that state, so it is drawn as one: the same chevron, with the
app accent filled in behind it at 16%. The settings screen's selected "Expanded"
segment carries the identical fill, so the disc on the card and the highlighted
option are one fact in two places, in the same colour, doing the same job. The
disc animates in, because it is set on another screen and would otherwise simply
be present when the user navigates back rather than something they saw happen.

**Which stations are open is a SET, not one id** — the user can open several by
hand — and it is SESSION state, not a preference. What you had open is not
something you configured; the setting is `startExpanded`, and it is the only part
that survives a cold start. `null` (untouched) resolves to the open-by-default
stations, or the first station when none are marked; an EMPTY set means the user
closed everything this session and must survive.

**The height budget is derived, and it is not an equal share** (`boardMaxHeight`).
Collapsed cards cost a known header + legs each and come out of the budget first.
What is left goes to the TOP open board; every other open board is held to
`MIN_BOARD_HEIGHT`. Dividing the viewport equally between three open boards gives
three boards nobody can read, and the user has already said which station matters
by putting it first. Collapsed
cards are budgeted at the MAXIMUM leg count, not the actual one: the real number
moves with live predictions, and budgeting on it would re-flow every open board
each time a train departs — the height churn §5/§8/§9 exist to prevent. This
replaced `MULTI_BOARD_FRACTION` (82% of the viewport per card), which was damage
control for a layout that could not fit.

**The floor is sized in ROWS, not in card height.** `MIN_BOARD_HEIGHT` is derived
from what a card spends before its panel gets anything — the container's own
chrome (`CARD_CHROME_HEIGHT`, §16), header, pills, hero, status strip, footer —
plus `MIN_VISIBLE_ROWS = 3`. The previous 280dp was picked
against the CARD and left the panel one row with several stations open:
technically one viewport, but a departure board showing one departure is not a
departure board. Past the floor the PAGE scrolls, which is the honest outcome of
opening more boards than a screen holds.

## 12. iOS: per-station settings are a SCREEN

`StationSettingsScreen` (route `station/settings`) owns everything about one
card: its boards, its layout, its pin, and its deletion. It replaced a popover on
the card, which could not carry any of the four honestly.

- **The layout choice is drawn, not described.** "Hide next departure" is a
  sentence the user has to simulate; the picker renders both real layouts with
  plausible departures on them — the hero with its line dot, destination and
  countdown, then the black panel with an amber platform header and rows. Grey
  placeholder bars were the first attempt and could not answer the question being
  asked ("what does my card look like"): they show a shape where the user needs
  to see a board. The board-only tile draws the EXTRA rows the hero was costing,
  because that is the entire trade.
  The sample data is fixed and fictional on purpose — it has to render the same
  for a station whose last train has gone, and "No departures" would be
  describing tonight rather than the layout.
- **The boards list groups by LINE, with a row per direction.** Flat, it repeated
  the line name once per direction ("Victoria, Victoria"), which reads as a
  duplicate and gives the user two identical rows to choose between when deleting
  one. The direction is the whole difference, so the direction labels the
  deletable row.
- **A board is named by a priority order, not a field** (`core/util/BoardLabels`,
  tested): compass
  bearing ("Northbound") → the filter the user set ("Via Green Park", "Only
  Brixton") → where the trains actually go ("Towards Putney Bridge", from the
  departures already cached in SQLite). "Inbound"/"Outbound" is the LAST resort,
  not the first — it means "towards the centre of the network", an operational
  fact about TfL rather than about the user's journey, and it told someone
  choosing which of two boards to delete almost nothing. Every rung above it is a
  phrase they would use themselves. The `towards` fallback is read from cache,
  never fetched: this screen must be readable instantly and offline, and where a
  board goes is a stable fact, not a live one.
- **Preferences are per-device and local** (`StationPrefsRepository`, keyed on
  `UserSelection.groupingId`). Pinning and hiding a hero are statements about one
  device's home screen, not about what the user tracks, so they are deliberately
  NOT synced with the selections. One live copy for the whole app, because two
  screens now edit the same preferences and two independently-loaded copies drift
  the moment one writes.
- **Filters are explained here.** The dot-matrix panel no longer carries "VIA
  GREEN PARK" — it is app chrome on a surface that must read as station signage —
  so a filtered board explains itself in its settings row instead.
- **Editing lines enters the picker at the LINE step** and back LEAVES the flow.
  The mode and station were never chosen in that flow — they came from the card —
  so stepping back through them walks the user forwards through screens they did
  not pick and strands them at the mode list.
- The screen re-reads its boards on `ON_RESUME`: the line picker edits through
  its own `SelectionRepository` instance, so returning would otherwise show the
  list as it was before the user's own edit. Same hook, same reason, as the home
  screen.
- Deleting the last board deletes the station, so the screen dismisses itself and
  forgets the preferences — or a re-added station silently comes back pinned.
- **Deleting is two jobs, and the second is easy to forget.**
  `StationLifecycleUseCase.discardStation` tears down the LOCAL state (topics,
  cached rows, the widget) and says nothing to the backend. Without a matching
  `SyncSubscribedStationsUseCase.sync` the deletion is local only: the backend
  still lists the board, and a cloud restore or a second device brings it back.
  That sync now lives in one place instead of being re-implemented at every call
  site, which is how the settings screen came to be missing it.

## 13. A hidden hero leaves the pills with nothing to switch

The line pills select which line the hero shows. With the hero hidden they still
belong on the card — they are the board's legend and carry each line's status dot
— but a tap has nothing to do, and a control that silently does nothing reads as
broken.

Tapping one now floats a hint over the panel pointing at the setting
(`HeroHint`, dismissing itself after `HERO_HINT_MS`). Four rules shape it:

- **It floats; it does not take a row.** Anything occupying layout space would
  push the board down as it appeared and pull it back as it went — under the
  user's finger, milliseconds after they tapped (§5, §8, §9).
- **It is an offer, not an error.** The user hid the hero, quite possibly
  deliberately, so the hint says what they are missing and points at the setting
  rather than correcting them.
- **It is styled against the BOARD, not the theme.** Fixed dark palette, because
  the panel it floats over is near-black in every theme. It used to take
  `colorScheme.surface` and was a white capsule on a black board in light mode —
  legible, but visibly app chrome dropped onto the signage rather than part of
  it.
- **Its label matches where it goes.** The action reads "Settings ›" and opens
  the station's settings screen. It used to read "Turn on" while doing exactly
  the same thing, which promises an immediate switch and then navigates.

The copy says **"next departure"**, never "countdown" — that is what the hero
prints and what the setting is called (`Next dept. + board`). A hint naming a
control the user cannot then find is a dead end.

`AnimatedVisibility` is called from its own composable, not inline: the enclosing
card is a `Column`, and the `ColumnScope` extension wins over the plain overload
and lays the hint out IN the column instead of over the panel.

## 14. iOS home: the top bar adds; it never "edits"

The action button flipped between `+` and a pencil depending on whether any
stations existed, and the pencil was a lie — it opened the same "pick a mode,
pick a station" flow the plus did, which ADDS. Editing is per station now, behind
that station's own settings (§12), so the top bar has one job and says it in a
glyph: **+**. To its RIGHT, on the very edge, a gear opens `HomeSettingsScreen` —
theme, bulk board actions, screensaver, notifications. Order matters: the gear is
the rarer of the two, and the primary action should not be the one pushed
inboard.

The gear is deliberately NOT the sliders glyph (`Tune`) a station card uses.
There are two settings surfaces now, and the same icon on both would say they
lead to the same place. The split is the rule: anything true of ONE station lives
on its card's settings; only cross-station settings live in the gear.

### Kotlin/Native: a ViewModel's `init` runs BEFORE properties declared under it

`StationSettingsViewModel` segfaulted the instant the settings screen opened.
`viewModelScope` is `Dispatchers.Main.immediate`, so the coroutine its `init`
launches runs **synchronously** during construction when the screen composes on
the main thread — at which point any property declared *below* `init` is still
null. The coroutine wrote to one (`_towards`), and Kotlin/Native has no null
check to turn that into an exception: `EXC_BAD_ACCESS` at address 0, with the
faulting frame `MutableStateFlow#<set-value>`.

Declare every property an `init` coroutine touches ABOVE the `init` block. The
crash report is the fast way to find this class of bug — pull it straight off the
device rather than guessing:

```
xcrun devicectl device copy from --device <id> --domain-type systemCrashLogs \
  --source . --destination <dir>
```

Each `.ips` is a JSON header line followed by a JSON body; the body's
`faultingThread` indexes `threads`, and the frames carry demangled Kotlin names.

## 15. One drag belongs to one scroller

The departure rows scroll inside a card inside the home page's scroller. Chaining
the leftover of an inner drag to the outer one was tried and is wrong: reaching
the last departure slid the whole home screen away mid-read — the board throwing
the user off it.

`boardScrollOwnership` decides ownership **once per gesture**: if the rows could
still move when the drag started, everything it produces (including the fling)
stays in the rows and they stop, and bounce, at their end; otherwise nothing is
consumed and the page scrolls as though the card were not scrollable at all.
Lifting the finger clears the decision, so the next touch is judged fresh — which
is what makes a second drag scroll the page.

Deciding per gesture rather than per frame is load-bearing. "Consume whenever the
rows are at their end" deadlocks the page: at the bottom of the rows every
subsequent drag is also leftover, so the page could never move again.

The inner overscroll bounce, previously disabled to make chaining feel smooth, is
back on purpose — it is how iOS says "this is the end of this list", which is the
signal that was missing.

## 16. iOS home: a station card is a CONTAINER, and it moves as one (2026-08-05)

**The problem.** A card was never a container — pills, hero and panel sat straight
on the home canvas with a 20dp gap between stations and nothing else. A gap is not
a boundary: with three stations the page read as one long ribbon, and the only
thing marking a new station was its name, 17sp of text competing with a full
dot-matrix board directly above it.

**Three cues, because one is not enough against a board this loud:**
- a raised surface (`colorScheme.surface`, #161616 on the #0A0A0A canvas) with a
  hairline border, so the card has literal edges;
- a **3dp colour rail** across the top in the station's accent — its line colour,
  or the mode roundel tint when several lines are tracked — fading out to the
  right so it does not read as a progress bar. This is the cue that does the work:
  it is the first thing the eye meets coming down the page, it says "new station"
  before a word is read, and it says WHICH station by colour alone;
- the panel now sits INSIDE something, so the board reads as this station's board
  rather than as the page background.

The container costs ~20dp before any content. That is `CARD_CHROME_HEIGHT` in
`SummaryScreen`, and it is in **both** `MIN_BOARD_HEIGHT` and the collapsed-card
cost — left out, the three-visible-rows floor quietly becomes two and a half.

**Expand/collapse runs on ONE clock.** Every moving part of a card — the body's
height, the collapsed legs, the chevron, the rail's alpha — is driven from a
single `updateTransition(expanded)`. They were briefly three independent
animations that merely shared a duration, which is not the same thing: separate
springs start on the frame each is first composed, so the chevron finished a beat
before the board and the legs arrived after both, and the card read as three parts
reacting to a tap rather than one card responding to it.

Sharing a clock is also what lets the parts differ **on purpose**. Inside the
body, pills → hero → panel enter on `animateEnterExit` at `STAGGER_STEP_MS`
intervals, so the card unfolds top-down like something with a hinge instead of
everything fading up at once. Opening (`EXPAND_MS` 340) leads with height and
holds the fade back, so the board materialises into space already being made for
it; closing (`COLLAPSE_MS` 240) is quicker and drops the panel first, because the
big dark rectangle is what the collapse is *about*. The legs and the body each
take the OTHER's duration, so they cannot cross mid-flight and briefly both show.

**The collapsed legs are computed even while expanded.** Skipping them saved
nothing measurable — it is a `remember` keyed on the data, so it runs when
departures change, not per frame — and it cost the animation its content: the legs
arrived as an empty list on the frame the card closed, so there was nothing to
animate.

## 17. Good Service is a status, and it still has something to say (2026-08-05)

`LineStatusRanker.rotation`'s all-lines-healthy branch built its single entry with
`reason = ""` hard-coded, discarding whatever the feed had sent. The strip
therefore showed two bare words and a dead marquee on the state the board is in
nearly all of the time.

The reason is carried through now, and whichever healthy line actually has
something to say speaks for the board — they are all good, so no one line has a
better claim, and taking the first blindly usually takes an empty one. TfL does
not always ship a description, so `BoardStatusStrip` falls back to
`explore.good_service_sub` ("All lines running normally"), which is the same
phrase the Network section uses for the same state.

**The scrollbar is a hint, not decoration.** It appeared on boards with nothing to
scroll: the test was `maxValue > 0`, and a board that visually fits routinely
overflows by a pixel or two (2dp inter-row spacing against a fractional-density
viewport). The threshold is half a departure row, so it only shows when a row is
genuinely hidden.

**⚠️ A NaN in a Brush aborts the process on Kotlin/Native.** Giving the thumb a
gradient turned a latent divide-by-zero into a hard crash on the first frame of
every launch, and the shape of it is worth knowing before drawing anything else
here:

- `scroll.maxValue` is read at DRAW time, but the decision to draw the bar at all
  is made at COMPOSITION time. Layout updates `maxValue` in the same frame that
  draws; the recomposition that would withdraw the bar happens in the next one.
  A board that has just stopped overflowing therefore gets exactly one draw with
  `maxValue == 0`, and every ratio in the lambda becomes `0/0`.
- **`coerceIn` does not launder NaN.** Every comparison against NaN is false, so
  it falls through both branches and comes out the other side unchanged. The
  same is true of `coerceAtLeast`.
- Skia answers a gradient with a non-finite endpoint by returning a **null
  shader**, and Kotlin/Native answers the null shader by aborting:
  `kotlin.RuntimeException: Can't wrap nullptr`, `SIGABRT`, no useful frame in
  the `.ips` beyond `MetalRedrawer.draw`. The message only appears on the
  console — `xcrun devicectl device process launch --console` prints it, the
  crash log does not.
- A solid `color =` fill takes the same NaN and quietly draws nothing, which is
  why this had never surfaced. **Guard the numbers, not the brush**: bail out of
  the draw lambda when the viewport or the overflow is zero.

**And it is readable at rest (2026-08-06).** It was drawn at 45% of an
already-translucent amber while idle, which on a black panel is an effective
alpha around 0.2 — invisible in daylight. So the answer to "is there more below"
arrived only after the user had guessed and dragged, and a hint that appears in
response to the action it exists to prompt is not a hint. The rail is now always
present, the thumb is lit amber with brighter ends (the panel's own rows fall off
at their edges the same way), and touching it only nudges the brightness. Still
absent entirely when the board fits.

## 18. iOS home: list or carousel (2026-08-06)

The home screen has two arrangements, chosen in home settings
(`StationPrefsRepository.layout`, `home_layout_v1`):

**List** is what shipped before: every station down one scrolling page, all but
the top collapsed to its legs, height shared by the budget in §11.

**Carousel** gives each station a page of its own, swiped left and right, with a
`HorizontalPager` and a row of dots. Nothing is collapsed, because a page with
one station on it has nothing to collapse *for*.

They answer different questions, which is why neither is a default that suits
everyone. A list is "what is happening across my stations" and pays for breadth
in board height; a carousel is "what is happening at THIS station" and spends
the whole screen on one board, at the cost of a swipe to see the next.

### How the carousel moves

Three things, none of them decoration:

- **The pages have depth.** Each page reads its own distance from centre inside
  `graphicsLayer` and applies scale (0.94 → 1), alpha (0.45 → 1) and a 10%
  parallax against the finger, hinged about its trailing edge. Numbers this
  gentle are deliberate — a carousel that scales to 0.8 is a showreel, and this
  one has a live departure board on it that has to stay readable the whole way
  across. All of it is draw-phase: a full board is never recomposed or
  re-laid-out during a swipe.
- **`beyondViewportPageCount = 1`.** At the default of 0, composing the next
  board happens on the frame it first pokes into the viewport, which is the
  first frame of the swipe — every gesture began with a stutter. The list layout
  composes every board anyway, so this costs nothing it was not already paying.
- **⚠️ NEVER `translationX` a page, and never pivot one at its edge.** Both were
  tried on device and both are the same mistake: the pager has already placed
  the page at its own offset, so anything moving it horizontally moves it
  relative to that placement — into its neighbour. The parallax was
  `offset * width * 0.10`, which for the page one to the right pulled it ~39pt
  LEFT over an 18pt gutter, and the next card's edge sat visibly on top of the
  current one. An edge `transformOrigin` compounded it by shrinking each
  neighbour *towards* the middle page. Scale about the CENTRE is the whole
  answer: a shrinking page pulls away from both neighbours, so the gutter grows
  during a swipe instead of closing. Parallax, if ever wanted, has to move the
  page's CONTENT inside a clipped page.
- **The dots track the finger, not the settled page.** Widths are
  `6 + 12 × max(0, 1 - distance)`, which sums to exactly one stretched dot
  across the row however far through a swipe you are — so the indicator moves
  continuously and the row never changes width. Drawn in one `Canvas` reading
  the pager inside the draw lambda.

Consequences worth knowing before touching either:

- **`StationPrefsRepository.order` drives both** — top-to-bottom in the list,
  left-to-right in the carousel. Arranging one arranges the other, and the home
  settings caption says which is which.
- **The page height comes from the same budget**, asked with the counts a
  carousel actually has: one open board, nothing collapsed, plus
  `PAGER_DOTS_BLOCK` as `extraChrome`. It is FIXED for every page — a page that
  resized as it scrolled past would be the same instability §5 exists to
  prevent — so a short station leaves space below itself rather than shrinking.
- **`StationPrefs.startExpanded` means nothing here** and the station settings
  screen hides the control while the carousel is on, rather than offering a
  setting that will not do anything.
- Pull-to-refresh, the promos and the Network section are unchanged and shared:
  only the middle of the page differs.

## 19. iOS: drag to reorder, and why this attempt worked (2026-08-06)

The station list in home settings is dragged into order by holding a row. Four
earlier attempts failed (`IOS_HANDOVER.md` §6d has each one); this is what the
working version does differently, and all three parts are load-bearing:

1. **The detector is on the container, not the row.** A `PointerInputChange`'s
   position is in the coordinates of the node receiving it, so a row that moves
   to follow the finger sees a stationary finger and the drag delivers nothing.
   The container never moves.
2. **It claims the gesture on the `Initial` pass.** This is the part every
   earlier attempt was missing. The ancestor `verticalScroll` detects drags on
   the `Main` pass, and the moment it consumes a move the child's drag ends
   silently — which looks exactly like a row that lifts and then refuses to
   budge. `Initial` runs before `Main` in its entirety, so consuming there means
   the scroller never sees the event at all. The screen also disables its
   scroller while a row is held, which is the second lock rather than the fix.
3. **`pointerInput` is keyed on `Unit`.** Keyed on the list, the first change
   tore the modifier down and cancelled the in-flight gesture. The live list is
   read through `rememberUpdatedState`.

Positions, never reordering: the committed list is frozen for the whole drag
(reordering it reorders the composables under the finger) and each row is placed
at `ListReorder.slotOf(...) * ROW_HEIGHT`. `slotOf` and `moved` are tested
against each other in core precisely because the drop is jump-free only if every
row is already standing where the committed list is about to put it — the
commit then changes no pixel. The lifted row flies to its slot BEFORE the commit,
for the same reason.

One detector owns the tap too. A `Modifier.clickable` beside it would fire on
release however long the press was, so every drop would also open a settings
screen; a release before the row lifts is the tap, and a hold that never moved is
treated as one as well.

**The control matches the layout it arranges.** In a carousel the same stations
are a horizontal strip of page chips dragged left and right, because ordering
side-by-side pages in a top-to-bottom list makes the user map one picture onto
the other every time. Both are `ReorderBox`, which is axis-agnostic — the only
difference is which coordinate counts — so there is one copy of the gesture and
one place for it to be right. The strip scrolls when the chips no longer fit,
and that scroller is disabled during a drag: the `Initial`-pass claim already
handles it, but here the two would be fighting over the same axis rather than
different ones.

## 20. iOS motion: what "premium" is made of (2026-08-06)

Four changes, each fixing something that read as "an app" rather than as iOS:

- **Navigation is a real push.** Both screens used to travel the full width in
  opposite directions, which is Android's shared-axis transition wearing a
  horizontal coat. UIKit moves the two layers by DIFFERENT amounts: the arriving
  screen comes the whole way in, the covered one slides a third and dims. That
  difference is parallax, and it is what makes the new screen look like it is on
  top of the old one instead of the two being on a conveyor belt. The curve is
  UIKit's own (`CubicBezierEasing(0.32, 0.72, 0, 1)`) — fast off the mark, long
  decelerating tail.
- **Segmented controls have one selection that travels** (`ui/common/SegmentedRow`).
  The fill used to appear under the new segment and vanish from the old; two
  things happening at once read as a flicker, one thing moving reads as a
  response. Shared by the theme picker and the expanded/collapsed picker, which
  had already started drifting apart in padding and radius.
- **No ripples** (`ui/common/Press`). Material's ripple is the single loudest
  tell that a Compose app is not native. Card-shaped things shrink under the
  finger (`pressScale`); full-width rows fill with a faint grey
  (`pressHighlight`) — a row spanning the screen scaling down pulls its own
  edges away from the screen edges and reads as the layout breaking.
- **The home screen's add/remove crossfade was 500ms each way**, overlapping for
  all of it, so a new board spent most of its entrance semi-transparent over the
  old one. Now 220ms in behind 160ms out: the replacement arrives faster than
  the thing it replaces leaves.

## 20b. iOS: the user arranges their own board (2026-08-07, cut to two 2026-08-09)

Two settings per station — how deep each platform goes, and which block leads it.
`BoardDisplayPrefs` in **core** holds them, `MultiLineBoardProcessor` applies
them, `BoardArrangementSection` edits them.

### There were three, and the third was removed

`BoardSort` offered **Time / Platform / Destination** in one segmented control.
It went on 2026-08-09, along with the enum, the destination re-sort and its
tests. Three reasons, and the first is the one worth remembering:

- **Its segments acted at two different levels.** Time and Platform ordered the
  BLOCKS; Destination ordered the ROWS inside a block and left block order on
  Time. Three segments reading as three alternatives were two alternatives plus
  an orthogonal switch — and the shape forbade the one combination a user might
  actually want, platforms in number order with destinations grouped inside each.
- **It was inert on any bus board.** A pole has no number, and at the unlettered
  stops most people use it has no label either, so "order by stop" compared
  empty strings and fell back to whatever order the user's selections were in.
  A live control that cannot move anything.
- **The pin already answers what Platform-order was justified by.** "My platform
  is where it was yesterday" is served better by putting it FIRST than by fixing
  it at position four.

Block order is now fixed and not configurable: unassigned last, then the pin,
then whichever block has the soonest train. Rows inside a block are always in
arrival order. Old stored `sort` values decode away harmlessly — the prefs JSON
is read with `ignoreUnknownKeys`.

### The grouping is not one of the settings, and must not become one

Every question asked about this feature comes back to the same answer, so it is
worth stating once. A board groups by platform (rail) or pole (bus) because
that is what a passenger experiences: everything under a header is **one queue,
in one place you can walk to**. "Sort the whole board by destination" would
dissolve that — the rows would be in a true global order and the user would have
to read the platform off every single row to know where to stand.

So each setting operates at exactly ONE level, and neither can reorder rows
across blocks:

| Setting | Level it acts at | Level it leaves alone |
|---|---|---|
| **Rows per platform** (2–5, default 3) | how deep each block goes | order, at both levels |
| **Pin to top** | promotes a whole block | never lifts rows out of one |

### The cap picks WHICH trains, off the TIME-ordered list

Load-bearing, and the one thing to get wrong here. Capping any other ordering
keeps the wrong trains: three alphabetically-first destinations at Green Park
means three Cockfosters trains an hour out and no sign of the Uxbridge one
leaving now. The rows a user is shown must always be the soonest rows. Tested
(`the cap keeps the SOONEST trains — never the alphabetically first ones`).

### A pinned LINE promotes every platform it calls at

The literal reading of "pin my line to the top" is a block of that line's
departures above everything else, and it cannot be built: a line at an
interchange calls at several platforms, so its rows would have to be lifted out
of the blocks they belong to and shown under a header naming no place you can
stand. Instead the pin promotes **every block carrying that line**, in their
usual order among themselves. A pinned platform promotes that one block. Either
way the board is still a set of places with a queue at each.

Three rules around it:

- **Unassigned platforms still sort last, above the pin.** A pin is a
  preference; "you cannot go and stand on a platform TfL has not allocated" is a
  fact. The case this guards is a pinned line whose only departures are
  unallocated.
- **One pin, not a set.** A rank every block can claim ranks nothing — the same
  argument that killed the per-station `pinned` flag (§11), with a sharper edge
  here: pin every block and you have the board you started with.
- **A pin that matches nothing does nothing, and is not pruned.** The platform
  will be back tomorrow; silently forgetting a setting the user made is worse
  than one that waits.

### A collapsed station takes the pin but not the depth

`collapsedLegs` shows the two soonest departures you could catch. A pinned block
leads them and, more importantly, **survives the two-leg cap** — otherwise the
pin quietly does nothing for anyone whose stations are collapsed, which reads as
a broken setting.

The depth cap cannot apply: a leg IS one departure, so there is no depth to
bound. That makes it the one board setting with no effect until a station is
opened, which is why its caption says so ("Applies once the station is open").
A setting that silently does nothing in the state the user is looking at is the
same bug as a control that cannot move anything — it just hides better.

### There is no drawn preview here, unlike the layout picker above it

Deliberate, and it is the one place this screen departs from "draw it, don't
describe it" (§12). The layout picker draws two boards because hiding the
next-departure hero is a shape the user would otherwise have to imagine. These
two are different: the real board is one back-tap away and changes the instant
you get there, so the honest preview is the board itself. Drawing one from the
SQLite cache was the alternative and was rejected — cached ETAs are minutes or
hours old, and a settings screen reading "Brixton 2 min" over stale rows is
indistinguishable from one reading it over live rows.

What carries the meaning instead: **the heading is the readout** ("Show up to 3
per platform"), so the number being set is in the sentence rather than explained
by a caption underneath; the numbers 2–5 drawn under the slider so the whole
scale is visible before you drag; and a pin picker built from the places the
board has **actually shown**, so it can never offer one that does not exist.
Line chips carry their line's colour and platform chips do not, which is what
keeps "Platform 4" and "Victoria" from reading as one flat list.

### Two rules the controls themselves follow

- **"Pin to top" is hidden when there is nothing to promote** — no platform has
  been seen yet and only one line is tracked, so every block on the board
  carries it. A picker whose only option is "None" is a control that cannot do
  anything, and a caption explaining that is worse than the section not being
  there. It reappears if a pin is already set, however the options have since
  shrunk, so a setting in force is always reachable.
- **The section is named for the ACTION, not the outcome.** It read "Show
  first", which forced its off state to be a chip saying "Nothing" — show
  nothing first, which is not a thing anyone asks for. Naming the action gives
  it a natural absence, so the chip is "None". The empty-state caption then says
  what happens WITHOUT a pin, which is the one thing the other branches cannot.
- **The platforms offered are the ones the board has actually shown**
  (`MultiLineBoardProcessor.pinnablePlatforms`, read from the SQLite cache),
  ordered by number rather than by time. Ordered by whose train is soonest, the
  list would rearrange itself under the user's finger every time the board
  refreshed. Unassigned blocks are excluded, because the board sinks them
  regardless of any pin — offering one would be offering a setting guaranteed to
  do nothing.

### A bus hub pins POLES, named by where they go (2026-08-09)

`pinnablePlatforms` cannot serve a bus hub and never could. TfL letters stops
only at multi-stop interchanges, so at an ordinary one every pole's label is
blank and there is nothing to offer — which left the picker showing routes and
nothing else. And a route chip at a hub is usually a no-op: both sides of the
road run the same routes, so pinning one promotes every block, which promotes
none of them. The pin worth having there — *the side going towards Putney
Bridge* — was the one thing unreachable.

`BoardPin.Kind.STOP` + `MultiLineBoardProcessor.pinnableStops` fix it:

- **Matched on the pole's naptan**, which is the board's own group key for buses
  (`groupKeyFor`). Never on the label — blank equals blank, so a label pin would
  match every unlettered pole at once.
- **Shown as its most common destination**, "→ Putney Bridge". A naptan is not
  something any user has seen. Most common rather than soonest: one
  short-terminating service must not rename the side of the road, and the label
  has to still be there tomorrow.
- **Ordered by that label**, so the chips do not move between refreshes — the
  same reason `pickerOrder` exists for rail.
- **Exactly one of `platforms`/`stops` is ever populated.** Both would offer the
  same block twice under two different names.

### `isBus` has one definition now

`mode == "bus"` was written out at four call sites — the board, the panel, the
station settings screen, its ViewModel — agreeing only by coincidence. It is
`MultiLineBoardProcessor.isBus(mode)`, declared beside the grouping rules that
depend on it and tested. A fifth caller spelling it `"Bus"` would have grouped a
bus board by platform, which at an unlettered stop collapses every pole into one
block: the exact bug `groupKeyFor` exists to prevent.

### Things that will bite

- **The widget DOES see this** (since `0720622`), and not by sharing
  `StationPrefs` — that is still in the standard NSUserDefaults suite and still
  invisible to the extension. The board is built in the APP, where the
  preferences live, and only finished blocks cross into the App Group. The
  extension's own REST refresh is the one path that still re-derives rows
  without them.
- **The widget is pushed on the way OUT of the settings screen, not per change**
  (`StationSettingsViewModel.onCleared`). `updateWidget` is
  `IosWidgetManager.refreshAllBoards` — every station rebuilt from SQL — and the
  depth slider fires once per detent crossed, so dragging 2→5 ran three full
  rebuilds during one gesture. The push is also diffed against the arrangement
  as it stood on entry: opening the screen to read it, or moving the slider and
  putting it back, does no work at all. It runs on a scope that outlives the
  ViewModel, because `viewModelScope` is already cancelled by `onCleared`.
- **The home screen needs no push and never did.** `StationPrefsRepository.prefs`
  is one shared `StateFlow` that `SummaryViewModel` exposes directly, and the
  board derives rows with `remember(rendered, isBus, boardPrefs)`. Writing a
  preference IS the redraw.
- **`MAX_ROWS_PER_PLATFORM` on the processor is gone** — the ceiling is
  `BoardDisplayPrefs.rowCap` now, clamped on READ so a value stored by a build
  with different limits cannot render twelve rows. `MIN_BOARD_ROWS` stays a
  constant and is deliberately not user-facing: "how short may the panel get
  before it stops looking like a board" is a layout fact the user cannot reason
  about, and a floor above the ceiling is a state the settings screen would have
  to defend against for nothing.
- **The hero ignores the pin.** It answers "what do I run for" — the soonest
  train across every tracked line — and the pills are how it is switched. Letting
  the pin re-point it too would be one setting quietly doing two jobs.

## 21. Where the shared iOS UI lives (2026-08-06)

Three settings screens, two previews of the same board and two axes of the same
drag had each grown their own copy of the same components. They are now one copy
each, and new work belongs in them rather than beside them:

| File | Owns |
|---|---|
| `ui/common/SettingsUi.kt` | `SettingsSectionLabel`, `SettingsCaption`, `SettingsCard`, `SettingsDivider`, `SettingsActionRow`, `PickerTile` |
| `ui/common/SegmentedRow.kt` | Mutually-exclusive options, selection slides between them |
| `ui/common/MiniBoard.kt` | The board in miniature + the signage palette + `MINI_DEPARTURES` |
| `ui/common/ReorderBox.kt` | Hold-and-drag reordering, either axis, any item type |
| `ui/common/Press.kt` | `pressScale` / `pressHighlight` |
| `ui/station/StationOrder.kt` | What a station looks like while being dragged |
| `ui/station/BoardArrangement.kt` | The sort / rows-per-platform / pin controls (§20b) |
| `ui/summary/StationCarousel.kt` | The pager, its page transform, the dots |
| `ui/summary/HomeBoardBudget.kt` | `MIN_BOARD_HEIGHT`, `boardMaxHeight` and the measurements behind them |

Two rules that fall out of this and are worth keeping:

- **A second caller is the moment to extract, not the moment to copy.** Every
  pair above diverged before anyone noticed — padding, corner radius, label
  alpha, which departures a preview shows. None of those differences were
  decisions.
- **The gesture especially.** A bug fixed in one copy of a drag that took five
  attempts to get working would not have been fixed in the other.

## Consistency contract (don't break)
- The board renders on home + widget + dream from the **same** `widget_departure_*.xml`
  + `GlobalBoardProcessor` + `StationlyFormatters`. Presentation rules belong in those
  shared places, never per-surface.
- The platform header label is **backend-owned** and rendered via
  `StationlyFormatters.platformHeaderText` (see `PLATFORM_DISPLAY.md`).
- No urgency flashing/red on arrivals — calm amber only.
- Dream canvas text (date, temp, station chrome) uses `LocalDreamColors.onCanvas`;
  brand amber is reserved for signage/accents, never plain text.
- A station card never exceeds the viewport, and the page never scrolls to reveal a
  board. Overflow goes inside the panel (§5).
- **Nothing on the home screen may change height on its own.** Banners that come
  and go, content-sized heroes, wrapping subtitles — each moves the board the user
  is reading. Reserve the space or remove the element (§5, §8, §9).
- **The TfL board's appearance is non-negotiable** — glow, amber, dot-matrix, roundel.
  Optimise layout, draw cost and scroll mechanics, never the look. §6 is the model:
  same pixels, ~8,000× fewer draw calls.
- Nothing inside the departure-rows scroller may animate continuously. Anything
  that does invalidates every row above it, every frame (§7).
- **"Inbound"/"Outbound" are never shown to a user.** They are operational
  vocabulary meaning "towards the centre of the network", which tells a passenger
  on a platform nothing they can act on. Compass bearings are shown; anything else
  is dropped (`MultiLineBoardProcessor.compassOrNull`).
- Grouping keys: rail groups by **platform**, buses by **pole naptan**
  (`UserSelection.station`) — never by `stopLetter`, which is null at every
  unlettered stop and collapses both directions into one block. Anything that
  summarises a board (collapsed legs, §11) groups on the SAME key, or it
  describes a platform the board itself would not show.
- **The dot-matrix panel carries station signage only** — platform headers,
  departures, status. App chrome (filter captions, settings affordances) belongs
  on the card around it or on the settings screen (§12).
- **A setting every item can hold cannot express a rank.** Ordering lives on the
  list, never as a per-item "pin to top" (§11).
- **One glyph, one meaning, everywhere it appears.** A mark on a card and the
  control that sets it are one fact in two places; drawing them differently makes
  the user work out that they are related (§11).
- **A state and a mark on that state are not two icons.** When one fact
  qualifies another — "open right now" and "opens by itself" — the second is
  drawn ON the first (fill, tint, weight), never beside it. Two glyphs on one
  axis are two controls as far as the user is concerned (§11).
- **A card's parts move on one clock.** Anything that animates on expand or
  collapse hangs off the card's `updateTransition`, never its own
  `animateFloatAsState` — same duration is not the same clock (§16).
- **A gesture nested inside a scroller must claim its events on the `Initial`
  pass.** Waiting until `Main` is waiting until the scroller has already
  consumed them, and the symptom is a control that responds once and then dies
  (§19).
- **User-facing copy is short, plain and free of em dashes.** A settings row is
  a label and at most one line saying what it does; the reasoning behind it goes
  in a comment, where it is useful, and not on the user's screen.
- **A preview shows the real thing.** Placeholder bars cannot answer "which of
  these layouts do I want", because what differs between layouts is what the
  content does in them (§12, and the screensaver preview in
  `DreamSettingsScreen.PreviewBoard`).

## iOS launch screen
`iosApp/project.yml` → `UILaunchScreen` (`UIColorName: LaunchBackground`,
`UIImageName: LaunchLogo`). Matches Android's `Theme.Stationly.Splash`: the roundel
centred at **140pt** on cream `#FAF7F0`, flipping to `#0A0A0A` in dark via the asset
catalog's dark appearance.

Two things to know:
- `UIImageName` draws the image at its **natural size** and does not scale it, so
  `LaunchLogo` is authored at 140/280/420px. Dropping a 1024px asset in gives a
  full-screen logo.
- `Info.plist` is **generated from `project.yml`** — edit the yml and run
  `iosApp/xcodegen.sh`, or the change silently never reaches the build.
