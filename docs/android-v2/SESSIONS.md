# Android v2 — session journal

Append-only. Newest first. One entry per session, written at close-out.
**Never delete or rewrite an entry** — a wrong turn recorded is worth more than a
tidy log, because the next agent would otherwise take it again.

Template:

```
## S0NN — YYYY-MM-DD — AV2-x.y "story title"
**Outcome:** DONE | PARTIAL | BLOCKED | GATE-FIX
**Gate:** GREEN | RED (which task)
**Commits:** <sha> <sha>
**Did:** one or two lines.
**Learned:** anything that changes the map. Move it into analysis/ if it does.
**Next agent needs to know:** the thing you would tell them in person.
```

---

## S017 — 2026-09-12 — owner feedback, against the phone throughout

**Outcome:** DONE (eight fixes + the release-build walk, all on the Pixel 7 Pro)
**Gate:** GREEN, including the XCFramework — `commonMain` changed in several places, all shared fixes iOS wants too.
**Commits:** `c3dbfa0` `e2846a6` `d89570a` `f68eb4c` `9dfc46e` `6b6c740`

**Did.** Worked the owner's feedback list, TDD, with the device in the loop for
every item rather than at the end. The avatar's "?", the widget's depth rule,
the gear opening a real settings page, the widget body opening the app, the
screensaver's missing Start button and its station picker, mode icons on every
list you configure from.

**Learned — five things, and four of them were only reachable from hardware.**

**1. The screensaver had never been rendered, and it can be.** A dream runs only
with the screen on and the doze dream wins while it is off, which is why every
previous pass verified the settings screen and stopped. It previews from the
CLI: Settings → Display → Screen saver → the eye on the tile. `Somnambulator`
does NOT work on this device (SDK 37) — `am start` succeeds and nothing binds.
`dumpsys dreams` is the honest check: `mCurrentDream`, `mCrashRetryCount`,
`mLastCrashTimeMillis`. One render found two defects: rows centred vertically
(three departures floating between two voids, reading as a board that failed to
load) and a whole interchange drawn as ONE board — the same defect the widget
had, with the empty space below being the other platforms all along.

**2. Starting to write an identity key is a DATA MIGRATION.** `UserSettings`
namespaces by uid from `firebase_user_uid`, and until this session the only
writer of that key in the entire codebase was `AuthBridge.swift`. So every
Android user's arrangement lived in `::anon` — silently and consistently,
because one namespace used forever is indistinguishable from the right one.
Publishing the identity to fix the avatar pointed `load()` at a namespace that
had never existed, in the same commit. The owner's phone showed it: real
settings in `::anon`, an empty bucket beside it under the uid, and a home
screen quietly reverted from CAROUSEL to the default list. Adoption now rescues
it once and CONSUMES the anon namespace — without the consuming, the second
person to sign in on a shared phone inherits the first person's arrangement.

**3. Never `am force-stop` before probing a widget tap.** A stopped Android app
cannot be launched by its own PendingIntents until a human opens it. Every tap
target then reports "nothing happened", with no logcat line, indistinguishable
from unwired clicks. This cost several rounds and a wrong conclusion about
`setPendingIntentTemplate`; the same probes without the force-stop showed the
gear, the board body and the collection rows all working. Always include a
known-good control in the same run. (`uiautomator dump` is no help either — the
widget clock ticks, so the window is never idle.)

**4. A constant the widget owned was overriding a setting the app promised.**
The widget hardcoded three rows per platform. Every station carries "Show up to
N per platform", 2–5, default 3, which the home screen and screensaver already
obeyed. Three was that setting's default all along, so reading the board keeps
the product rule for everyone AND the promise to whoever changed it. Verified
both directions on the device.

**5. Four station cards each invented their own rule for naming lines**, and the
two errors were opposite halves of one missing rule — "Mild." in a card with
room for "Mildmay" twice over, and four full line names ellipsised into
uselessness. `joinLines` had documented the right rule for board headers since
before any of them existed.

**6. The release build did not need a human either.** That assumption was the
last thing blocking AV2-8.1(b), and it was wrong for the same reason as the
screensaver: "an R8 failure lands on the screen nobody opened" is true, but
opening those screens is something a session can do over `adb`. Signed the
release APK with the debug keystore, installed it over the debug build, walked
every surface, put the debug build back. **Zero `FATAL EXCEPTION`**, and nothing
R8-shaped anywhere. Two traps in the recipe: `--no-build-cache`, because
`shrinkResources` fails on a build-CACHE packing bug that looks like R8; and
capture `run-as` state BEFORE the swap, because a non-debuggable build closes
that window. Both builds share a `versionName`, so check `flags=[ DEBUGGABLE ]`
to know which one is actually installed.

**Next agent needs to know:** nothing on this board still needs a session. Q5 is
the owner's call and the biggest risk on the branch; AV2-8.3 is Play Console
work. The one thing no walk can prove is hours of runtime under R8 with real
pushes landing, which needs the phone left alone rather than another pass. The
device notes are in memory (`android-test-device`, `android-dream-preview`,
`android-release-walk`) so they survive a context loss.

---

## S016 — 2026-09-11 — EPIC-04, EPIC-05, EPIC-06, EPIC-07 · **eleven stories**

**Outcome:** DONE (11 stories to Review; AV2-8.1 In Progress; Q7 fixed, Q5 escalated)
**Gate:** GREEN, including the XCFramework — `commonMain` changed in six places.
**Commits:** `755273f` `91a51f7`(merge) `b6eacea` `d87e0df` `8116f93` `84b66d5` `bc6fa44`

**Did.** Closed the data plane (4.2, 4.3, 4.4), the widget epic (5.3, 5.4), the
dream (6.1, 6.2) and the release surfaces (7.1, 7.2, 7.3). Merged `master` in at
the owner's request — the eight iOS commits that shipped v1.0. Started AV2-8.1.

**Learned — the branch had a board-eating bug and it was structural, not subtle.**
AV2-4.3 was written as a migration: adopt the boards write, dual-write the legacy
array, fold on first run. The write had already moved at the cutover. The READ had
not. Android was writing `boards` and reconciling against `stations`, the backend
derives neither from the other on a write, and that reconcile **deletes any local
selection the cloud list does not have**. On an account whose `stations` array is
empty — every account created on v2 — that is every board the user has, within
fifteen minutes of adding it, with no error and nothing on screen.

It survived four device passes because the one test account's legacy array
already described the one board its device held. Nobody had added a board and
then waited.

**The general shape, and it is the third time this branch has hit it:** the
cutover moved a writer without moving its reader. AV2-3.5 did it with
intent-filters, AV2-4.1 with the fan-out key, and this is the same thing at the
level of a whole array. **Grep for what wrote TO a thing, not just for the thing.**

**Learned — every stale comment on this branch has been load-bearing.** Four
separate ones this session, each of them the only thing standing between a
correct-looking file and a live defect:

