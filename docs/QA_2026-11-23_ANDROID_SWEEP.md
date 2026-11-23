# QA sweep, 2026-11-23: Android 2.0 on a Pixel 10 Pro Fold (Android 17)

An end-to-end pass over the Android app on staging: home (carousel and list),
station settings, the add-station flow, edit board, profile, the in-app
browser, home settings, the screensaver, the widget manager and the widgets,
offline, font scale 1.3 and landscape. No crash or ANR was logged.

This file is the record of what was found, what was fixed, and **what each
fix means for iOS**. Most fixes are in shared Kotlin (`core/commonMain`,
`composeApp/commonMain`) and reach iOS on its next XCFramework build without
any Swift change. Where the iOS fix needs Swift, it says so and it has NOT been
made: the iOS app is live (1.1) and is left untouched on purpose.

**Status: committed as `bf6d724` on `dev_android_bring_to_v2`.** Gate green after the code review: core unit tests,
Android app unit tests (124), composeApp unit tests (77), iOS compile of core
and composeApp (`compileKotlinIosSimulatorArm64`), `assembleStagingDebug`.

---

## Fixed

| # | Finding | Fix | Where | iOS |
|---|---|---|---|---|
| 1 | "Nearby" said **"Location off"** for every failure, including a phone with location on that is not in London, and on the Tube it said "stops". | The failure is now classified (`NearbyProblem`: no location / none nearby / unreachable) and worded for its cause and mode ("No stations near you. Type a name to find one"). | `SelectionUiState.kt`, `SelectionViewModel.fetchNearbyStations`, `SelectionScreen.StationScreen` | **Shared.** Same screen on iOS, fixed on next framework build. |
| 1b | "Searching nearby…" spun ~25 s before giving up. | The location wait is capped at 8 s (`LOCATION_WAIT_MS`). | `SelectionViewModel` | **Shared.** |
| 2 | Typing a search while "nearby" was still running kept the spinner over the results, and a late nearby answer could overwrite the search results. | A typed search always wins: the spinner shows only when the box is empty, a nearby answer that lands after the user started typing is dropped, and a slow answer to an older keystroke cannot replace the latest one. | `SelectionViewModel.activeSearchQuery`, `searchStations`, `StationScreen` | **Shared.** |
| 3 | One system **Back** on the last add-station step left the whole flow and threw the picks away; Back from the search step skipped the mode list. | System Back now does what the toolbar arrow does: one step back (directions → station → mode), and leaves only where the arrow leaves. | `SelectionScreen` (`BackHandler`) | **Shared code**, but iOS only delivers an edge-swipe to it if back gestures are enabled for the Compose host controller. Today the arrow is the iOS path, so iOS behaviour is unchanged. |
| 4 | In the in-app browser (Privacy, Terms, fares), system **Back** silently popped the screen underneath, then closed the app, with the page still showing. | System Back now unwinds the page's own history, then closes the browser, same as the arrow. | `InAppBrowserScreen` (`BackHandler`) | Same note as #3. |
| 5 | A widget in **arrows mode** remembered its page by position. Blocks re-sort as buses move, so "Bus 39 2/2" (Clapham Junction) became Putney after a refresh, under the same title. | The widget remembers the BLOCK (platform, or bus pole naptan) and finds it wherever it has sorted to; the position is only the fallback when that block has gone. | `MultiLineBoardProcessor.Row.PlatformHeader.key`, `PlatformPages.indexOfKey/resolve`, `WidgetPageStore` (`pagekey_<id>`), `DepartureWidgetProvider.stepPlatform` | **Needs Swift.** The iOS widget stores an integer page in `WidgetPageIntent.swift` (`AppGroupKeys.page`) and has the same bug. Fix: store `group.key` beside it and resolve by key first, exactly as `PlatformPages.resolve` does. Not done. |
| 6 | Edit board: each route's direction summary read **"Towards · Towards"**. Bus stops send the literal word "Towards" as the direction name. | One helper, `directionSummaryLabel`: compass direction if there is one, else the TERMINUS from `destinations` ("Putney Bridge Station", or "Edgware +1" where it divides), else the next stop, else the label. Not `towards`: that field is the NEXT STOP ("Levana Close"), found on device. | `SelectionScreen` | **Shared.** |
| 7 | Bus routes listed as text: 14, 337, 37, 39, 424, 430, 85, 93. | Routes sort by number, lettered routes after, night (N) routes last. Buses only; rail keeps the backend's order. Applied where the list is stored, so "Select all" fills its quota in the order the user reads. | new `core/util/BusRouteOrder.kt` (+ test), `SelectionViewModel.inPickerOrder` | **Shared.** |
| 9 | Both directions of a route at an unlettered stop were headed **"Bus 39"**, on the board, the widget and the screensaver. | An unlettered bus header names where that side goes: its terminus when every route at the pole shares one (**"Bus 39 → Putney Bridge Station"**), else the next stop as the stop flag prints it (**"Bus 39, 639 towards Church Road"**). Routes are listed by number so the header does not reorder as buses move. Lettered stops keep "Bus 39 Stop W". The ladder drops the "→ …"/"towards …" part only as its last rung. Data: `Feed.terminus` = `UserSelection.directionDestinations` when it holds one; `Feed.nextStop` = `UserSelection.directionTowards`, which despite its name (and its KDoc in `Models.kt`) holds the next stop. | `MultiLineBoardProcessor.headerFor`, `Feed.terminus/nextStop`, `busRouteList`, `headerVariants`; every `Feed(...)` builder (Board ×2, Summary, Dream, StationSettings, widget ×2, WidgetConfigure, `Platform.ios.kt`) | **Shared**, including the iOS widget's feeds (`Platform.ios.kt`). Boards saved before those fields existed have them blank and keep plain "Bus 39". |
| 12 | At font scale 1.3 the widget's bottom departure row looked clipped. | `BoardProportions.LINE` 1.32 → 1.42, so a STRETCHED row is never sized under its text (test: every size × 1.15/1.3/1.5). **Re-tested on device:** at 1.3 both widgets correctly switch to the scrolling list, and what looks clipped is the list's last visible row cut at its edge; swiping the list scrolls it. So the original sighting was most likely that, not a squeezed row. The LINE change stays as a safety margin. Open polish: a fading bottom edge would make a cut-off list row read as "scrolls" rather than "broken". | `BoardProportions.kt`, `BoardProportionsTest` | Android widget only. |

