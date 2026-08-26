# Staff review — in-app browser + explore sheets

Date: 2026-08-26 · Branch: `ios-parity` · Both targets compile clean
(`:composeApp:compileDebugKotlinAndroid`, `:composeApp:compileKotlinIosArm64`).

Scope: the uncommitted `SFSafariViewController` → `InAppBrowserScreen` cutover
and the explore-sheet rework. No behaviour outside those files was touched, and
no public call site changed shape — `StationExploreSection(lineStatuses,
strings)` still compiles exactly as `SummaryScreen` calls it.

---

## 1. Bugs fixed

### iOS: cancelled loads were shown as failures
`PlatformWebView.ios.kt` treated every `didFail…` as an error. WebKit reports
`NSURLErrorCancelled` (-999) for any navigation it supersedes — a server-side
redirect, a second tap before the first settles, and our own `stopLoading()` in
`onDispose`. The "Couldn't load the page" screen therefore covered pages that
were loading perfectly well, most reliably on tfl.gov.uk, which redirects.
Cancellations are now filtered in one place (`reportFailure`).

### iOS: `target="_blank"` links were dead taps
No `WKUIDelegate` was set, so `window.open` / `_blank` navigations asked WebKit
for a new web view, got nothing, and vanished silently. TfL's status pages use
them. The delegate now loads such requests into the existing view, keeping one
screen and one back stack.

### iOS: an unparseable URL left the browser blank forever
`NSURL.URLWithString(url)?.let { … }` silently did nothing on null, leaving a
screen with a permanently spinning progress bar. It now reports a failure, so
the user gets the retry screen.

### Android: the error state was unreachable
`PlatformWebView.android.kt` implemented only `onPageFinished`. It never set
`hasError`, never re-raised `isLoading` on a new navigation, and never cleared
the error on a retry — so on Android a dead connection spun the progress bar
forever and the shared error/retry UI could not be reached at all. Now wired
through `onPageStarted` / `onReceivedError` (**main frame only** — a 404 on an
analytics beacon is not a failed page) / `doUpdateVisitedHistory`.

### Android: the WebView leaked, and ignored a changing `url`
The view was built inside the `AndroidView` factory, which meant `loadUrl` ran
once and could never react to `url`, and nothing ever called `destroy()` or
cleared `handle.goBackAction` — the handle held the view, the client held the
handle. Now built with `remember`, loaded from `LaunchedEffect(webView, url)`,
unwired in `DisposableEffect`, destroyed in `AndroidView(onRelease = …)`.
This mirrors the iOS actual exactly; the two halves had drifted.

### `rememberSheetExit`: a cancelled hide swallowed the whole point of the call
`after?.invoke()` sat *after* the `try/finally`. `sheetState.hide()` is an
animation and can be cancelled rather than completed — the very case the
`finally` was added for — and when it was, the user tapped "See TfL fares", the
sheet closed, and no page ever opened. `after` is now in the `finally` beside
`onDismiss`.

### `rememberSheetExit`: two taps raced the same animation
Nothing guarded re-entry, so a second tap on "Done" (or Done during the link's
exit) launched a second `hide()` coroutine against the same state; the loser was
cancelled with its `after` unrun. Guarded with a single-shot `exiting` flag held
in the remembered lambda.

### App root: the browser slid in empty for one frame
`renderedLink` was written from `LaunchedEffect(browserLink)`, which runs *after*
the frame that starts the enter animation — so the panel entered blank and then
filled. Both states are now set together in the open callback, and the effect is
gone. (The write-during-composition it replaced was worse; this is the third and
correct version.)

### App root: taps fell through the browser to the screen behind it
`InAppBrowserScreen` animates in *over* a still-composed, still-hit-testable home
screen. A tap in the web view's margins reached the board underneath and
navigated the app out from under the page. The `Scaffold` now consumes pointer
events.

### `breakdownPhrase` could return an empty string
Incidents whose severity the ranker tones GREEN (a non-"good service" severity —
"Information", an unknown value) fell out of both buckets. The card rendered a
title over a blank second line, and the sheet's entire summary line disappeared.
There is now an "{n} with notices" bucket, SDUI-overridable like the others.