- `SupportCheckout.android` said an Android build "has neither `enabled` nor a
  checkout URL". `enabled` comes from the backend. One config change would have
  shipped a dead money button to Android.
- `cleanupAll` ordered its teardown around an "unsubscription queue" that iOS
  deleted when it dropped FirebaseMessaging — while that ordering cost Android
  the ledger it actually has.
- `UserStateSync` said "iOS only, deliberately" about a class Android had been
  calling since the cutover.
- `DeviceIdProvider` and `DeviceIdentity` were kept in step by a test, which is
  not the same as being one implementation: both could MINT an id, and one of
  them ran during logout.

A comment explaining why something is safe is the first thing to rot when the
thing it describes moves. The fix in three of the four was to make the safety
STRUCTURAL — a capability, a single owner, one function — rather than to write a
better comment.

**Learned — "it compiles for Android" is still not "it works on Android", five
stories later.** `ActivityUploader` was complete and had never been called.
`Board.widget` was an empty map that two surfaces read as an answer.
`board.count` counted the wrong map on both platforms. None of them error.

**Late in the session — Q7 fixed, and Q5 turned out to be far bigger than its
own question says.**

Q7: the prediction primary key ended in the FORMATTED `eta`, so two trains 40s
apart both keyed as "1 min" and `INSERT OR REPLACE` threw the first away. Fixed
with `targetEpochMs` in the key beside `eta`, `2.sqm`, schema version 3. The
test that had asserted the wrong behaviour on purpose for three sessions now
asserts 2 — and making it pass made a NEIGHBOUR fail: `the same train twice in
one payload is collapsed` had been green **because of** the defect, off a fixture
that read the clock twice for what it called one train.

Q5: checked `origin/master` while reasoning about adding a second migration, and
it has **no `.sqm` files at all**. So the iOS build on the App Store stamps its
databases version 1 while already holding the current schema — meaning the first
iOS release cut from this branch runs `1.sqm` on **every App Store user**, hits
its deliberate guard, and fails to open the database. The question still says
"TestFlight testers". It has not been about testers since 2026-09-10. Three
options are written into the question on the board; the good one is an iOS-side
re-stamp of a version-1 database whose shape is already current.

**Next agent needs to know:**

1. **Nothing in this session has been on a phone.** Twelve stories. Each handoff
   carries its own device script; AV2-4.3's is four lines and would have caught
   the headline bug in two minutes.
2. **AV2-8.1 is the live story** and its task (b) is the one that matters: a
   release build that compiles is not a release build that runs. R8 strips
   reflection paths and the failures land on screens nobody opened.
3. **Two config changes are owed to the backend**, both written out in full — the
   widget guide's Android copy (AV2-5.4) and the About screen's `market://` rate
   link, which is an iOS bug found by an Android audit (AV2-7.2).
4. **Q5 is the biggest risk left on the branch**, and it is a question about
   iOS, found from Android. Read it before planning any merge to master.

---

## S015 — 2026-09-06 — AV2-5.2 rework · **reconstructed, logged S016**

**Outcome:** DONE (never written up at the time)
**Gate:** not recorded
**Commits:** `5b5b7c5`

**Did.** Rebuilt the widget flow around where the want forms: "Add to Home
Screen" on a station's own settings using `requestPinAppWidget`, the two
settings rows collapsed into one destination, and the launcher-refused fallback
spelled out in the manager's own copy.

**The owner's report is the finding.** *"I don't see the widget settings yet —
design a good dedicated flow, it's very different from iPhone."* The settings
existed; AV2-5.2 had put them in a row called "Widget stations" NEXT TO a row
called "Widgets" that opened a guide. Anyone looking for widget settings taps
the obvious row, gets a page explaining what a widget is, and concludes there
are none. **Two rows for one subject is worse than either alone.**

**Next agent needed to know** (and this reached AV2-5.4 correctly): the guide
now has nowhere to be reached from on Android, and the row for it belongs INSIDE
the manager, never back in Home settings.

---

## S014 — 2026-09-06 — device QA + R8 · **reconstructed, logged S016**

**Outcome:** PARTIAL (recorded its R8 result in EPIC-08 and nothing else)
**Gate:** `:android:app:assembleStagingRelease` PASS — 10m 31s, 6.98 MB
**Commits:** `ba560e0`

**Did.** First minified build since the cutover — R8 full mode plus
`shrinkResources` survives the whole Compose Multiplatform graph, which was the
largest unknown in EPIC-08. Fixed the status bar following the PHONE's dark mode
rather than the app's: `enableEdgeToEdge()` with no arguments reads the system
setting, so a light app on a dark phone had white status icons on a cream canvas.
Predates the cutover — v1 called it the same way.

**Learned — the fix had to move to where the answer is resolved.**
`enableEdgeToEdge()` runs once in `onCreate` and cannot know that
`AppTheme.SYSTEM` later resolved to light. `StationlyThemeHost` does, and
recomposes when the user flips the toggle, so the system-bar appearance is
applied there in a `SideEffect`.

**Not logged at the time, and one more change went unlogged entirely:** commit
`fd9a627` (2026-09-08, "android v2 phase") implemented AV2-5.3's targeted widget
redraw. No session entry, no board move — so S016 opened with the board claiming
that story was untouched, and re-derived from the code what the board could have
said. **A change that lands outside a session still has to be written onto the
board.**

---

## S013 — 2026-09-06 — AV2-5.1 + AV2-5.2 "the widget knows which station it is"

**Built.** `WidgetBindingStore` (`appWidgetId` → `groupingId`), a configuration
Activity with two modes, per-instance rendering, and a "Widget stations" row in
the app's home settings. Deleted `updateWidgetContent`.

**The owner's question was the design.** *"Multiple widgets of different stations
— widget stacking is not an option on Android, so what can be done?"* The answer
is that nothing needs inventing and stacking was never the shape. Stacking is
iOS's answer to WidgetKit's placement model; Android's home screen is a free grid
and has allowed many instances of one provider since widgets existed, each with
its own `appWidgetId`. **Two stations is two widgets.** What was missing was a
way to tell each instance which station it is for, which is `android:configure`.

**Learned — a thing can be un-wrong without ever being right.**
`updateFromStorage` read `selections.first()` and pushed that board to every
widget id. It was impossible for a widget to show the wrong station, because no
widget was ever showing a particular one. That reads as "working" for as long as
the user has one station, which is how it survived to here. The bug was not in
the rendering; it was that there was nothing to render *from*.

**Learned — deleting the helper is part of the fix.** `updateWidgetContent` took
one board and fanned it out to every id. Left unused it would have been a
correctly-named, obviously-useful function that does exactly the forbidden thing,
sitting in a file whose one rule is never to show the wrong stop. Two smaller
versions of the same shape fell out of the same change: the mode roundel and the
"has this ever loaded" check were both reading the account's FIRST selection to
describe whatever widget was being drawn.

