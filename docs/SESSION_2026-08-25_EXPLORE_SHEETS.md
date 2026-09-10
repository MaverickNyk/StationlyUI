# Explore sheets, in-app browser, and the dead-card bug

`ios-parity`, 2026-08-25. Home screen network + fares cards, the two sheets
behind them, and the browser they open into.

## 1. What changed, in one line each

| Area | Before | After |
| --- | --- | --- |
| Network status detail | centred `Dialog`, 320dp inner scroll | `ModalBottomSheet`, summary-led, grouped incidents |
| Fares detail | centred `Dialog`, two prose paragraphs | `ModalBottomSheet`, state + countdown + window table |
| Severity semantics | re-implemented per surface | `core`'s `LineStatusRanker`, one authority |
| Link handling (iOS) | `SFSafariViewController` presented over Compose | `InAppBrowserScreen` — `WKWebView` in the Compose tree |
| Card copy | "2 Disruptions / Delays on network" | "3 lines affected / 1 closed · 2 delayed" |

## 2. Bugs fixed

### 2.1 Explore cards became permanently unclickable (the headline bug)

**Symptom.** Open a sheet, tap the TfL link, come back — both explore cards
stopped responding. Unrecoverable without force-quitting.

**Root cause, third diagnosis.** `openUrlInApp` presented an
`SFSafariViewController` over the top-most view controller. That put UIKit
above the Compose host *and* backgrounded the app, mid-sheet-teardown.

Two earlier fixes attacked the timing (dismiss-then-open, then `try/finally`
around `sheetState.hide()`). Both failed because the race was a symptom. The
cause was presenting a controller over Compose at all.

**Fix.** Deleted the whole `openUrlInApp` expect/actual trio. Links are now an
ordinary state change at the app root rendering an ordinary Compose screen.
Nothing is presented, nothing backgrounds, no teardown to race.

> **Diagnostic note for the next person.** The clue was that only the *cards*
> died while the rest of the screen worked. A lingering full-screen window
> would have killed everything. The fares card's `onClick` is never null, so
> the tap was always landing — the fault had to be downstream, in state. Two
> rounds were spent on the wrong layer before reading that signal properly.

### 2.2 `WKWebView` leaked, one per link opened

`handle.goBackAction` closes over the web view; the web view's delegate closes
over the handle. A retain cycle surviving the screen. Now broken in
`DisposableEffect.onDispose` (`stopLoading`, null the delegate and actions).

### 2.3 Double page load on slow connections

The load was in `UIKitView`'s `update` guarded on `view.URL == null`, which is
nil until a load *commits* — two updates before the first byte both passed and
raced. Now `LaunchedEffect(webView, url)`: exactly one load per pair, and it
reacts to `url` changing, which the guard could not.

### 2.4 Bare-host URLs went to the wrong handler

`url.substringBefore(':')` returns the whole string when there is no colon, so
`"tfl.gov.uk"` was classed as a non-web scheme and handed to the system, which
cannot open it either. Now an explicit `startsWith("http://" / "https://")`.

### 2.5 Composition-phase write in `App.kt`

`lastBrowserLink = shown` was assigned inside `AnimatedVisibility`'s content —
a write during composition, with unpredictable ordering, invalidating the scope
it is read from. Moved to `LaunchedEffect(browserLink)`.

### 2.6 Status dialog ranked severity wrongly

The dialog sorted disrupted lines **alphabetically**, so Minor Delays could
outrank a Part Closure, and treated severity as a boolean — a closure and a
two-minute delay painted identical red. Now `LineStatusRanker` throughout:
GREEN/AMBER/RED, worst-first, shared with the widget strip and covered by
`LineStatusRankerTest`.

### 2.7 Good Service rendered TfL's prose

TfL routinely attaches text to a good service. The dialog printed it whenever
non-blank: a paragraph per healthy line, saying nothing, above the disruption
the user came for. Reason text is now dropped for good service at every level.

### 2.8 Shared incidents counted and printed N times

The sub-surface lines share track, so one signal failure arrived as four
identical paragraphs and inflated the card to "4 Disruptions". Incidents are
now grouped on `(severity, reason)`; the count is incidents, the breakdown is
lines.

### 2.9 The card was inert on cold launch

`onClick` was null while `lineStatuses` was empty — exactly when a user taps
it. Now tappable, with a "Checking lines" state.

