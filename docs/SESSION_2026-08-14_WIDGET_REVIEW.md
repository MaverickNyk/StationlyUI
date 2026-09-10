# Review pass — `iosApp/StationlyWidget/` view layer

_Session 2026-08-14, branch `ios-parity`. Strict review of the widget view layer
after two rounds of typography/layout feedback. Feature reasoning lives in
`IOS_WIDGET_DESIGN.md` §6.3; this is the defect and cleanup log._

**Scope:** `WidgetViews.swift`, `WidgetTheme.swift`, `WidgetPageIntent.swift`.
No behaviour was changed except where a defect is named below.

**Gates:** `xcodebuild` BUILD SUCCEEDED (device + `generic/platform=iOS`), no new
warnings. `:core:testDebugUnitTest` up-to-date (no `:core` changes),
`:composeApp:compileKotlinIosArm64` and `:composeApp:compileDebugKotlinAndroid`
green.

Installed on the iPhone 11 and run through the §3.4 archive loop:
**29 × `Reload success`, 0 × `Unable to unarchive collection`, 0 × `Reload
failure`**, across all three families (large 13 / medium 15 / small 16) and four
boards — including a **bus** hub (`490G00008805`, 7 departures), which is the
path that exercises the bus affinity split and the bus header ladder. Per-family
height budgets re-derived arithmetically (§4).

---

## 1. Bugs fixed

### 1.1 The footer's height floor forgot the cell's own padding — the maker mark was cropped

`LitCell` pads 4pt top and bottom, so a floor of H offers its tenants H − 8. The
floors were written as "tallest thing + a bit" with that padding left out:

| | floor | content box | tallest tenant | |
|---|---|---|---|---|
| small | `max(ago, logo) + 7` = 18 | 10 | logo 11 | **overflows 1pt** |
| medium | `clock + 12` = 27 | 19 | logo 22 | **overflows 3pt** |

`StationlyMark` demanded a hard `.frame(width:height:)`, so it did not shrink —
it overflowed and `LitCell`'s clip took the top and bottom off it. A quietly
cropped logo, worst on small where it is one of only two things in the footer.

Three-part fix, so the arithmetic cannot drift again:

- `BoardMetrics.cornerCellVPad` names the 4pt, and both the `LitCell(vPad:)` call
  sites and the floors now read from it.
- `footerMinHeight` is **derived**: `max(tallest text tenant, logo) + 2·vPad + 2`.
- `StationlyMark` takes `maxWidth/maxHeight`, so it scales to fit instead of
  overflowing. Belt to the braces — a slightly small logo is cosmetic, a sliced
  one is not.
- Maker mark sized to what the footer can actually hold: **22 → 18** on
  medium/large, 11 → 10 on small. It was being clipped to ~19 on medium anyway,
  so this renders *more* of the logo, not less.

`headerMinHeight` got the same guard shape but **deliberately preserves its tuned
numbers** (`max(station + 14, icon + 2·vPad)` → 27/29/31, unchanged). The header
was not overflowing; re-deriving it would have grown it 3–5pt on the wider
families and taken that straight out of the departure rows.

### 1.2 An unreachable branch shipped the exact arrow the design doc forbids

`PlatformPagerHeader.arrow` was `if #available(iOS 17.0, *) { Button } else { glyph }`.

**This extension deploys to iOS 26.0**, not 17 (`project.yml` — the push-enabled
bundle forced it; see `StationlyWidgetBundle`). So the `else` could never run —
and it drew an arrow as *plain content*, which §6 of the design doc records as a
device-proven bug: every non-interactive pixel of a widget belongs to the
widget's own tap target, so that arrow **opens the app** when tapped. Dead code
that is loaded is still loaded. Removed; the arrow is now unconditionally a
`Button`.

Same root cause, same removal, elsewhere:

- `DotMatrixHeader` wrapped `RefreshButton` in `Group { if #available … }`. Had
  it ever failed, the slot would have been reserved and **empty** — a refresh
  control that silently isn't there.
- `@available(iOS 17.0, *)` on `RefreshButton`, `MovePlatformPageIntent`,
  `RefreshBoardIntent` and all seven `#Preview`s. All dead; all removed.
- A code comment claiming "iOS 17 … is also this extension's deployment target"
  was simply **wrong** and is corrected.

### 1.3 `boardTransition` read `Date()` from inside a view body

A widget's `body` runs while WidgetKit archives the *whole* timeline, so `Date()`
returns the build time for all ~20 entries at once. Every entry was told the
payload was seconds old — including the one that will be on screen forty minutes
later.

Nothing visibly broke, because the animation is keyed on a value all entries in a
batch share and so fires at most once per timeline. But a view whose output
depends on when it happened to be archived cannot be reasoned about, and the
question being asked ("is the user watching this land?") is answered exactly by
the entry's own date. Now takes `entryDate:` and uses it.