**A second owner instruction, mid-session:** *"there should be a widget settings
in the app that can also be opened from the widget, and then every widget can be
assigned a station."* Both halves built, and the second changed a decision — the
gear on a widget used to open the app's home screen and now opens that widget's
own picker. You tapped the gear ON a widget; that is the widget you meant.

**Learned — the dependency graph is not evidence.** The board had AV2-5.1 waiting
on AV2-4.3 (cloud state dual-write). It does not: the binding is device-local
prefs, and both halves of it already existed on Android. The dependency looks
inherited from AV2-5.3's placement probe, which is itself `@Transient` and never
synced. Worth checking a dependency before spending a sprint respecting it.

**Next agent needs to know:**

1. **Nothing here has been driven by a human, and it cannot be from adb.** There
   is no shell command that drags a widget onto a home screen. The Activity
   launches and lays out (confirmed by `dumpsys`, no exception) and that is all
   that can be claimed. The four-minute script is in AV2-5.1's handoff; do it
   before anything else in this epic.
2. **AV2-5.3 got easy.** A push for station A still redraws every widget. The
   binding store now says which ones care, so the targeted redraw is a filter on
   a list that already exists.
3. **`requestPinAppWidget` is designed and not built.** "Add to Home Screen" on a
   station's own settings, placing a widget already bound to it. It is the piece
   that makes the whole feature discoverable — today the user has to know the
   widget gallery exists. Guard on `isRequestPinAppWidgetSupported()`.
4. **Owner direction on `boards`** (also this session): Android moves to the
   backend's `boards` array now that iOS has developed it. That is AV2-4.3 task
   (b), already the plan, now confirmed and prioritised — and AV2-4.1's duplicate
   rows point at the `stations` array as their source, so this removes a cause
   rather than a symptom. The widget binding is unaffected: a board's id is the
   same StopArea naptan a `groupingId` already is.

---

## S012 — 2026-09-06 — AV2-4.1 "FCM at v2" · **the board was not listening**

**Built.** Three scoped fan-out entry points instead of one, the foreground
reconcile restored, Android's writer for the account-removed notice, a read-side
dedupe for duplicate boards, and 15 tests. `commonMain` untouched.

**Learned — a story's task list can be a description of a problem that has
already been solved.** Tasks (c), (d) and (e) asked for direction scoping,
per-board fan-out and a `matchesFilter` precompute in `FcmMessagingService`.
All three were already in `SyncPredictionsUseCase`, which both platforms call:
the shared use case absorbed them on `ios-parity` before this branch existed.
Writing them again would have produced a second implementation of three things
that were already right. Characterizing first is what showed that — the tests
went green against untouched code, which is the answer, not a disappointment.

**Learned — the bug was where the cutover had removed a reader, not where the
story pointed.** The fan-out told the app a board had changed by writing a
SharedPreferences key that v1's `SummaryViewModel` watched. AV2-3.5 deleted that
view model. The write kept happening to a key with no listener, and the shared
view model that replaced it collects a different signal that nothing on Android
emitted. So an FCM push wrote fresh departures and the visible board sat still
until its own 30-second poll. Nothing errored. It looks exactly like a slow
network, which is why it survived a cutover and three device passes.

The general shape, and it is the second time this week: **deleting a consumer
does not delete its producer, and a producer with no consumer is silent.**
AV2-3.5 hit the same thing with intent-filters. Grep for the deleted class's
name is not enough — grep for what wrote TO it.

**Learned — put a log line at the point of silence.** The fix would have been
one function change; the durable part is `D/FreshData: fresh data → Station(…)`.
The failure mode here was not a wrong value, it was no evidence at all.

**Three defects, and the device found the one the tests could not.**
1. The push→board gap above. Fixed, verified from real pushes.
2. Android signed people out with no explanation when their account was deleted
   elsewhere — v1's Toast went with v1's Activity, and the shared login screen
   had been carrying the receiving end unused the whole time. Fixed; this was
   AV2-4.4's task (e), closed early because it was three lines in a file I was
   already in and leaving it meant leaving a silent sign-out shipped.
3. **A screenshot showed the same departure rendered twice.** Three
   `UserSelectionEntity` rows for one board; the schema has an AUTOINCREMENT
   primary key and no uniqueness constraint at all. Deduped on read. They came
   back after a restore with the identical 2-with-hub-plus-1-blank shape, which
   points at the cloud `stations` array rather than a local double-insert —
   AV2-4.3's to chase.

**Learned — screenshot the thing.** Defect 3 was invisible in the logs and in
every test; it took one `adb exec-out screencap`. The log then confirmed the
cost: one push, three identical sync-and-persist passes.

**Next agent needs to know:**

1. **Q7 is a live correctness bug on both platforms and I did not fix it.** The
   prediction table's primary key includes the FORMATTED eta string, so two
   trains 40 seconds apart collapse into one and the rider is shown one train
   where two are coming. `SyncPredictionsUseCase` dedupes on `targetEpochMs`
   specifically to prevent this and the schema undoes it underneath. The fix is
   a migration, which this story does not own and which lands on Q5. There is a
   test asserting the WRONG behaviour on purpose — flip it, do not delete it.
2. **Task (b) is deliberately open.** Routing Android through
   `ProcessPredictionsUseCase` is right and would regress the widget today: the
   shared path refreshes the primary selection only, Android redraws every
   instance. Do it after AV2-5.1, which is the story that makes per-instance
   refresh a thing the shared path can express.
3. **AV2-4.2 has a head start.** The device is subscribed to two `Station_*`
   topics for boards it no longer has. `run-as com.stationly.mobile cat
   shared_prefs/StationlyPrefs.xml`, grep `Station_`, compare with the selection
   table.

---

## S011 — 2026-09-06 — AV2-3.5 "The cutover" · **the shared UI IS the Android app**

**Built.** `MainActivity` is `setContent { App(...) }`. `src/staging/` is gone,
`:composeApp` is `implementation`, and roughly 8,000 lines of v1 UI are deleted
along with `navigation-compose` and the downloadable-fonts pipeline. The class
kept the v1 NAME on purpose — home-screen pins reference the component, not the
package — so no `<activity-alias>` was needed. Tests 18 → 23.

**Started from a user report, not the board.** "Why do I see a Sign in with
Apple option in Android — catch mistakes like that, that's basic." It was a
known finding, correctly deferred by AV2-3.4 because hiding the button meant
editing a `commonMain` screen iOS ships, and AV2-3.5 owns that screen. Fixed
with `PlatformAuthProvider.supportsAppleSignIn`, abstract rather than defaulted.

**Learned — the fix for a wrong button is rarely just removing it.** Hiding
Apple left the landing screen with **no primary action at all**. `GoogleButton`
is deliberately styled to recede *below* the white Apple primary — dark surface,
54dp, semibold, no lift — so with Apple gone, Android's only sign-in button read
as the lesser of two and the eye went to "other ways to sign in" instead. A
button that is wrong to show is usually load-bearing for the one beside it.

