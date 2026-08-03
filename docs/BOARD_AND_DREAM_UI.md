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
  unlettered stop and collapses both directions into one block.

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
