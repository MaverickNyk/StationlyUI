# Widget configuration: which station a widget shows, and where a tap goes

> ### ⚠️ SUPERSEDED on 2026-08-17 — read `SESSION_2026-08-17_WIDGET_STATION.md`
>
> Points 1 and 2 of the scope below were **reversed**. A widget no longer takes
> "the next station nothing is showing", and a widget whose station is deleted is
> no longer repointed at another one — both were auto-assignment, both moved
> widgets onto stations the user never chose, and both are gone along with
> `unclaimedStation()`, `StationAssignment.swift` and
> `WidgetPlacementRegistry.swift`.
>
> **Everything in §3.1 arguing that `unclaimedStation()` is sound is wrong.** It
> is kept unedited because this is a dated record of what that session did and
> why, and the reasoning it contains is the reasoning that had to be answered.
> Points 3 and 4 still stand.

_Session 2026-08-14 (second), branch `ios-parity`. Follows
`SESSION_2026-08-14_WIDGET_REVIEW.md`, which was the typography pass on the same
extension._

**Scope.** Four asks, all about a widget knowing which station it is for:

1. A new widget takes the next station in the user's list, not always the first.
2. Deleting a station does something sensible to the widget pinned to it.
3. The Edit-Widget picker tracks the app, through deletions and sign-out.
4. Tapping a widget opens the app **on that station**.

Feature reasoning lives in `IOS_WIDGET_DESIGN.md` §9. This is the defect log.

---

## 1. Bugs fixed — pre-existing

### 1.1 The picker's "first station" was not the user's first station

`Platform.ios.kt` built the App Group directory from `all.groupBy { groupingId }`,
and `groupBy` preserves **insertion** order. The home screen has never agreed:
it sorts by each board's own `position` through `UserSettings.ordered`, which is
what `SummaryScreen` reads. Dragging a station to the top of the list moved it on
the home screen and nowhere else.

Three surfaces take "first" off that directory and every one of them means the
user's first — the configuration picker, the gallery's `recommendations()`, and
the station a new widget defaults to. All three were answering with the oldest
board instead.

Fixed by arranging the directory through the same `UserSettings.ordered` the home
screen uses. `boardPrefs()` moved above it, because that call is what loads the
store: arranging before it loaded would see every board `UNPOSITIONED`, tie on
the sort key, and silently fall back to insertion order — the exact bug, with a
sort in front of it.

### 1.2 A deleted station's widget jumped to an arbitrary board, silently

`readWidgetData(stationId:)` fell through to the **legacy flat keys** whenever the
configured id did not resolve. Those keys are written for whichever board is
primary, by a different code path, and can be staler than the per-station keys.
So deleting a station moved its widget to some other station with no notice, and
possibly to a board last written hours earlier.

Now it repoints through `unclaimedStation(anchor:)` — the first station nothing
else is showing — and reads that station's live board. The legacy keys are the
last resort only, which is what they are actually for: a widget added before the
configuration existed has no id to resolve and nothing else to read.

**Repoint rather than clear, and the reason is recoverability.** Nothing can
rewrite a placed widget's configuration — there is no API — so a widget that had
been "cleared" would stay cleared permanently. Because the configured id
*survives*, signing out and back in restores every widget to its own station with
no action from the user, and re-adding a deleted station brings its widget back
by itself. Clearing would throw that away for good.

### 1.3 `stationly://` was never a registered URL scheme

`AppDelegate` carries a note reading *"To enable password reset deep links,
register a custom URL scheme in Info.plist"*. Nobody had — only Google Sign-In's
scheme was registered — so `handleFirebaseActionURL` has never been reachable and
every `stationly://` link was dropped by iOS before the app saw it. Added to
`project.yml` (§4.2 below has what that did NOT fix).

### 1.4 Every widget defaulted to the same station

`defaultResult()` returned `.first` unconditionally, so a user with three
stations who added three widgets got three copies of one board and had to open
Edit Widget twice to fix it.

---

## 2. Bugs fixed — introduced earlier in this session, caught in review

### 2.1 The deep-link URL was built inside a view body

The first pass put `.widgetURL(Self.boardURL(stationId: entry.widgetData.stationId))`
in `DepartureBoardEntryView.body` — a percent-encode and a `URL` parse **per
entry** (~20), per widget, inside WidgetKit's archiving pass, producing the
identical URL every time.