**Learned — a migration task can be satisfied by a fallback written for another
platform.** Task (e) asked for a one-shot theme translation on first v2 launch.
None was written and none should be: the shared reader is `loadDurable(key) ?:
loadString(key)`, and on Android `loadString` reads `StationlyPrefs` — exactly
the file and key v1 wrote to, with the same three `storedAs` strings. That
fallback exists for an *iOS* reason and happens to be the whole of Android's
upgrade path. The story's task text also misnamed the source: the choice was
never in `ThemeRepository` (that is the SDUI colour-token cache), it was in
`AppSettings`.

The real theme bug was the other direction and nobody had asked about it: the
shared UI writes to `stationly_durable_prefs`, v1's reader read `StationlyPrefs`
only, and the **Daydream** is the one v1 surface still standing. Flipping the
theme in the app would have left the screensaver on last month's. v1's reader is
now durable-first with no setter at all.

**Learned — the cutover's real risk was not what it deleted, it was what it
started SHOWING.** Two things came from asking "what does the shared UI now put
in front of an Android user that nobody checked?", and neither was on the task
list:

- Home settings → **Screensaver** opened the shared dream settings screen, whose
  Android `actual`s are placeholders — a map that dies with the process, a
  no-op keep-awake. The user would configure a screensaver, have every choice
  discarded, and still have the real Daydream unchanged in system Settings.
  Their own file header said "the composeApp android target is a
  build-verification surface only", which stopped being true the moment this
  story landed. Android now goes to `ACTION_DREAM_SETTINGS`, as v1's promo did.
- `App()` calls `UpdateSurfaces()` at its root, so the **update gate is live on
  Android** as of this commit — including a blocking screen that consumes every
  gesture and offers one button. If the backend's `android` block carries iOS's
  store URLs, that button opens nothing and there is no way past. Written up at
  the top of AV2-7.1, which was planned as "wire it up".

A placeholder is only safe while nothing reaches it. Changing what a module
*is* changes what reaches it, and the compiler has nothing to say about that.

**Learned — deleting a host deletes its intent-filters' meaning, not the
filters.** v1's `MainActivity` owned four; the shared `App` handled one. The
other three would have stayed registered in the manifest, been delivered by
Android, and dropped in code — nothing errors, the tap does nothing. That is the
iOS bug this project keeps citing, and the cutover was one commit away from
re-committing it on Android.

**Next agent needs to know:**

1. **Nothing in EPIC-03 has run on a phone since the cutover.** Five stories sit
   in Review on a compile-and-test pass over an irreversible change. The check
   that cannot be deferred is the upgrade in place: v1 build → set theme, add a
   station, pin the icon → install this build over it. Everything else can be
   re-derived from the code; that cannot.
2. **AV2-4.1 inherits two deleted behaviours**, written into EPIC-04 as tasks
   rather than left in a findings section: the foreground `reconcile` fallback
   (4.1 (f)) and the Android writer for `ACCOUNT_REMOVED_FLAG` (4.4 (e)). The
   first is coherent to defer — the push it backs up does not reach the v2 board
   model either — and the second is not: today an account deleted from another
   device returns this one to login with no explanation at all.
3. **Q3 got more expensive.** The Daydream is now the only reason any v1 code is
   left in the app. Answering it "no" empties `com.stationly.mobile.ui` for free.

---

## S010 — 2026-09-06 — AV2-3.3 "Real actuals, batch B" · **EPIC-03 code-complete bar the cutover**

**Built.** The last three placeholder `actual`s became real: POST_NOTIFICATIONS
(state, request, and a settings deep link), fused-location nearby search, and an
on-disk SDUI asset cache. Both behavioural criteria verified on the Pixel 7 Pro.

**Learned — one finding that outweighs the story:**

**`AndroidAppContext` had been silently disarming the notification prompt since
AV2-3.2.** It registered its Activity-lifecycle callbacks lazily, on first
access, and its own KDoc justified that: "everything reading this is a response
to a user touching the screen, which cannot happen before then."

The shared UI already broke that assumption. `NotificationPermissionEffect`
reads the current Activity from a `LaunchedEffect` on the summary screen's first
composition — no touch — and composition runs **after** `onActivityResumed`. The
tracker therefore registered too late to hear the only resume that had happened,
reported no Activity, and the request returned false without launching anything.

On a fresh install the prompt never appeared. It did not appear on the next
screen either, or the next launch — nothing fires `onActivityResumed` again
until the user happens to background the app and come back. Nothing threw and
nothing logged, because "no Activity" is a legitimate answer meaning "cannot
ask". The bug was visible only as **an absence**, on one of the two prompts
Android gives you a single chance at.

Found by running the acceptance criterion on hardware rather than reasoning
about it. It would not have failed any test, on any CI, ever.

Fixed with `StationlyActivityTracker`, a content provider in `:composeApp`'s own
manifest — instantiated after `Application.onCreate` and before the first
Activity, which is the only hook a library gets there without adding an
obligation to the twenty-line host contract. Staging-only, confirmed in the
merged manifests. It also un-breaks haptics, and every future `actual` that
needs an Activity.

**Three smaller things worth carrying:**

1. **The notification "we asked" flag is v1's flag** — `StationlyPrefs` /
   `post_notifications_asked` / `post_notifications_granted`. Third instance of
   the AV2-3.2 storage contract, and the quietest failure yet: a different file
   makes every already-decided user read as `NOT_DETERMINED`, the effect asks
   again, and Android returns the standing answer with no UI at all. A user who
   had *denied* would also lose the banner explaining why no alerts arrive.
   Now pinned by `V1V2StorageContractTest`.

2. **v1's location request is wrong in two ways, and both are fixed.** It asks
   for `ACCESS_FINE_LOCATION` alone — the shape Android documents against on
   API 31+ — and checks for FINE alone, so a user who grants **Approximate** has
   location and v1 refuses to use it. Both permissions are now requested
   together (which is what puts Precise/Approximate in the dialog) and either
   grant is accepted. Verified by granting Approximate on purpose and watching
   the nearby list populate with FINE denied.

3. **The permission request had to move from the UI into the provider.** v1
   launched it from `SelectionScreen`; the shared one is `commonMain` and cannot
   hold an Android launcher. iOS's provider already asks from inside
   `getCurrentLocation`, so the shared contract assumed this all along.

**Next agent needs to know:** AV2-3.5, the cutover, is unblocked and is the
irreversible one. Read it together with the AV2-3.1 finding — v1's summary
crashes on any account that has opened v2, and 3.5 is what deletes that summary.
Two things were deliberately deferred *to* 3.5 and are easy to forget: the
"Continue with Apple" button the shared landing still renders on Android, and
the four deep-link filters still sitting on `MainActivity`.

