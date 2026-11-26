# UI util — Agent context

Small Android-only helpers for the widget and the screens around it:

```
ui/util/
├── BoardFallbackState.kt  Which empty-state message a board shows.
├── NetworkState.kt        Online/offline, for the fallback state.
├── ScrollWrap.kt          Wrap-around scrolling helper.
└── CLAUDE.md              This file.
```

## The tick contract no longer lives here (2026-11-23)

This package used to hold `PredictionTicker.kt`: `tickPredictions`,
`rememberTickedPredictions`, `rememberMinuteTick` and a hard-coded
`DEPARTED_GRACE_MS = 30_000`. It was the v1 tick contract. After the
v2 cutover the only caller left was the widget, and it was the one
surface still ticking by compiled values while the app board and the
screensaver followed the SDUI timing rules iOS introduced
(`board.tick.departedGraceMs`, `retentionMinAgeMs`, `departedLabel`,
`rowReserve`, via `BoardPolicyStore`). It has been DELETED.

Where the contract lives now:

| Piece | Where |
|---|---|
| The tick (grace, retention, "Gone" label, bump) | `core/.../util/BoardTicker.kt` |
| The served values | `core/.../config/BoardPolicy.kt`, `BoardPolicyStore` |
| Minute-aligned Compose tick | `composeApp/.../ui/util/MinuteTick.kt` |
| Flat-list helper for the dream hero | `composeApp/.../ui/util/PredictionTicker.kt` (wraps `BoardTicker.tickRows`) |
| The widget's board | `DepartureWidgetProvider.tickedGroupsFor` (group, `BoardTicker.tick`, cap) |

**Rule:** never add a compiled-in grace period, departed label or reserve on
any surface. Read `BoardPolicyStore.current`, or call `BoardTicker`, which
does.