### 2.10 "Open in browser" opened the wrong page

It handed the system the URL the screen was *opened* with. Follow two links
inside the page and it bounced you back to the start. Now `handle.currentUrl`.

### 2.11 Back arrow one navigation stale

`canGoBack` was written only in `didFinishNavigation`, so it lagged for the
whole of a slow load. Now also refreshed on `didStartProvisionalNavigation`.

## 3. Performance

- **`Incident.tone` was a `get()`** re-running `LineStatusRanker.toneOf` on
  every read. One card reads it three times per composition, on top of the sort
  and the summary. Now resolved once at construction.
- **Card and sheet shared one parse.** The card ran its own
  `startsWith("good service")` count while the sheet did a separate split and
  sort. Both now go through `networkSummary` / `buildStatus`, memoised on
  `(lineStatuses, strings)`.
- **`SEVERITY_WORDS`** is a file-level `val`, not rebuilt per row.
- **12 dead imports** removed from `ExploreSection.kt`.

## 4. Design

Summary-led, not line-led. Two earlier headline attempts were wrong in
opposite directions: counting lines ("2 lines are disrupted") overstates a
shared incident; naming the worst line ("SPECIAL SERVICE / Bus 17") looks
arbitrary, because worst-first ordering earns a line the top *row*, not the
story. The shape is what helps: `3 of your 7 lines are affected` /
`1 closed · 2 delayed`.

- Healthy lines collapse to one expandable row. Not hidden — a user who sees
  only the disrupted line cannot tell whether the rest are fine or failed to
  load.
- Tone rail down the card's leading edge, not a fill: several stacked REDs as
  tints become a wall of colour that distinguishes nothing.
- `ThemeTokens` throughout, replacing raw `MaterialTheme.colorScheme`. Note
  `warning == primary == brandSignage` (`0xFFFFC819`): the middle severity band
  is TfL signage amber, so delays feel like part of the product and closures
  feel like an intrusion.
- Peak fares were painted with `tokens.error`, the same red as a suspended
  line. Nothing has gone wrong when you pay peak. Now amber.
- Severity wording is sentence case via `SEVERITY_WORDS`, SDUI-overridable at
  `explore.status.severity.<key>`. "Bus Service" reads "Replacement buses",
  which matters on an app that also shows real bus departures.
- The browser wears the app's own screen chrome (`CenterAlignedTopAppBar`, back
  arrow, bold 18sp title) and pushes in from the right. Our title wins over the
  page's, which otherwise swapped to "Transport for London | Every Journey
  Matters" once the page settled.

## 5. Files

**New:** `ui/common/PlatformWebView.kt` (+ ios/android actuals),
`ui/common/InAppBrowserScreen.kt`, `ui/summary/components/LineStatusSheet.kt`,
`FareSheet.kt`, `SheetShell.kt`.

**Deleted:** `platform/InAppBrowser.kt` (common + ios + android),
`FareInfoDialog` / `LineStatusDialog` / `LineStatusRow` / `parseLineStatuses`.

## 6. Gotchas worth remembering

- **`@ObjCSignatureOverride`** (from `kotlinx.cinterop`, *not*
  `kotlin.native`) is required on all four `WKNavigationDelegate` methods:
  their selectors differ only in argument labels, which Kotlin erases, so each
  pair collapses into a conflicting overload.
- **A `ModalBottomSheet` is a popup above root content**, so the browser at the
  app root cannot render over an open sheet. The sheets therefore close on the
  way through. That is safe now only because nothing backgrounds any more.
- `SheetStateSync` reads visibility back off `SheetState` and corrects the
  caller's flag. Its `everShown` guard is load-bearing: `currentValue` is
  `Hidden` during a sheet's *entrance* too.

## 7. Not verified on device

Builds are green (`compileKotlinIosArm64`, `compileDebugKotlinAndroid`,
`:core:testDebugUnitTest`) and the app is installed on the iPhone 11. The UI
itself is **unverified**: this setup has no CLI screenshots, Xcode 26's `log`
dropped `--device`, and `devicectl` has no console subcommand, so nothing here
was observed running.

Needs a human pass on:
1. Fares card → See TfL fares → back → tap both cards. (The whole bug.)
2. The sheets' resting height.
3. `IncidentCard`'s `IntrinsicSize.Min` rail against a wordy TfL reason.
4. Light theme — designed against the token set, never seen.