---

## S009 — 2026-09-06 — AV2-3.4 "Auth and deep links" · **first session with a device**

**Took over** a claim S009 had written into the board and never acted on — no
commits, only the claim row. Gate GREEN on arrival.

**Built.** Google sign-in from the shared `LoginScreen` (it returned a canned
failure before, so sign-in from the shared UI was impossible), Apple left
correctly unavailable, and the deep-link scheme made per-flavour: `stationly` on
prod, `stationly-staging` on staging.

**A device was attached mid-session**, which changed the shape of the work. It
closed out the on-device criteria AV2-3.1 and AV2-3.2 had been sitting in Review
for since S007, and it found things no test would have.

**Learned — four things, in order of how much they cost to find:**

1. **Changing the manifest alone would have shipped the iOS bug, not fixed it.**
   `MainActivity.handleDeepLink` compared `uri.scheme == "stationly"` at four
   call sites. Flip only the manifest and a staging build starts *receiving*
   `stationly-staging://` links and silently drops every one — arriving,
   matching no branch, doing nothing. The scheme is now declared once per
   flavour and spent twice, on the manifest placeholder and
   `BuildConfig.DEEP_LINK_SCHEME`, from a single argument. `handleDeepLink` runs
   on `core`'s `parseDeepLink`, which is what AV2-1.3 extracted it for.

2. **Backing out of a slow sign-in crashed the app, and the crash was disguised
   as a blink.** Press back while "Signing you in…" is up: the Activity is
   destroyed → the NavController's `ViewModelStore` is cleared → `viewModelScope`
   is cancelled → and cancelling *drains* the dispatcher queue, running the rest
   of an already-successful sign-in synchronously inside Activity destruction.
   No suspension points are left after the last `await`, so cancellation is
   never observed and the navigation callback fires into a dead NavHost.
   `IllegalStateException: State must be at least 'CREATED'`. The app then
   relaunched **already signed in**, because Firebase had persisted the
   credential — so it looks like a flicker, not a crash. Fixed with one guard
   (`navigateIfLive`) over all nine navigation callbacks in `LoginViewModel`.
   The only `commonMain` change this session, and provably inert on iOS.

3. **v2 writes user state that crashes v1 on launch.** Once an account has been
   used in the shared UI, the v1 icon dies before drawing:
   `Key "940GZZLUKSX_piccadilly" was already used`. v1 keys its list by
   station+line; the v2 board model keeps one selection **per direction**, so
   King's Cross with Piccadilly east- and westbound is two rows with one key.
   Not a prod risk — no shipped build has both UIs, and AV2-3.5 deletes v1's —
   but it is a trap set for **AV2-8.2**, which will hit it the moment it signs
   one account into both. Recorded under AV2-3.1.

4. **The offline "banner" does not exist, and should not.**
   `computeBoardFallbackState` returns early on `hasPredictions` *before* testing
   `isOnline`, so airplane mode over a live board shows nothing and the board
   keeps ticking on cached data. That is the right behaviour; the acceptance
   criterion was written against a component that was never there. The real
   offline surface is the cold-start "Can't reach servers", and it appeared —
   the device arrived with WiFi enabled but joined to nothing, which tested it
   by accident before anything else could.

**Also settled by hardware, cheaply:** the Google chooser names *"Stationly
Staging"* and mints a token against the staging Firebase project, so the
per-flavour `default_web_client_id` wiring is right; `dumpsys package` lists
`stationly-staging` on all four filters and no `stationly` anywhere;
`am start -d "stationly://home"` answers *"unable to resolve Intent"*; the device
id held one value across a crash, several force-stops, an update install and a
sign-out; and sign-out empties `com.google.android.gms.signin.xml`, so the
chooser genuinely reappears for the next user of a shared phone.

**Next agent needs to know:** AV2-3.3 is the last story before the cutover.
Two things are deliberately left for AV2-3.5, not forgotten: the
"Continue with Apple" button the shared landing screen still renders on Android,
and the four deep-link filters, which stay on `MainActivity` until there is one
door left to move them to. And read Q6 before planning any side-by-side install
test — the two flavours share an `applicationId` and cannot coexist.

---

## S008 — 2026-09-05 — AV2-3.2 "Real actuals, batch A"
**Outcome:** PARTIAL → Review (two acceptance criteria need hardware)
**Gate:** GREEN both ends
**Commits:** see branch head

**Did:** The four stubs became real — connectivity, haptics, mode icons, device
identity — plus `AndroidAppContext`, which is where they get a `Context` and the
current Activity.

**Learned — "delegate to the existing implementation" is not possible, and that
is the whole story.** `:composeApp` cannot import `:android:app`; the dependency
runs the other way. So delegation means agreeing on a *file name*: the same
`SharedPreferences("StationlyDevice")` → `device_id`, the same
`filesDir/mode_icons/<safeName>.png`. Two implementations, one directory, no
compiler watching.

Every failure in that arrangement is silent. A changed prefs file issues every
v1 user a NEW device id, and the backend releases a subscription only when the
last device signs out — so the old session becomes a ghost logout can never
clear. A changed icon directory is quieter still: the shared UI re-downloads
into a second set of files and **the home-screen widget keeps rendering from the
first**, untinted. Neither errors on either side.

`V1V2StorageContractTest` reads those constants off both classes by reflection
and compares them, then runs `ModeIconCache.safeName` against
`modeIconFileName` over the real mode names. It lives in `:android:app` because
that is the only module that can see both — and only on staging, which is where
the shared UI is. AV2-3.5 deletes the v1 halves and this test with them.

**`ModeIconStore.sync` still writes `tints.json`, though nothing in the shared
interface reads tints.** v1's widget does. Do not tidy it out before AV2-3.5.

**Learned — haptics through a View, not `Vibrator`, and the reason is not
style.** `View.performHapticFeedback` respects the user's touch-feedback setting
and needs no `VIBRATE` permission, so adopting the shared UI adds no permission
to an app that is already live. The cost is needing an Activity, which is why
`AndroidAppContext` tracks one — weakly, cleared on pause. AV2-3.3 and AV2-3.4
both need that Activity anyway, so it is built once rather than three times.

**Two files outside the story's list, both logged in the epic.** `core`'s Android
`Platform.appContext` went `private lateinit` → `lateinit … private set` (one
line; the alternative was a second context holder with its own initialisation
order to forget, in an app that already has exactly one place for this).
And `composeApp/build.gradle.kts` gained an `androidUnitTest` source set, plus a
new `composeApp/src/androidMain/AndroidManifest.xml` declaring the two
permissions the Android actuals need — which merges to nothing today, because
`:android:app` already declares both, and that is the point.

**`commonMain` was untouched by both S007 and S008**, so the 20-minute
XCFramework assemble was correctly skipped twice. Check `git status` before
assuming you owe it.