### `parseBankHolidays` could disable bank holidays entirely
A CSV that was present but yielded nothing parseable (format change, typo,
placeholder) produced an empty set — making every bank holiday a peak-charging
day and quoting peak fares on Christmas Day. It now falls back to the baked-in
list, which is wrong far less often than trusting a string we could not read.

### `computeFareState` could hang the main thread
The "next peak-charging day" search is a `while` loop over an SDUI-supplied
holiday set. A set covering a fortnight would have spun it forever on the main
thread — a frozen home screen with no error and no way out. Capped at 14 days.

---

## 2. Performance

- **The status map was parsed, grouped and sorted twice.** `networkSummary`
  did the whole thing for a two-line card, and `LineStatusSheet` did it again on
  open. New `NetworkStatus` is built once in `StationExploreSection` and handed
  to both. `affectedLines` is resolved at construction rather than re-summed by
  each reader.
- **`isDark` recomputed a luminance every minute.** It sat in the body of a
  composable that `rememberMinuteTick` invalidates on the clock. Now keyed on the
  background colour.
- **`buildNetworkStatus` parsing:** one `indexOf(':')` replaces a `contains` plus
  two full scans per entry; one `partition` replaces a `filter` + `filterNot`
  (two passes and a duplicated predicate); severities are trimmed once at
  construction rather than re-trimmed inside the `groupBy` key.
- **`IncidentCard`** memoises its joined line names and its `humanSeverity`
  lookup, both of which were string work on every recomposition.
- **The dropped `Column`** wrapping the card row in `ExploreSection` was a layout
  node that laid out exactly one child.
- **`WebViewHandle` is `@Stable`; the model types are `@Immutable`** — Compose can
  now skip on them instead of re-reading.

---

## 3. Maintainability

- **`WebViewHandle` state is `private set` behind three report methods**
  (`onStarted`, `onProgressed`, `onFailed`). It was five `internal set`
  properties each actual wrote by hand, which is exactly how Android ended up
  never setting `hasError` and how the "keep the previous title when the new one
  is blank" rule came to exist in two copies. The platforms now report events;
  the handle owns what they mean.
- **`LineBars`** replaces two near-identical bar loops in `LineStatusSheet` that
  had already drifted on height. It also caps at `MAX_LINE_BARS` — the bars are
  fixed-width and the label has `weight(1f)`, so a user following a dozen lines
  had the label squeezed off its own row.
- **`String.withCount(n)`** replaces seven copies of `.replace("{n}", …)`.
- **`isWebUrl`** and `BrowserLink` lifted out of the `App` composable body.
- **`ErrorState`** extracted from `InAppBrowserScreen`'s content lambda.
- **`incidentCount` deleted** from `NetworkSummary` — nothing read it.
- **Accessibility:** `Role.Button` on the six `Text`/`Row`s that are buttons, and
  the browser's back arrow now says "Close" when it will close rather than
  lying about going back.
- **`LocalOpenUrl`'s doc corrected** — it still said iOS had no in-app WebView
  and that links went to `SFSafariViewController`, which this branch deleted.
- `StationExploreSection` takes a `modifier`, as every other public composable in
  this package does.

---

## 4. Deliberately not changed

- **No system-back handling** for `InAppBrowserScreen`. There is no common
  `BackHandler` in the Compose version this module pins, and composeApp's Android
  target is dev-only — the shipping Android app has its own `WebViewScreen`. On
  iOS the top-bar arrow and WebKit's edge-swipe cover it. Worth revisiting if
  composeApp ever ships to Android.
- **Fare maths, peak windows and every SDUI key** are untouched, including
  `explore.fares.dialog.link` — the backend contract outlives the widget it was
  named after.
- **`skipPartiallyExpanded` stays off** and the double-safety
  (`rememberSheetExit` + `SheetStateSync`) stays, for the reasons in their own
  KDoc.