That is precisely the defect class the previous session's review existed to
remove, and `BoardRenderState` was introduced by that review to hold exactly this
kind of answer: *"resolved once when the timeline is built… the views used to read
them from inside `body`"*. Moved to `BoardRenderState.deepLink`, built once in
`timeline(for:in:)` from the station actually being rendered.

### 2.2 `isCarousel` was captured by an effect that was not keyed on it

`LaunchedEffect(requested, stationIds)` read `isCarousel` to decide between
expanding a card and turning a page. Switching layout would not re-run it, so the
effect could act on a layout that was no longer on screen.

Split in two: resolution (no layout knowledge, keyed on the request and the
station list) and the list's response (keyed on `focus` **and** `isCarousel`).
The split is load-bearing for a second reason — the `BringIntoViewRequester` is
attached by the composition that setting `focus` triggers, so calling
`bringIntoView()` from the effect that *sets* `focus` would run against a
requester bound to no node and do nothing.

### 2.3 A foreign URL scheme could evict the push trace

`.onOpenURL` logged every rejected URL to `PushTraceSwift`, whose ring is 40
entries deep and **shared with the Kotlin push trace**. Google Sign-In's redirect
scheme arriving during a debugging session would quietly spend entries that the
push investigation needed. Now returns on a foreign scheme before any logging;
only `stationly://` URLs can write.

---

## 3. The decisions worth knowing

### 3.1 `unclaimedStation()` is a pure READ, and that is the whole design

The obvious implementation is a cursor in the App Group that advances per new
widget. It cannot be made correct: `defaultResult()` is **not** called once per
widget — the system consults it whenever the parameter is unresolved, including
gallery previews and every trip into the editor — so a cursor advances on calls
that added no widget, drifts, and has no repair path, because nothing can rewrite
a placed widget's configuration afterwards.

Deriving the answer from the placement stamps instead makes it idempotent: asking
ten times gives the same answer ten times. It also self-heals — remove the widget
showing the second station and the next widget added takes that station back
rather than duplicating the first.

A short-lived *reservation* was considered, to close the window in §5.1, and
rejected: it cannot tell "the same widget asking twice" from "a second widget
asking", so it would break exactly the idempotency above.

### 3.2 `anchor` is what stops a repointed widget from wandering

In the rotation branch the answer would otherwise depend on how many stamps
happen to be live, and that number **moves** — the app prunes stamps for widgets
that have gone. A board that silently changed station because an unrelated stamp
expired is the one failure this design exists to prevent, so a caller with an id
to be consistent about passes it and gets an answer derived from that id alone.

FNV-1a rather than `hashValue`: Swift seeds String hashing per **process**, so
`hashValue` would pick a different station every time the extension relaunched.

### 3.3 The placement stamp records the CONFIGURED station, not the rendered one

These differ for exactly one widget: one that has been repointed. Stamping the
configured id keeps that widget from claiming the station it borrowed — if it
claimed it, the next build would find it taken and repoint somewhere else, and
the board would walk down the user's station list a few minutes at a time.

### 3.4 A widget with NO configuration uses `.first`, not the claim rule

It stamps whatever it renders, so under the claim rule it would claim its own
answer and the next build would skip past it. Same walk, different cause.
Distributing across stations is `defaultResult()`'s job, which runs once per
widget and whose answer is stored.

### 3.5 "No stations at all" is one state, not two

An earlier draft of this design distinguished *signed out* from *station
deleted*, showing a sign-in panel for the former. **Deleting your last board runs
the same `wipe()` that signing out does** (`refreshAllBoards`, `all.isEmpty()`),
so the two are byte-identical in the App Group and that rule would have told a
signed-in user to sign in.

The widget cannot break the tie either: the uid lives in the app's *standard*
defaults, not the App Group, and an extension cannot read those. Telling them
apart would mean a new flag kept correct through sign-out, auth-expiry
force-logout and reinstall — to earn one word of copy. The existing single empty
state ("Open the app to add a station") is true in both worlds and is kept.

### 3.6 The delete dialog does not name the replacement station

The widget resolves that at its next timeline build, from stamps that can move in
between, so any name promised in the dialog could be the wrong one — and
predicting it would put a second copy of the rule in Kotlin, free to drift from
the Swift one that actually decides. The dialog says a widget is showing this
station and will switch; the station name is the largest thing on the panel, so
the answer is legible the moment it happens.