**Next agent needs to know:** AV2-3.3 and AV2-3.4 are both Ready, both `M`, and
independent of each other — either order.

**Take AV2-3.4 first if a tester is waiting.** It is the one a human notices:
`signInWithGoogleInteractive()` currently returns a failure carrying the string
"Use the Google Sign-In button to continue." — v1's flow talking about a button
the shared `LoginScreen` does not have. So sign-in from the v2 door does not
work at all. Its other half, the per-flavour deep-link scheme, is the bug iOS
shipped: a hardcoded `"stationly"` silently dropped every staging link, and
`V2HostManifestTest` currently asserts the v2 host advertises no scheme at all —
that assertion is yours to update, deliberately, not to delete.

**Two device checks are now queued and they are the same trip.** AV2-3.1 needs
"the shared UI opens and navigates"; AV2-3.2 needs the offline banner and — the
one that matters — a v1 install opening the shared UI keeping ONE entry in the
account's device list rather than gaining a second.

---

## S007 — 2026-09-05 — AV2-3.1 "Host the shared UI" · **first change to the shipped app**
**Outcome:** PARTIAL → Review (one acceptance criterion needs hardware)
**Gate:** RED on arrival, GREEN at close
**Commits:** see branch head

**Did:** Gave the shared Compose Multiplatform UI an Android host.
`"stagingImplementation"(project(":composeApp"))`, a `V2MainActivity` in
`src/staging` calling `setContent { App(...) }`, a staging manifest registering it
as a second launcher, and a test that guards the manifest decisions.

**The gate was red when I opened the board**, which the board did not say. The
previous session had claimed AV2-3.1 and left
`stagingImplementation(project(":composeApp"))` in the tree. It does not compile:
Kotlin DSL generates type-safe accessors only for configurations that exist when
the script is compiled, and flavour configurations are created *by* this script.
`"stagingImplementation"(...)` works. **A claim row is not a statement about the
tree** — the protocol says run the gate on arrival, and this is why.

**Learned — the flavour scoping is measured, not cautious.** I checked the
resolved classpaths rather than trusting the comment:

    prod     compose.runtime 1.7.0   material3 1.3.0
    staging  compose.runtime 1.8.0   material3 1.3.2

`compose-bom:2024.09.00` loses to Compose Multiplatform 1.8.0. Plain
`implementation` would have moved the **live** app's Compose runtime out from
under `navigation-compose:2.8.0`, which is pinned to Compose 1.7 by a comment
recording a shipped blank-screen bug.

**⚠️ The flip side, and the next person on staging should know it: v1's own
screens on a staging build now run on Compose 1.8.0 with nav 2.8.0.** Same class
of mismatch, other direction. Prod untouched; AV2-3.5 deletes the v1 stack. If v1
misbehaves on staging during the two-door period, suspect this first.

**Learned — the blank-screen bug has a second, un-fixed half in `commonMain`.**
Shared `AppNavigation` derives `startDestination` from a plain `val`, so a restore
after process death can root a saved back stack on a destination that no longer
matches. v1 fixed exactly this with `rememberSaveable`. I closed the
logged-in/logged-out flip host-side (`startLoggedIn` goes through
`onSaveInstanceState`, because the shared code takes it as a *parameter* — Android
has to save it at the boundary), but the `isEmailProvider()`/`isEmailVerified()`
branch is still recomputed on every restore. Fixing that means editing
`commonMain` on a branch shipping to TestFlight, which is outside this story's
file list. Logged in the epic and in GAP_ANALYSIS §3.3, for AV2-3.5.

**Learned — home-screen pins reference the component name.** AV2-3.5 (a) cannot
just delete `com.stationly.mobile.MainActivity` and promote `.v2.V2MainActivity`:
every v1 user with a pinned icon would find it greyed out. Keep the old name as
the exported launcher, or add an `<activity-alias>`. Written into the epic.

**Two doors, two tasks.** `V2MainActivity` takes its own `taskAffinity`. Sharing
the default would put both launchers in one task where `singleTask` on either
clears the other off the top — opening v1 from the drawer would silently destroy
an open v2 screen, and the tester would debug the wrong thing all afternoon.

**Tested:** `V2HostManifestTest` reads both manifests as files and asserts the
five things that no compiler would notice going wrong — the v2 host is staging
only, v1 is still the launcher and still owns all four deep-link hosts, the v2
host advertises no scheme, it keeps `singleTask` + its own affinity, and the
staging manifest does not take over the `Application` class (which is the closest
static proof there is for task (d), since `Platform` exposes no "initialised"
flag).

**Next agent needs to know:** AV2-3.1 is in **Review**, not Done, for exactly one
reason — no Android device was attached, so "a staging build opens the shared UI
and can navigate" is unverified. Everything else is proven: both flavours
assemble, the prod merged manifest has zero references to the v2 host, and the
prod dependency graph is unchanged.

**AV2-3.2 is Ready and does not wait on that check.** Four stub actuals, and
`DeviceIdentity` is the one that matters: it hands out a fresh UUID per process
today, which breaks device sessions and "logging out releases my subscriptions"
in ways that look like backend bugs. iOS lost two days to this exact class of
problem. The real `DeviceIdProvider` already exists in `:android:app` — the work
is an application-context holder in `composeApp/androidMain` and four delegations.

---

## S006 — 2026-09-05 — AV2-2.3 "Close the drift permanently" · **EPIC-02 complete**
**Outcome:** DONE
**Gate:** GREEN, and it has a new task in it
**Commits:** see branch head

**Did:** Enabled `verifyMigrations`. The build now fails if the `.sq` and the
`.sqm` disagree.

**Learned — the old comment was right about the blocker and wrong about the
fix being hard.** `verifyMigrations.set(true)` does fail with *"Verifying a
migration requires a database file to be present… use the generate schema Gradle
task"*, and that task genuinely **is not registered** by SQLDelight 2.0.2 in this
configuration — `:core:tasks --all` lists only the two `verify…` tasks. That dead
end is what got the earlier attempt reverted.

The way through is realising the baseline does not need generating. It is the
schema as the **last released Android build** created it — a fact about a shipped
APK, not about anything in this tree. So it is built by hand from the same
fixture AV2-2.2 checked in, which means the two cannot disagree. Two shell lines,
recorded in `core/build.gradle.kts`.

**Proven to bite:** a column added to the `.sq` alone now fails the build and
names the column.

**⚠️ Trap for whoever writes `2.sqm`: do NOT touch `1.db`.** It is the start of
the chain, not a snapshot of the present. Regenerating it from the current schema
makes the check vacuous — it would compare the schema against itself and pass
forever. That warning is in the build file too.

**The task is not wired into `check`**, so it is named explicitly in the gate and
in CI.

