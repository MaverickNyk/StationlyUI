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

## Consistency contract (don't break)
- The board renders on home + widget + dream from the **same** `widget_departure_*.xml`
  + `GlobalBoardProcessor` + `StationlyFormatters`. Presentation rules belong in those
  shared places, never per-surface.
- The platform header label is **backend-owned** and rendered via
  `StationlyFormatters.platformHeaderText` (see `PLATFORM_DISPLAY.md`).
- No urgency flashing/red on arrivals — calm amber only.
- Dream canvas text (date, temp, station chrome) uses `LocalDreamColors.onCanvas`;
  brand amber is reserved for signage/accents, never plain text.