### 1.4 `LiveAgo` silently right-aligned any alignment that wasn't `.center`

`alignment == .center ? .center : .trailing` on `multilineTextAlignment`, in a
helper that had just gained an `alignment` parameter. A `.leading` call site
would have laid out leading and drawn trailing. Replaced with a total mapping.

### 1.5 A slice indexed as though it were an array

`rowCell` now takes `ArraySlice<DepartureRow>` (§2.4) and indexes from
`startIndex`. `prefix` on an `Array` starts at 0 today, so the bare subscript
worked — it is exactly the out-of-bounds crash that arrives the day the slice
comes from somewhere else.

---

## 2. Performance

All of these land inside WidgetKit's archiving pass, which the existing
measurements identify as the dominant cost of a tap (`StationlyWidget.swift`:
our own data work is 1ms; the archive of ~20 SwiftUI trees per family is not).

| | was | now |
|---|---|---|
| **2.1** `LitCell` clip | `RoundedRectangle(cornerRadius: m.cellRadius, style: .continuous)` built per cell, per entry — with `cellRadius` **0 in all three families** | `Rectangle()`. The clip stays (it guards overflow, §1.1); the always-0 knob and its 12 argument passes are gone |
| **2.2** `DotMatrixStatusStrip.parts` | computed property doing `range(of:)` + 2 slices + 2 trims, read **3×** per body | hoisted to a `let`, computed once |
| **2.3** `HeaderLadder` | `ViewThatFits` measured **5 candidates** even when the ladder had one variant — all five the same string | short-circuits to the single compressing rung |
| **2.4** row identity | `"\(token)-\(page)-\(index)-\(stamp)"` — a `String` formatted per cell (~11), per entry, per widget | a `Hashable` `RowID` struct; `.id()` only ever hashes and compares |
| **2.5** `motionKey` | `map { String($0.page) }.joined(separator: ".") + "-\(stamp)"` | `[Int]` |
| **2.6** row take | `Array(rows.prefix(n))` — a copy per section, per entry | `ArraySlice` |
| **2.7** `stamp` | computed property re-evaluated per cell | hoisted once per section |

---

## 3. Code quality

- **`BoardMetrics.cellRadius` deleted.** A setting that could only ever be wrong:
  §3.1 requires squared cells, and all three families passed 0.
- **`DotMatrixHeader.refreshSlot` / `PlatformPagerHeader.arrowSlot` deleted.**
  Pure aliases for the metric, each carrying a *second copy* of the metric's
  documentation. The reasoning moved onto the field; the call sites read `m.…`.
- **`DotMatrixFooter` builds one `LiveAgo`.** The two arms each constructed their
  own with different arguments, so a change to the element that exists on every
  board had two places to be made. What actually differs — placement and width —
  is now `BoardMetrics.agoWidth` plus one alignment expression.
- **`SkeletonBoardView`'s footer mirrors the real one** column for column,
  including the empty trailing column on small. It is what is on screen
  immediately before the real footer, so a different arrangement is a visible
  jump.
- **`LiveAgo.textAlignment(for:)`** is a named total function rather than an
  inline ternary (§1.4).

---

## 4. Budget check

Re-derived from the final metrics rather than eyeballed. Minimums include the
2pt inter-cell gaps; "per flexible cell" excludes small's pinned footer.

| family | cells | minimums | canvas | surplus | per flexible cell | footer content vs logo |
|---|---|---|---|---|---|---|
| small | 6 | 140.0 | 169 | 29.0 | 5.80 | 12.0 vs 10 ✓ |
| medium | 6 | 156.5 | 169 | 12.5 | 2.08 | 20.0 vs 18 ✓ |
| large | 11 | 294.5 | 376 | 81.5 | 7.41 | 20.0 vs 18 ✓ |

---

## 5. Not done

- **The pixels, as ever.** The archive loop proves the widget *renders*, not that
  it renders correctly — `idevicescreenshot` does not work on this device and a
  home-screen widget cannot be captured programmatically. The two things this
  pass changes visibly are the maker-mark size (22 → 18 on medium/large, no
  longer cropped) and the small footer's height; both want an eye on them.
- **`WidgetData`/`AppGroupStorage` were read but not reviewed.** `sections(_:)`
  allocates a dictionary and two flatMaps per body evaluation, which is ~20× per
  large widget per timeline. It short-circuits for small and medium
  (`count > 1` guard) and the group count is ≤ 4, so it is not worth touching —
  noted so the next reader does not re-derive that.
- **`BoardMetrics` still has 19 stored properties across three call sites.**
  Considered giving the near-invariant ones defaults and rejected: the three
  families read as a table, and hiding small's two exceptions behind defaults
  makes the table lie.