**And it caught something immediately.** Switching it on broke interface
generation with `1.sqm: (149, 13): Duplicate index name prediction_lookup`. The
migration drops and rebuilds `PredictionEntity` and then recreates its index with
`direction` added; SQLite drops an index with its table, so this was
*functionally* fine — it ran under `sqlite3` and `MigrationTest` passed — but
SQLDelight's analyzer does not model the cascade. An explicit
`DROP INDEX IF EXISTS` before the `DROP TABLE` fixes it and reads better.

Three checks had already called that file correct. The flag found something on
its first run.

**Next agent needs to know:** sprint 1 is done. EPIC-01 and EPIC-02 are both
complete: the gate exists and runs in CI, v1's behaviour is recorded, and a real
v1 database migrates without loss on both schema shapes.

**AV2-3.1 is next and it is the first story that changes the shipped app.** It
adds the `:composeApp` dependency and a second Activity, staging-only, with v1's
`MainActivity` still the launcher. Do not make it the launcher — that is AV2-3.5,
after the actuals are real. And carry over the `launchMode="singleTask"` plus
`onNewIntent` reasoning; the manifest comment explains what breaks without it,
and it shipped as a blank screen once already.

---

## S005 — 2026-09-05 — AV2-2.2 "Prove the migration"
**Outcome:** DONE
**Gate:** GREEN — `:core` 403 tests (9 new). No `commonMain` change, so no
XCFramework run was required.
**Commits:** see branch head

**Did:** `MigrationTest`, 9 tests, against a real SQLite file rather than
`:memory:` — the thing under test is what happens to a database that already
exists on disk. Checked in the v1 DDL as `fixtures/v1/schema-v1.sql`, headed with
a warning that it is a fixture and not a mirror: it must hold still while the
live `.sq` moves, or it stops testing anything.

**Mutation-checked with the two mutations somebody will actually make:**

- Moving the `ActivityEventEntity` guard to the end of `1.sqm` — caught by *an
  iOS-shaped database is refused, and nothing in it is touched*.
- Adding a column to the `.sq` and not the `.sqm` — caught by *a migrated
  database is indistinguishable from a created one*.

The first matters most. The guard is one statement's position in a file, and now
something fails if it moves.

**Learned:** the iOS-shape test needs no transaction of its own and the data
still survives. That is the assertion, not an oversight — the guard being FIRST
means nothing destructive executes, so safety does not depend on the driver
wrapping the migration. The drivers do wrap it; the ordering is what makes it
safe without one.

**A gradle note:** a `:core:testDebugUnitTest` run took 16m46s here, against 1.2s
of actual test time. It was daemon lock contention with a background XCFramework
build, not the tests. If a test run seems to hang, check for a native compile
holding the daemon before you go looking at your test.

**Next agent needs to know:** EPIC-02 has one story left (AV2-2.3,
`verifyMigrations`), and AV2-3.1 is also Ready now. AV2-3.1 is the bigger
unlock — it is the first story where the shared UI actually runs on Android.
Keep the equivalence test in `MigrationTest` after AV2-2.3 lands: it tests the
schema where the flag tests the build, and they fail at different times.

---

## S004 — 2026-09-05 — AV2-2.1 "Reinstate migrations"
**Outcome:** DONE
**Gate:** GREEN
**Commits:** see branch head

**Did:** Rewrote the `.sq` banner (the premise it rested on is dead, and the
hand-maintained list that was wrong is replaced by the command that regenerates
it). Wrote `migrations/1.sqm` as a table rebuild. Bumped the AV2-1.1 tripwire
1 → 2; it fired exactly as designed.

**Learned — and it changed the migration, not just the notes:**

**"Version 1" means two different schemas.** `StationlyDatabase.Schema` is shared
by both platforms and its version is the migration count, so the number is
global. But Android's released devices were created from the OLD `.sq` and iOS's
TestFlight devices from the CURRENT one — iOS databases are stamped version 1 and
already contain everything this migration adds.

Left alone, the rebuild would have copied only the eight columns an Android v1
row has and silently destroyed `parentStationId`, every filter, `viaKeys` and
`patternIds` on every iOS device, then crashed at the activity table anyway.

There is no pure-SQL migration correct for both shapes. So
`CREATE TABLE ActivityEventEntity` is now the FIRST statement, and its position
is the guard: on an iOS-shaped database it throws before a row is touched and the
transaction rolls back. Verified on both shapes; iOS data comes through
untouched. MIGRATION.md §1.4b has the reasoning.

**A harness trap worth an hour of somebody's life:** `sqlite3` without `-bail`
**keeps going after an error**. My first iOS-shape run sailed past the guard,
destroyed the data, and told me the guard did not work. It does — the harness was
wrong, and the drivers stop on the first failure. Always `-bail`.

Also verified by hand: `PRAGMA table_info` is identical between `Schema.create`
and the migration for all five tables, plus both indexes. AV2-2.2 turns that into
a test rather than discovering it.

**Next agent needs to know:** AV2-2.2, and it gained a task (g) — cover the
iOS-shaped database. The guard is one statement's position in a file and nothing
else protects it; a well-meaning reorder silently re-arms the data loss. Do not
add `IF NOT EXISTS` to that statement, for the same reason.

**Q5 raised:** iOS TestFlight testers on an older build must delete and reinstall
once when this ships.

---

## S003 — 2026-09-05 — AV2-1.3 "Capture v1's golden outputs" · **EPIC-01 complete**
**Outcome:** DONE
**Gate:** GREEN — `:core` 394 tests (11 new), XCFramework assembles
**Commits:** see branch head

**Did:** Four fixtures under `docs/android-v2/fixtures/v1/` and `V1GoldenTest`
reading them. Captured while a v1 build still exists to capture from — after
AV2-3.5 the only source is git history.

**Learned:**

1. **`AndroidWidgetManager.formatForWidget` is a placeholder.** It returns a
   hardcoded `"Loading..."` and always has. `FormatDeparturesUseCase` is the
   real formatter, called from `DepartureWidgetProvider`. Anyone wiring the v2
   widget by following the `WidgetManager` interface name would wire it to
   nothing. Flagged in EPIC-05.
2. **A v1 blob folds to a board per POLE.** No `parentStationId`, so `groupingId`
   falls back to `station`. That is what the user's phone shows today, not
   corruption — AV2-4.3 must not "repair" it.
3. **The destination truncation ends mid-token**: a raw 22-char cut, so
   `"Heathrow Terminals 2 & 3 via Hatton Cross"` becomes
   `"Heathrow Terminals 2 &..."`, dangling ampersand and all. My fixture guessed
   otherwise and the test corrected me — which is the fixtures doing their job on
   day one.

**Scope note:** as in S002, the story had nothing to point at for the deep-link
table. It was a `when` over `android.net.Uri` in `MainActivity` (deleted by
AV2-3.5), with iOS keeping its own copy. It is now
`core/.../model/deeplink/DeepLink.kt`, and **the scheme is a parameter** — the
iOS per-environment bug encoded so it cannot recur. Two test stories have now
each needed one small extraction. That is a pattern, not a coincidence: v1 put
shared logic in ViewModels and Activities, so anything worth pinning has to be
lifted out before it can be pinned.