Warned **before** the action rather than reported after, which also avoids
plumbing a message across the screen the deletion navigates away from.

---

## 4. Verification

**Compile gates.** `:composeApp:compileKotlinIosArm64` and
`:core:compileKotlinIosArm64` green, no new warnings. `xcodebuild` BUILD
SUCCEEDED for **`iosApp Staging` / `Debug Staging`** on the iPhone 11.

**On device, with trace evidence pulled from the App Group:**

| | result |
|---|---|
| deep link, app already running | `deeplink station=910GTOTCTRD`, 2s after the URL |
| deep link, cold start | `deeplink station=490G00008805`, 1s after launch |
| widget extension health | 10 timeline builds, `read=0ms tick=0ms entries=19`, 0 failures |
| environment | `widget_api_base_url` = `https://staging-api.stationly.co.uk` |
| directory | 3 stations, ordered, no entries dropped by the new `mapNotNull` |

### 4.1 ⚠️ `idevicesyslog` returns NOTHING on this device

Measured: 8 seconds of capture, **2 lines, neither ours**. The §3.4 archive-loop
protocol in `IOS_WIDGET_DESIGN.md` — the tool every previous widget session
verified with — **cannot be used on this phone any more**.

What works instead, and what this session used throughout:

```bash
xcrun devicectl device copy from --device <UDID> \
  --domain-type appGroupDataContainer --domain-identifier group.com.stationly.shared \
  --source Library/Preferences/group.com.stationly.shared.plist --destination /tmp/g.plist
plutil -extract widget_refresh_trace json -o - /tmp/g.plist   # extension's own trace
plutil -extract push_trace          json -o - /tmp/g.plist   # app + Kotlin trace
```

Both rings are bounded (20 and 40) and already existed. This is why the deep-link
handler writes to `PushTraceSwift` as well as `os_log`: on this device `os_log`
is write-only.

Also usable, and new here: `xcrun devicectl device process launch --payload-url
"stationly://home?station=<id>"` fires a deep link without touching the phone.

### 4.2 What registering the scheme did NOT fix

`stationly://…?mode=resetPassword` links now **arrive** and are dropped, where
before they never arrived. Net behaviour is unchanged (broken either way), but it
is now visible in the trace as `deeplink unhandled`.

Deliberately not wired up: `AppDelegate.handleFirebaseActionURL` posts a
notification that sets `deepLinkOobCode`, which reaches Compose only through
`makeUIViewController` — already run by the time `.onOpenURL` fires. Routing it
would look fixed and not be. It needs the same treatment `BoardFocus` got.

---

## 5. Not done / known limits

### 5.1 Two widgets added within a second of each other can double up

A widget claims its station on its **first timeline build**, a second or two after
being added. Two added inside that window see the same claims and take the same
station. Nothing available fixes it — a provider is handed no widget identity, so
the two cases are indistinguishable — and the fallout is one Edit Widget away.

### 5.2 A stamp goes stale for up to 36 hours if the app is never opened

`claimTTL` is the backstop, not the mechanism: the app reconciles stamps against
the host's real placement list on every foreground (`HomeStateProbe.describe`),
which is exact. 36 hours clears the longest legitimate gap between builds for a
*live* widget — overnight tiers taper to hours, and a widget out of reload budget
goes longer still.

### 5.3 The backend's own copy now contradicts the behaviour

`profile.delete_station.bullets` is served by the backend and reads **"Widget will
be cleared"**. It will not be — it repoints. Server-side string, not changeable
from this repo.

### 5.4 The picker can report "unknown extension process" after install churn

Seen on device this session. Not a code fault: AppIntents metadata was verified
present in the installed bundle (`SelectStationIntent`, `StationEntity`,
`StationEntityQuery`), there were no extension crash logs, and the extension was
building timelines normally throughout. iOS lost the AppIntents registration —
caused by installing a **Production** build over Staging and back, same bundle id,
different provisioning. A reboot re-registers. See the staging-only rule in
`IOS_HANDOVER.md` §8.

### 5.5 Still needs eyes

Nothing here proves how it **looks**: whether the carousel turning and the list
scroll-and-expand read well, and whether widgets 1/2/3 land on stations 1/2/3 —
that one needs widgets actually placed, which cannot be done from the CLI.

### 5.6 None of this reaches Android

Android's widget has no per-station tap either, so the gap widens.
