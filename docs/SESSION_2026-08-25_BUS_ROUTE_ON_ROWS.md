# The bus number on every departure — session log

**2026-08-25.** iOS only. `core`, `composeApp`, `iosApp/StationlyWidget`. No
schema change, no backend change, no new setting.

**Tests green** (`:core:testDebugUnitTest`). `:core` and `:composeApp` compile
for `iosArm64`; `iosApp Staging` / `Debug Staging` builds and is installed on
the iPhone 11 against staging.

Android is untouched: `:android:app` renders its board through the legacy
single-selection path and never calls `MultiLineBoardProcessor`, so nothing in
this change reaches it.

---

## 1. What was wrong

A user can track several bus routes at one hub. The board grouped them
correctly — one block per pole — but every row read as a bare destination:

```
Bus 39, 85 Stop W
  Putney Bridge          2 min
  Clapham Junction       4 min
  Kingston               7 min
```

Nothing on the row says which bus to get on. The header lists the routes calling
at the pole, which is a set, not an assignment.

## 2. Why it was wrong — a comment that was believed instead of checked

Three places suppressed the bus prefix on purpose, each citing the same reason:

> Buses never take a client-side prefix: the backend appends the route number to
> the destination itself ("53 Nags Head"), so adding one here would double it up.

**It does not.** The prediction source builds `displayName` from `towards`,
falling back to `destinationName`, and cleans it — the route number is never
part of it:

```ts
// stationly-backend/src/services/predictionSources/TubeDlrBusTramMixPredictionSource.ts
private formatDisplayName(arrival: any): string {
    const towards = (arrival.towards || '').trim();
    const raw = (towards && towards.toLowerCase() !== 'null')
        ? towards
        : (arrival.destinationName || '');
    return cleanDestinationName(raw);
}
```

The belief had been copied forward into KMP, into Swift, and into a test that
asserted the wrong behaviour and therefore protected it. That is the actual
lesson here: a rule with a stated reason still needs the reason checked against
the source of truth, and the source of truth was one file away in a sibling repo.

## 3. What it looks like now

```
Bus 39, 85 Stop W
  39  Putney Bridge      2 min
  85  Clapham Junction   4 min
  39  Kingston           7 min
```

Bare number, no brackets, matching TfL's own stop signs. Rail is unchanged and
keeps its bracketed short form (`(Cir.) Edgware Road`) — brackets keep the line
subordinate to the destination, which is what you scan a platform for. On a bus
board the number is the headline, so it leads.

The number is drawn on **every** bus row, including at a pole where a single
route calls. That is deliberate and it is what TfL signs do: a route number is
not a repeated word, it is the first thing the eye looks for.

---

## 4. The changes

### 4.1 `MultiLineBoardProcessor` — one shape rule, one display rule

| Before | After |
| --- | --- |
| `lineShort = if (isBus) "" else shortName(feed.line)` | `lineShort = shortName(feed.line)` for every mode |
| `mixesLines = lines.size > 1 && !isBus` | `mixesLines = lines.size > 1` |
| prefix shape inlined at the one call site | `linePrefixText(lineShort, isBus)`, public |
| — | `Group.isBus`, and `Group.showsLinePrefix = isBus \|\| mixesLines` |

`mixesLines` is now only ever the plain fact its name states: this block holds
more than one line. The first draft of this change overloaded it to mean "draw
the prefix", which made a single-route bus pole claim to mix lines and put a lie
on the wire. The display decision is its own derived property, and the two are
separate questions.

`linePrefixText` is public because three surfaces draw this prefix — the home
board, the dream board and the widget — and "bare on bus, bracketed on rail" in
three places is how three surfaces come to disagree about one row.

### 4.2 `legWhere` — one naming map

The collapsed card's leg built its bus label from the raw feed id
(`feed.line.trim()`) while everything else went through
`LineShortNames.shortName`. The two agree on `39` and disagree on a night route:
`n39` versus `N39`. Now both use `shortName`.

### 4.3 The wire, and the widget

`WidgetGroup.isBus` is new, defaulted `false`. The extension keeps no line
vocabulary of its own by design, so it is told rather than left to re-derive it
from the board's `mode`.

Swift mirrors the same split: `BoardGroup.mixesLines` counts distinct lines
(bus included, honestly), `BoardGroup.showsLinePrefix` is the derived decision,
and `DotMatrixRow` takes `isBus` to choose the shape. The extension's own REST
refresh and its legacy fallback grouping both pass `isBus` through, so a board
rebuilt by a refresh tap keeps the numbers a push gave it.

**Backward compatibility.** `isBus` is absent from any board written by an older
build, decoding to `false` — which is exactly the behaviour that shipped before.
A bus board written by the previous build therefore renders unchanged until the
next push or refresh rewrites it, rather than rendering wrongly.

### 4.4 Dream board

The dream is on the legacy single-selection path, where rows carry no
`linePrefix` and there is no `Group`. The route is read off the selection —
correct by construction there, since a dream board is one (station, line,
direction) — and shaped by the shared `linePrefixText`.

Both dream labels are now behind `remember(sel?.mode, sel?.line)`. The dream
recomposes on a minute tick and runs all night on a screensaver; rebuilding two
strings from an SDUI map on every pass bought nothing.

---

## 5. Bug fixed along the way

**`WidgetRefreshService.mapRows` dropped a legitimate bus departure.** Its dedupe
key was `destination_platform_target`, and `seen` is shared across every feed the
refresh walks. Two routes calling at one pole can share a destination and a
minute — the 39 and the 85 both to Clapham Junction — and the second was silently
discarded. The key now includes the line.

Pre-existing, and invisible until rows started naming their route. The KMP-side
dedupe in `SyncPredictionsUseCase` needs no such term: it runs per feed, so a
collision there is genuinely TfL returning one service twice.

---

## 6. What was deliberately left alone

- **The bus block header** still lists the routes calling at the pole
  ("Bus 39, 85 Stop W"). It reads as slightly redundant beside numbered rows,
  but trimming it to the stop label alone would leave an unlettered pole — most
  suburban stops, since TfL letters stops only at multi-stop interchanges — with
  an empty header strip.
- **The hero** already names its own line in its caption ("39 · NEXT
  DEPARTURE"), and it is per-selection, so it cannot be ambiguous.
- **Android.** Frozen at versionCode 2, and on a code path this change does not
  touch.

## 7. Verifying it on device

Open the app from the home screen, not via `devicectl` — a CLI-launched app
reads no Keychain and reports itself signed out. Then:

1. A bus hub with two routes at one pole: every row leads with its number.
2. A bus stop with one route: the number is still there.
3. Any tube station: unchanged, and a shared platform still shows `(Cir.)`.
4. Widget: same three, after a push or a refresh tap.