### Build change
`composeApp/build.gradle.kts` now declares
`org.jetbrains.compose.ui:ui-backhandler:1.8.0` in `commonMain`. It was already
in the build at runtime via navigation-compose; common code needs it at compile
time for `BackHandler`. **Keep it on the same version as the
`org.jetbrains.compose` plugin.**

### Verified on device (Pixel 10 Pro Fold, outer screen, staging, 2026-11-23 13:05)
| Check | Result |
|---|---|
| 1 Nearby gives up with the right wording | Pass: 8 s, "Couldn't find your location. Type a name to search" |
| 2 Typing during the spinner | Pass: results at once; still there after the location timeout |
| 3 System Back in add-station | Pass: directions → station → mode → home |
| 4 System Back in the in-app browser | Pass: closes the page, stays on Profile |
| 5 Edit Bus Board summary | Pass: "Putney Bridge Station · Clapham Junction Stn / the Falcon" |
| 6 Bus headers | Pass on board and widget: "Bus 39 → Putney Bridge Station", "Bus 39, 639 towards Church Road" |
| 7 Route order at Putney Station | Pass: 14, 37, 39, 85, 93, 337, 424, 430, N74 |
| 8 Widget page after a re-sort | Not testable today: the Clapham side had no live departures, so the widget had one page. Covered by unit tests; `pagekey_13` was seen written correctly. |
| 9 Large text | See #12: list behaviour, not squeezed rows |

Font scale restored to 1.05 after testing.

### Also fixed: the screensaver's logo (owner, 2026-11-23)
The screensaver drew its own red disc with a white "S" bottom-left, while the
home board and the widget show the real Stationly mark there. It now uses
`StationlyLogo`, the shared component whose own doc already called itself "the
single source for every logo placement". The home board's footer had also
inlined a private copy of the same logic; it now calls `StationlyLogo` too, so
the three surfaces cannot drift again. Verified on device (screensaver preview).
iOS: shared code; the iOS app's board footer goes through the same component.
The iOS WIDGET draws its own `StationlyMark` in Swift and was not touched.
Not changed, noted: the sign-in "Signing you in…" overlay pulses an amber "S"
disc. It is a loading animation, not the board's maker mark.

### Also fixed: the Android widget ignored the SDUI timing rules (owner question, 2026-11-23)
iOS introduced served timing rules (`board.tick.departedGraceMs`,
`retentionMinAgeMs`, `departedLabel`, `rowReserve`, the stale ladder) read
through `BoardPolicyStore`. On Android the home board, sign-in and screensaver
load them, the widget and push paths warm them (`SduiConfig.ensureLoaded`), and
the backend serves them to Android unchanged. But the WIDGET ticked through an
Android-only `tickPredictions` with a hard-coded 30 s grace and no retention, so
it never applied them. Now:
- The widget builds its board like the app: group the raw rows with the full
  reserve, `BoardTicker.tick` under the served policy, cap to 3
  (`DepartureWidgetProvider.tickedGroupsFor`, shared by render and chevrons).