**Next agent needs to know:** EPIC-01 is complete and **AV2-2.1 is next — the
riskiest story in the programme**. Read `analysis/MIGRATION.md` §1 in full and
regenerate the schema delta rather than trusting any written list, including the
one in that document. The `.sq` banner's own list is missing 14 of the 21 items.

And the tripwire from S001 fires here: `SchemaHarnessTest` asserts schema
version `1`; adding `migrations/1.sqm` makes it `2`. Bump it, do not delete it.

---

## S002 — 2026-09-05 — AV2-1.2 "Lock the v1 contract"
**Outcome:** DONE
**Gate:** GREEN — `:core` 383 tests (9 new), XCFramework assembles
**Commits:** see branch head

**Did:** Wrote `V1ContractTest` — the flat `stations` wire form both ways, and
the v1 FCM payload. Mutation-checked both halves rather than trusting a green
run: dropping `parentStationId` fails 3 tests, folding only the first queue per
board fails 3.

**Learned — and this one is a live bug, not a note:**

There was **no** `Board → SubscribedStation` conversion to test. It existed four
times, hand-written: three ViewModels outbound, `UserSyncRepository` inbound.
**Two of the three outbound copies drop `parentStationId`** —
`ProfileViewModel.loadStations` and `SummaryViewModel`'s delete-sync. A station
restored without it groups on its own naptan, so one bus hub comes back as a
card per pole with the same name on every one.

This is a v1 bug that is live today, and it is a direct hazard to AV2-4.3: the
dual-write's whole purpose is that a v1 device sees a degraded-but-*correct*
view, and a split hub is not correct. The four copies are now one —
`model/user/LegacyStationList.kt` — and `UserSyncRepository` calls it. The three
ViewModels are deleted by AV2-3.5, so they were left alone rather than patched.

**Scope note:** this test story made a production change. A contract test needs
one named thing to point at, and there wasn't one. Flagging it because the
protocol says work outside a story's file list is a finding, not a licence.

**Next agent needs to know:** AV2-1.3 is next and it is the last thing standing
between here and the database work. It captures v1's golden outputs **while a v1
build still exists to capture them from** — after AV2-3.5 deletes the v1 UI, the
only source is git history. `BoardTest` already covers `Board` ⇄ `UserSelection`
thoroughly (18 tests); do not re-cover it.

---

## S001 — 2026-09-05 — AV2-1.1 "The gate, in CI"
**Outcome:** DONE
**Gate:** GREEN — `:core` 374 tests, `:composeApp` pass, `:android:app` 4 tests
(new), both Android compiles pass
**Commits:** see branch head

**Did:** Built the gate and put it in CI. Added `core/src/androidUnitTest` on the
SQLDelight JDBC driver — the source set EPIC-02 cannot be tested without, since
`commonTest` has no driver and an instrumented test would need a device on every
run. Added `SchemaHarnessTest` to prove the harness reaches the schema, and
`android/app`'s first ever unit test.

**Learned:**

1. **CI cannot compile `:android:app`.** `google-services.json` is gitignored and
   untracked; the `google-services` plugin fails outright without it. The
   workflow now runs the shared half unconditionally and the Android half only
   when a `GOOGLE_SERVICES_STAGING_B64` secret exists, emitting a GitHub warning
   annotation when it does not — so it can never pass quietly while implying
   coverage it lacks. Raised as **Q4**; one `base64` command from the owner
   closes it.
2. `BackendErrorUtil` classifies partly on exception type and partly on message
   *text* ("timeout", "failed to connect"). That is the kind of rule that rots
   silently when a library changes its wording, which is why it was chosen as
   the first thing in `:android:app` to get a test.

**Next agent needs to know:**

- **A tripwire is armed and AV2-2.1 will trip it.** `SchemaHarnessTest.the schema
  version matches the migration count` asserts `1`. Adding `migrations/1.sqm`
  makes the version `2` and the test fails. This is deliberate — SQLDelight
  derives the version from the migration count, so the assertion is what catches
  a `.sq` changed without a matching `.sqm`. AV2-2.1 task (g) says bump it to `2`
  in the same commit. **Do not delete the test to make it pass.**
- An `ios-contract` job was added beyond the story's scope: macOS,
  `:composeApp:assembleComposeAppDebugXCFramework`. It is the C2 contract's only
  real check, because Xcode will link a stale framework and give a green build
  that proves nothing. It needs no secret.
- AV2-2.1 is now Ready. Read `analysis/MIGRATION.md` §1 in full before starting
  it, and regenerate the schema delta rather than trusting either written list.

---

## S000 — 2026-09-05 — project setup
**Outcome:** DONE
**Gate:** GREEN (`:core:testDebugUnitTest`, `:composeApp:testDebugUnitTest`,
`:android:app:compileStagingDebugKotlin`, `:composeApp:compileDebugKotlinAndroid`)
**Commits:** _(see branch head)_

**Did:** Cut `dev_android_bring_to_v2` from `ios-parity` @ `b7b7a1c`. Measured
the actual state of the repo rather than assuming it, took four decisions
(D1–D4), and built this workspace: gap analysis, migration plan, test strategy,
gap graph, and the board.

**Learned** — three measurements reframed the project:

1. `ios-parity` is a **strict superset** of `master` (117 ahead, 0 behind). There
   was never an Android branch to merge back.
2. `:android:app:compileStagingDebugKotlin` is **green** on `ios-parity`. The
   shipped app was kept compiling against the v2 core as it moved — 38 lines
   across 5 files is the whole of its adaptation.
3. `:composeApp:compileDebugKotlinAndroid` is **green**. The entire iOS v2 UI
   already builds for Android; its Android `actual`s exist and are deliberate
   placeholders, each one commented as a "build-verification surface only".

And one finding that changes a plan rather than the map: the hand-maintained
list in the `StationlyDatabase.sq` banner, of what an Android upgrade owes, is
**incomplete**. It names 7 items. The computed delta is 21 — it omits five
`UserSelectionEntity` columns, three `PredictionEntity` columns, and **both**
primary-key changes. Anyone writing the migration from that list would ship a
broken one. See `analysis/MIGRATION.md` §1.2.

Also recorded: `backup_rules.xml` includes `domain="database"`, so Android Auto
Backup can restore a **v1 database into a fresh v2 install**. The upgrade path is
a larger set than "devices that had v1 installed", and a test plan scoped to
in-place upgrades would miss it entirely.

**Next agent needs to know:** start at AV2-1.1. Do not start the migration
(EPIC-02) before the gate runs in CI — it is the one piece of work where a
regression reaches users who have been using the app the longest, and it is
invisible on every development device, all of which are fresh installs.
