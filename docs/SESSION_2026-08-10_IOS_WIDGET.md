# Handover — iOS widget: reachable platforms, a stable board, an honest status

**Date:** 2026-08-10 · **Branch:** `ios-parity` · **Scope:** iOS widget only.
**Design rationale lives in `IOS_WIDGET_DESIGN.md` §6, §6.1, §6.2** — this file is
the change log: what was broken, what got faster, what got tidier.

Files touched: `iosApp/StationlyWidget/{WidgetViews,WidgetPageIntent,AppGroupStorage,AppGroupKeys,DepartureEntry,StationlyWidget}.swift`,
`core/src/iosMain/kotlin/platform/Platform.ios.kt`, four docs.
**Nothing in `commonMain`, `androidMain` or `composeApp` changed.**

---

## 1. Bugs fixed

| # | Bug | Why it happened | Fix |
|---|---|---|---|
| 1 | **The small widget could show two platforms' departures merged, unlabelled.** `firstDepartures(3)` took the first three rows *across blocks*, so a 2-platform station showed 2 rows of Platform 1 and 1 row of Platform 2 with no header saying so. | `SmallWidgetView` was a separate view that never called the shared board. | Deleted. Every family renders `BoardWidgetView` at its own `BoardMetrics`. |
| 2 | **The small widget had no refresh button and no platform pager** — two shipped features invisible on 2×2. | Same root cause as #1. | Same fix; slots re-sized for the narrower canvas (refresh 22pt, arrows 24pt). |
| 3 | **The large widget silently dropped platforms.** With the default `rowCap` 3, a 3rd platform got zero rows and was not drawn — unreachable, with no arrow to say it existed. With `rowCap` ≥ 6 the *first* platform ate all six cells and the second vanished. | `budgetedGroups` spent one whole-board row budget top-down. | Two sections, 3 cells each, each an independent pager. Blocks are grouped by line (bus: by the routes at the pole) and split at the most balanced boundary. |
| 4 | **One arrow was always dead on a 2-platform board.** Paging clamped at both ends and the dead arrow was drawn dim. | Deliberate old design ("a dim arrow says nothing that way"). | The platforms are a ring: `move` clamps what it *reads* and wraps what it *writes*. Both arrows always live; the `2/4` marker carries position. |
| 5 | **The board resized itself as trains departed** — the reported "font size keeps changing". Cells are height-flexible at equal priority, so drawing a cell per departure meant 4 rows at 09:00 and 3 at 09:03 each rendered at a different height. | Cell count was a function of the data. | Fixed skeleton per family (6 cells on small/medium, 11 on large); empty cells hold their places. |
| 6 | **The status strip named the wrong line.** At King's Cross a part-closed Northern was hidden behind a healthy Victoria that merely sorted first — the widget said "Good Service" while the app's own board said otherwise. | `buildBoard` took "the first line with anything to say", with a note that ranking "would need a severity ranker the extension does not have". The extension can't rank; **KMP can**, and `LineStatusRanker` has been in `commonMain` all along. | Worst-first and named, through the home board's own ranker and de-duplication. Verified on device: `Victoria Minor Delays: Minor delays due to train cancellations.` |
| 7 | **A long status would overflow the strip** once the line name was prefixed (`fixedSize()` cannot give width back). | Sized for "Minor Delays", not "Circle, District Minor Delays". | `layoutPriority(1)` + `minimumScaleFactor(0.75)`. Same rule preserved: truncation eats the reason, never the label. |

### Edge cases closed

- **Negative modulo.** Swift's `%` keeps the dividend's sign, so `-1 % 4 == -1` — a left arrow on page 0 would have indexed off the front. Wrapping is a double modulo.
- **Stale page after a platform goes quiet.** Reader and intent normalise identically (clamp), so they can never disagree about which page is current; only the *step* wraps.
- **Leaked App Group keys.** The two new per-section page keys are removed on station deletion (`AppGroupKeys.WIDGET_PAGE_SECTIONS`), which only KMP has an event for.
- **A line with no status record** counts as Good Service rather than an unknown severity, which would otherwise lead the board with an empty sentence.
- **Legacy payloads with no `lineShort`**, and boards with no `feeds`, fall back to a balanced split rather than mis-grouping.
- **`sections(_:)` returns exactly `count`**, so a one-platform large board yields `[[block], []]` and the renderer never branches on shape.

---

## 2. Performance

- **Section state resolved once per render.** `rowCells`, `motionKey` and the renderer each used to read the stored page and re-clamp it against the block count. One `SectionRender` value now answers all three.
- **No new App Group I/O on the render path.** The two extra pages are hoisted into `BoardRenderState` with the existing ones — three integer reads per *timeline build*, not per entry or per view body (the rule `DepartureEntry` documents).
- **Dead code removed:** `firstDepartures(_:)` (no callers left), `SmallWidgetView` (~90 lines), `DotMatrixSectionHeader`, `NoDeparturesRow`.
- **Tighter affinity pass:** `isBus` hoisted out of the per-block loop; `lazy` filters drop the intermediate arrays.
- **Entry counts unchanged**, so the WidgetKit refresh budget is untouched. Measured on device after the change: `read=0ms tick=0–3ms entries=13` per tap.

---

## 3. Code quality

- **One board, three type scales.** Everything that differs between families is data in `BoardMetrics` (`layout`, `statusPolicy`, `size`, `headerPad`, `footerPad`, `refreshSlot`, `arrowSlot`) instead of literals in per-family views. This is what stopped the small family silently missing features.
- **Booleans replaced by intent-carrying types:** `singlePlatform: Bool` → `BoardLayout`; status behaviour → `StatusPolicy`; the pager key → `BoardSection`.
- **`rows(for:sections:)` → `rows(for:)`.** The argument was always `metrics.sectionCount`; passing it made two sources of truth possible.
- **The skeleton draws the real cell count**, so the first data landing does not re-lay the widget out.

---

## 4. Verification

| Check | Result |
|---|---|
| `xcodebuild "iosApp Staging"` (device destination) | **BUILD SUCCEEDED** |
| Partition + ring + cell-budget harness (`swift`, mirrors the shipped code) | **ALL PASS** — incl. the Piccadilly/Victoria interleave and King's Cross's real 5 blocks |
| Device syslog, 3 families | `Reload success` ×5, **0** unarchive/reload failures (see `IOS_WIDGET_DESIGN.md` §3.4) |
| App Group state | `widget_page_…#u`/`#d` hold independent pages → sectioned paging works on device |
| `:android:app:compileStagingDebugKotlin` | **BUILD SUCCESSFUL** |

**Not verified: the pixels.** `idevicescreenshot` cannot reach this iOS 26 device (§ the memory note / `IOS_WIDGET_DESIGN.md`), so layout was verified by state and logs, not by eye. Worth one human look at: the small header with a long station name, and the large board's lower section on a **one-platform** station — which is deliberately drawn empty (per spec) rather than letting the single platform take all six rows.

**Deliberately not ported:** the home board rotates through the remaining disrupted lines every 8s. A widget has no animation loop, and a strip that changed subject on the per-minute timeline is the stepped marquee that was already built and rejected (§3.3). The worst line is the one that changes a journey.