- The screensaver ticked each feed BEFORE grouping, which honoured the served
  grace but never retained a departed train; it now uses the same three steps.
- Retained "Gone" rows are dim amber (`#B88F1A`) on the widget and screensaver,
  as on the home board.
- The Android-only `ui/util/PredictionTicker.kt` is deleted (no callers left);
  notes updated (`ui/util/CLAUDE.md`, `widget/CLAUDE.md`,
  `BOARD_TIMING_CONSISTENCY.md`).
iOS: no change. Its widget already mirrors `BoardTicker` in Swift, and its app
uses the shared board. Gate green; widgets re-rendered on device without errors.
Committed as `c1ffca4`.

### Also fixed: widget refresh spin on unlock, and the stuttering marquee (owner, 2026-11-23)

- **Unlock spun every widget.** Screen-on/unlock refreshes via `triggerRefresh`,
  the same action as a press but with no widget id, and the spinner did not
  check. Now only a press (an id) spins or rolls; the wake refresh is silent and
  skips the 900 ms spinner hold. Device: wake gave "Refresh fetched 6/6" with
  four still icons; a tap still turned the icon.
- **The status marquee stuttered after a refresh.** A TextView restarts its
  marquee whenever its text is set, and every redraw sets it. Logged on device:
  each Highbury widget drawn twice in 130 ms per push pair, Smithwood four times
  in 400 ms, and 64 renders every 30 s from the app's SQL poll (one blank,
  "sweep everything" update per board). Fixes:
  - push-driven redraws merge into one per widget per burst
    (`DepartureWidgetProvider.scheduleRedraw`, 300 ms quiet, 1.2 s cap,
    `RedrawCoalesceTest`);
  - `SummaryViewModel`'s widget update names `stationId`, and its 30 s poll no
    longer redraws the widget on Android (pushes and the minute watchdog
    already do).
  After: 70 s idle gave one render per widget per push burst (8 Smithwood
  pushes, 1 render); a tap gave spinner, new data, then the next push.

- **The refresh roll moved as one curtain.** Now the old board fades and the
  rows roll in one after another (layout animation on a refresh-only page
  layout), press only. Checked on device by the owner: working.
- **Review fixes to the above:** a press made while the unlock refresh was
  still running was silently dropped (no spinner, no roll), because the unlock
  no longer spins; it now gets both, and the running fetch redraws it
  (`stopSpinners`, with a backstop). Step mode now settles after a roll like
  scroll mode, so a launcher rebuild (unfold) cannot replay it.

iOS: no change in behaviour. `stationId` is ignored by the iOS widget manager
(it reloads all timelines), and the poll still reloads WidgetKit on iOS.
Whether WidgetKit's marquee shows the same stutter was not checked.
Committed as `15123d3`.

---

## Code review (2026-11-23, after the device run)

A line-by-line pass over every uncommitted change. What it found and did:

| Severity | Finding | Fix |
|---|---|---|
| **Bug (introduced by #1)** | The 8 s location cap also covered Android's permission dialog: a first-time user slower than 8 s to tap "Allow" was told their location could not be found. | `LocationProvider.ensurePermission()` (default `true`; Android asks), called BEFORE the timed fix wait and never under a deadline. iOS keeps the default: its host asks via `onRequestLocationPermission`. |
| **Bug (introduced by #2)** | `activeSearchQuery` was never cleared when leaving the station step, so after a search, the next station step's nearby answer could be thrown away as "the user is searching". | Cleared on every mode/station change (`onDropdownSelected`) and on step-back (`popLastSelection`). |
| Doc | The `UserSelection.directionTowards` KDoc edit left the old, wrong paragraph half in place. | Rewritten: it is the NEXT stop; use `directionDestinations` for where a direction ends. |
| Duplication | `directionDestinations.singleOrNull().orEmpty()` written out at nine feed builders. | `UserSelection.singleTerminus` (a getter: never serialised); all nine use it. |
| Clarity | A local `clean()` extension declared inside the per-group lambda; its comment claimed "every feed in the block" when it is every feed WITH A DEPARTURE in it. | Hoisted to `placeNames(feeds, of)`, comment corrected. |
| Edge case | The header ladder's last rung cut at " towards " on ANY header, so a rail header containing the word would have been truncated. | Only applied to headers that start with "Bus ". |
| Tidy | A `Regex` built per call in `directionSummaryLabel`; a stray blank line. | Hoisted; removed. |
| Duplication | The board footer and the screensaver each inlined their own logo. | Both call `StationlyLogo` (see above). Unused imports and `StationlyRed` in DreamBoard removed. |

Checked and left as is:
- `BackHandler` in the in-app browser stays registered for the ~220 ms exit
  animation; a Back pressed then calls `onClose()` again, which is idempotent.
- The selection flow's `BackHandler` is disabled exactly where the toolbar
  arrow leaves (edit mode, mode step), so those still pop the route.
- Sheets and dialogs on these screens are their own windows and take Back
  first, as before.
- `PlatformHeader(title, key = "")` and `Feed(..., terminus = "", nextStop = "")`
  are additive with defaults; no Swift constructs either.
- `pagekey_` does not start with the binding store's prefix, so the binding
  sweep ignores it; `forget()` and `reset()` clear it.

Gate after the review: core unit tests, Android app unit tests,
`compileKotlinIosSimulatorArm64` for core and composeApp, and
`assembleStagingDebug`, all green.

---

## Not fixed yet

| # | Finding | Notes | iOS |
|---|---|---|---|
| 8 | "Select all" at a stop with more routes than the limit ticks the first four, then shows "Line Limit Reached". | Deliberate per `toggleAllLines` ("four lines plus the modal saying why"). Now picks the first four in NUMERIC order (#7). Decide: hide the button above the limit, or relabel it "Select 4". | Shared. |
| 10 | The same buses are labelled "Wandsworth" / "Clapham Junction", "Putney" / "Putney Bridge" / "Putney Heath" on different refreshes. | Some are real short workings. Needs a backend check of where the two destination names come from. | Backend: affects both. |
| 11 | Landscape: the board runs off the bottom and the staging banner covers the status row. | Not yet confirmed whether the page simply needs scrolling; needs a device check before touching `HomeBoardBudget`. The carousel also ignores `MAX_BOARD_WIDTH` (480dp) in landscape. | Check iPad/landscape too. |
| 13 | Support amounts are fixed GBP ("Pay £8"). Play and the App Store charge in the buyer's currency. | Show `ProductDetails.oneTimePurchaseOfferDetails.formattedPrice` on Android. | **Needs Swift**: StoreKit `displayPrice`. |
| 14 | Screensaver: one direction only at a two-direction stop; weather from the phone's location, not the board's; "39 Line" for a bus. | | Screensaver is Android only. |
| 15 | No offline indicator in the app (the widget shows its retry glyph). | | Shared UI. |
| 16 | Widget manager lists 8 widgets with indistinguishable duplicates; no back arrow. | Some may be on the Fold's inner screen. | Android only. |
| 17 | List layout: each board scrolls inside the scrolling page. | | Shared. |
| 18 | Station settings: preview cards show tube platforms on a bus stop; text scrolls under a see-through header. | | Shared. |
| 19–28 | Copy and polish: "Dlr" → "DLR"; About omits the Elizabeth line; "Support Stationly" ×3; © 2025; no signed-in email on Profile; ".." vs "…" truncation; green tick beside a red warning; "Set Up My Board" when editing; alphabetical search results; Mildmay dot colour; empty band on home; TalkBack reads the hero destination letter by letter. | | Mostly shared. |
| 32 | **New, data:** Bank, Central line: "Eastbound" card says "towards St. Paul's" and "Westbound" "towards Liverpool Street". The two are swapped (east of Bank is Liverpool Street). | Backend direction/next-stop pairing. | Backend: affects both. |
| 33 | **New, data:** Smithwood Close outbound pole stopped receiving predictions (last arrival 13:01 while the inbound side kept updating), so the board and widget dropped to one block. | Check the Syncer/FCM fan-out for that pole. | Backend. |
| 29–31 | To check: Home settings → Screensaver opens Android's own settings; Privacy/Terms 401 on staging only; board clock uses phone time, fares card uses London time. | | |

---

## To verify on the device (not yet done)

1. Add station → Tube: "nearby" gives up within ~8 s with the new wording; typing during the spinner shows results at once.
2. Add station: system Back steps directions → station → mode → leaves.
3. Profile → Privacy Policy: system Back closes the page and stays on Profile.
4. Edit Bus Board (Smithwood Close): summaries read the destination, not "Towards".
5. Add station → Bus → Putney Station: routes in numeric order.
6. Smithwood Close board and widget headers: "Bus 39 → …" (only once the board has `directionTowards`; re-save the board if it shows plain "Bus 39").
7. Widget in arrows mode: step to page 2, refresh twice, stays on the same direction.
8. Font scale 1.3: no clipped widget rows. Then put the font scale back to 1.05.
