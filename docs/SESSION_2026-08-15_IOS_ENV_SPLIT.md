# Session 2026-08-15 — iOS staging/production split + TestFlight groundwork

**Branch:** `ios-parity`. **Everything is UNCOMMITTED working-tree changes** — the user asked
explicitly for nothing to be committed this session.

**Plan doc:** `docs/IOS_ENV_SPLIT_AND_TESTFLIGHT.md` (the full plan; §6 is the owner checklist).
This file is the session record: what was actually done, what was verified how, and what is
still unproven.

---

## 1. The bug this session existed to fix

Before this, the ONLY difference between iOS staging and production was the string
`STATIONLY_ENVIRONMENT`, which picks the API URL. Everything else was shared — including a
single `GoogleService-Info.plist` belonging to the **staging** Firebase project
`mindthetimefcm`.

So `Debug/Release Production` pointed the app at `api.stationly.co.uk` while authenticating
against the staging Firebase project: a production build would have minted a staging uid and
handed a staging ID token to the production backend. Never caught, because no production build
had ever been run.

Also shared, and each its own latent collision once the environments could coexist: the bundle
id, the App Group `group.com.stationly.shared`, the `stationly://` URL scheme, the two
BGTaskScheduler identifiers, the display name, and the backend API key (a single hardcoded
`IOS_API_KEY` in `Platform.ios.kt`, where Android has read per-flavor keys from
`local.properties` since it shipped).

---

## 2. Where configuration lives now

`iosApp/Config/`, one file per environment:

| File | Role |
|---|---|
| `Base.xcconfig` | shared values; versioning; `#include?`s the secrets file |
| `Staging.xcconfig` | every staging value — all real and live |
| `Production.xcconfig` | same keys, **placeholders** where the console work is outstanding |
| `Secrets.example.xcconfig` | template for the git-ignored `Secrets.xcconfig` |
| `GoogleService-Info-Staging.plist` | real (`mindthetimefcm`) |
| `GoogleService-Info-Production.plist` | **placeholder stub**, deliberately has no `GOOGLE_APP_ID` |
| `exportOptions-{Staging,Production}.plist` | TestFlight export |
| `make-staging-icon.swift` | regenerates the staging app icon |

### Three traps encoded in those files

1. **Precedence.** A setting written into a target's `settings:` block in `project.yml` BEATS
   the xcconfig. `project.yml` may only *reference* these as `$(NAME)`, never assign them.
   Assigning `STATIONLY_ENVIRONMENT` there is precisely what made `Release Production`
   silently unusable before.
2. **`//` starts an xcconfig comment anywhere on a line**, including mid-value. This is why
   `STATIONLY_URL_SCHEME` stores the bare scheme name and the `://` is added by callers.
3. **Include ordering.** Environment files include `Base.xcconfig` at the top, so their own
   later assignments beat anything it defined. Secrets therefore use environment-qualified
   names (`STATIONLY_API_KEY_PRODUCTION`) which `Production.xcconfig` reads through
   `$(…:default=…)`, so the placeholder applies only when the secrets file is truly absent.

### Resolved values

| | Staging | Production |
|---|---|---|
| Bundle id | `com.stationly.mobile.staging` | `com.stationly.mobile` |
| Widget | `…staging.StationlyWidget` | `…mobile.StationlyWidget` |
| Display name | Stationly Staging | Stationly |
| App Group | `group.com.stationly.staging` | `group.com.stationly.shared` |
| URL scheme | `stationly-staging` | `stationly` |
| BGTask ids | `…staging.widgetrefresh` / `.activityupload` | unchanged |
| Firebase | `mindthetimefcm` | ⚠️ placeholder stub |
| API key | committed (already in git history) | ⚠️ placeholder → `Secrets.xcconfig` |
| App icon | `AppIconStaging` (grey field) | `AppIcon` (white field) |

Production deliberately keeps the ORIGINAL bundle id and App Group. Staging is the side that
moved, so no existing install is orphaned and the id already registered with Apple is the one
any App Store release carries.

---

## 3. Code changes beyond configuration

- **The App Group string is no longer a literal anywhere.** It was copied into four
  compilation units — `iosApp/AppGroupID.swift`, `StationlyWidget/AppGroupID.swift`,
  `IosAppGroup.ID` in `core/iosMain`, and the entitlements — each carrying a comment begging
  the next person to keep them in lockstep, because a missed copy does not fail the build: it
  silently opens an EMPTY suite, indistinguishable from "the data was never written". All four
  now resolve the `StationlyAppGroup` Info.plist key. **The hazard is retired, not documented.**
  - Consequence: `IosAppGroup.ID` is a `val`, not a `const val`. Two downstream
    `private const val APP_GROUP_ID` declarations in `composeApp/iosMain` widened to match.
  - The widget needs its OWN copy of the key: `Bundle.main` in an app extension is the
    extension's bundle, not the containing app's.
- **`iosApp/BGTaskIdentifier.swift`** (new) composes the background-task ids from the running
  bundle id, so they cannot drift from `BGTaskSchedulerPermittedIdentifiers`. The environment
  suffix goes in the MIDDLE (`com.stationly.mobile.staging.widgetrefresh`) because Apple
  expects the identifier to be prefixed by the bundle id — appending `.staging` would break
  that. A mismatch here is a **launch-time crash**, not a warning.
- **`StationlyDeepLink`** reads the scheme from Info.plist instead of hardcoding `stationly://`.
  Two installed apps claiming one scheme lets iOS route a staging widget tap to the production
  app, arbitrarily.
- **`IOS_API_KEY`** in `Platform.ios.kt` now reads the `StationlyApiKey` Info.plist key.

---

## 4. TestFlight groundwork

- **The release-XCFramework trap — the most valuable fix in the session.** `project.yml`
  linked `XCFrameworks/debug` unconditionally, so a Release archive would have shipped
  unoptimised debug Kotlin with a green build and no warning anywhere. It now links a
  `current` symlink that a first-position build phase repoints per configuration
  (`STATIONLY_KOTLIN_BUILD`), failing loudly when the needed variant is absent.
  XcodeGen writes the framework path into a file reference and Xcode does not expand build
  settings there, which is why a symlink rather than a variable path.
- **Privacy manifests** for both targets. `UserDefaults` (reason `CA92.1`, own App Group) is
  the only required-reason API in use — audited and confirmed absent: file timestamps, disk
  space, boot time, active keyboard. (`AuthBridge.swift`'s `creationDate` is a Firebase model
  property, not a filesystem API.) Without a manifest, ASC rejects the upload.
- **`ITSAppUsesNonExemptEncryption: false`** in the Info.plist, so uploads do not stall in
  "Missing Compliance" awaiting a human click — fatal for automation.
- **Versioning single-sourced** in `Base.xcconfig`. App and extension `CFBundleVersion` were
  declared separately and held equal by hand; ASC **refuses** an upload where they differ.
- **`scripts/ios-testflight.sh`** — assembles the release framework, archives, then VERIFIES
  the archive before sending: app/extension build numbers match, the Firebase config is not a
  placeholder, the privacy manifest is present. Hard-refuses `--env production`.
- **`scripts/ios-dev.sh`** — the on-device cycle, replacing the five-step manual sequence in
  `IOS_BUILD_AND_HANDOFF.md` §2. Reads the bundle id and App Group back out of the BUILT app
  rather than hardcoding them.

---

## 5. The app icon

> **Superseded in §9i** — the grey field described below was replaced the same evening by a
> charcoal field with an amber `STAGING` band. The flood-fill machinery is unchanged and the
> notes on the three failed approaches still apply; only the colours and the band are new.

The production iOS icon already matched Android exactly — `#E12724` vs Android's `#E22623`, a
1/255 PNG encoder rounding difference. It was **not** touched.

`make-staging-icon.swift` derives the staging variant. Three approaches failed first, and all
three looked fine until inspected:

1. A translucent wash over the canvas — dragged the brand red to `#B81F1E`, visibly duller.
   **This was the "dull red" the user spotted.**
2. "Recolour every light pixel" — the S counter inside the roundel is white too, and turns grey.
3. Clip to the mark's bounding ellipse — the roundel is 691×670 (not a circle) and carries a
   soft drop shadow, leaving a white crescent along the lower-left arc.

The working approach is a **flood fill from the image border**: every light pixel reachable
from the edge becomes grey; the enclosed counter is never reached. No assumption about the
mark's shape. Red verified unchanged at `#E12724`.

---

## 6. What was actually verified, and how

**Built** (`BUILD SUCCEEDED`, signed, generic iOS destination): both schemes. Production emits
its intended placeholder warning and skips `FirebaseApp.configure()`.

**Read out of the built `.app`/`.appex`**: bundle ids, App Group, URL scheme, BGTask ids,
display name, entitlements, and the correct Firebase plist in the bundle.

**Tested on the iPhone 11** (installed the Debug Staging build, launched, pulled the App Group
container):

- App launched and stayed up → the Info.plist read path works. The `fatalError`/`require`
  guards would have killed it on first frame.
- `bgtask scheduled in 30m` → `BGTaskScheduler.register` accepted the derived identifier. This
  was the launch-crash risk.
- `group.com.stationly.staging` exists on device and is being written.
- `apns token received bytes=32`, `isRegistered=true` → push registration survives the new
  bundle id; Xcode auto-provisioned the App ID and App Group with Apple during the signed
  build, so **staging needed no console work**.
- Two `iosApp` processes and two widget extensions from different bundle containers → the old
  `com.stationly.mobile` and the new `.staging` **coexist**, which was the point of the split.

### Not verified

- **`scripts/ios-dev.sh` has never been run.** The device test above installed the existing
  DerivedData build directly. The script also has a known defect: it discovers the device via
  `xctrace list devices`, which reported this phone *offline* while `devicectl list devices`
  reported it `available (paired)`. **It should use `devicectl`.**
- **`scripts/ios-testflight.sh` has never been run**, `--dry-run` included. The entire release
  archive path is unexecuted; release signing is where surprises live.
  - The release Kotlin framework itself DOES build clean
    (`./gradlew :composeApp:assembleComposeAppReleaseXCFramework`, exit 0), so
    `composeApp/build/XCFrameworks/release/` exists and the `current` symlink has something
    to point at. That is the prerequisite, not the test.
- **A `stream:EXCEPTION JobCancellationException` reconnect loop** appears in the push trace,
  backing off repeatedly. Unknown whether it is pre-existing (the app is signed out, so the
  live stream may have nothing to attach to) or caused by the split. Needs a comparison
  against the pre-split build.
- Sign-in on device. A CLI-launched app **cannot read the Keychain**, so FirebaseAuth reports
  "no user" on a signed-in phone. Auth must be tested by opening the app by hand.
- The widget's visual state under the new empty container.

---

## 7. Owner-side actions still outstanding

Nothing here can be done from the repo. Only 1–3 block production; **staging needs none of it.**

1. Firebase console → `stationly-prod` → add iOS app for `com.stationly.mobile`; enable the
   Google and Apple sign-in providers; upload the APNs `.p8` for team `7T7D5LLYSL`.
2. Commit the downloaded plist over `Config/GoogleService-Info-Production.plist`, and copy its
   `REVERSED_CLIENT_ID` into `GOOGLE_REVERSED_CLIENT_ID` in `Config/Production.xcconfig`.
3. Production backend API key → git-ignored `Config/Secrets.xcconfig`.
4. **Blocks the first staging TestFlight upload:** create the "Stationly Staging" app record in
   App Store Connect for `com.stationly.mobile.staging`, and an ASC API key (role: App Manager)
   for `ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_KEY_PATH`.
5. Decide the tester model. Recommendation in the plan doc: **external** group for friends and
   family. Internal testers must be users on the ASC team, which hands a dozen friends access
   to the whole developer account. External costs one Beta App Review (~24–48h) per version,
   and the staging app is never submitted for sale, so beta review is the only review it sees.

---

## 8. SESSION CLOSED — resume here

**Status at close: staging split is DONE and working on device. TestFlight is prepared but
never exercised. Production is placeholders by request.**

### ⚠️ Nothing is committed

The user asked explicitly that nothing be committed this session. The entire session lives in
the working tree on `ios-parity`. **Do not `git stash`, `git checkout .`, or rebase before
reading this** — the changes span 16 modified files and 15 new ones, and several (the xcconfigs,
the privacy manifests, `BGTaskIdentifier.swift`) exist nowhere else.

```
 M .gitignore
 M composeApp/src/iosMain/.../HomePromoPlatform.ios.kt
 M composeApp/src/iosMain/.../ModeIconStore.kt
 M core/src/iosMain/kotlin/platform/Platform.ios.kt
 R iosApp/iosApp/GoogleService-Info.plist -> iosApp/Config/GoogleService-Info-Staging.plist
 M iosApp/StationlyWidget/{AppGroupID.swift,DepartureEntry.swift,Info.plist,StationlyWidget.entitlements}
 M iosApp/iosApp/{ActivityUploadScheduler,AppGroupID,BackgroundRefreshScheduler}.swift
 M iosApp/iosApp/{Info.plist,iosApp.entitlements}
 M iosApp/project.yml
 M iosApp/iosApp.xcodeproj/project.pbxproj        ← generated; regenerate, don't hand-edit
?? docs/IOS_ENV_SPLIT_AND_TESTFLIGHT.md
?? docs/SESSION_2026-08-15_IOS_ENV_SPLIT.md
?? iosApp/Config/                                  ← the whole environment split
?? iosApp/iosApp/Assets.xcassets/AppIconStaging.appiconset/
?? iosApp/iosApp/BGTaskIdentifier.swift
?? iosApp/{iosApp,StationlyWidget}/PrivacyInfo.xcprivacy
?? scripts/                                        ← ios-dev.sh, ios-testflight.sh
```

`iosApp.xcodeproj/project.pbxproj` is generated by `iosApp/xcodegen.sh` from `project.yml`.
Run it after any `project.yml` or xcconfig change; never edit it directly.

### Not committed also means not proven against a clean checkout

`composeApp/build/XCFrameworks/current` is a SYMLINK created this session. It is inside
`build/`, so it is untracked and a clean clone will not have it — the "Select Kotlin
XCFramework" build phase recreates it, but that path has only ever run on this machine.

### Do these first, in order

> **Superseded — see §9**, written the same evening. Items 1 and 2 below were done there,
> and item 1 failed in a way nobody predicted.

1. **`scripts/ios-testflight.sh --build 5 --dry-run`.** The single largest unknown. The whole
   release archive path — release signing, the `current` symlink under `Release Staging`, the
   archive verification block — is unexecuted. Its checks will catch the known traps if it
   gets that far. The release framework already builds clean, so this should be quick.
2. **Fix `scripts/ios-dev.sh` device discovery**, then run it end to end. It uses
   `xctrace list devices`, which reported the iPhone 11 *offline* while
   `devicectl list devices` reported it `available (paired)`. Switch to `devicectl`. The
   script has never been run at all.
3. **Determine whether the `stream:EXCEPTION JobCancellationException` reconnect loop is
   pre-existing** (see §6). Most likely just a signed-out app with nothing to attach to, but
   it appeared in the first trace taken after the split and has not been compared against a
   pre-split build.

### Then, once the owner has done §7

4. First staging TestFlight upload (§7.4 blocks it: the ASC app record and API key).
5. Production only after §7.1–7.3 — and remove the `--env production` guard in
   `scripts/ios-testflight.sh`, which is there on purpose.

---

## 9. Session 2 — same day, evening: first archive attempt

Still uncommitted, still `ios-parity`. Owner checklist now lives in
`docs/IOS_OWNER_SETUP_CHECKLIST.md`, which is the authoritative console runbook; §7 above is
the overview.

### 9a. App Store Connect — done and verified against the live API

Both owner blockers from §7.4 are cleared. Verified by signing a JWT with the new key and
calling `GET /v1/apps` (HTTP 200 — which simultaneously proves the key, the key id, the
issuer id and the role):

| | |
|---|---|
| `com.stationly.mobile.staging` | "Stationly Staging", SKU `stationly-staging`, appId **6801918900** |
| `com.stationly.mobile` | "Stationly: Live Tfl Departures", appId **6799715716** |
| API key | role App Manager; id/issuer/path live in `~/.appstoreconnect/env.sh` |

The **production record already existed**, which §7 did not know. Both apps sit at version
1.0 / `PREPARE_FOR_SUBMISSION` with **zero builds** — nothing has ever shipped on iOS.

Credentials live in `~/.appstoreconnect/env.sh` (mode 600, outside the repo);
`source` it before running the upload script. Exporting them in one shell is not enough —
they vanish with it, and the script then fails on a missing variable somewhere unrelated.

### 9b. App Groups: already registered — this item in §7.3 was stale

Decoding the provisioning profiles in
`~/Library/Developer/Xcode/UserData/Provisioning Profiles/` shows Xcode's automatic signing
registered everything during session 1. The staging profile carries
`com.apple.security.application-groups → group.com.stationly.staging`, plus `applesignin`
and `aps-environment`; the production one carries `group.com.stationly.shared`.

**The App Group capability lives on the App ID, not on the profile**, so the App Store
distribution profile inherits it and the switch to release signing does not re-open the
question. No portal work was or is needed.

The one real signing gap: the keychain holds only **Apple Development** certificates.
`-allowProvisioningUpdates` mints a distribution cert on the first archive.

### 9c. 🔴 The release link OOMs — `gradle.properties` was the ceiling

The dry run failed after 9 minutes:

```
e: Compilation failed: Java heap space
Execution failed for task ':composeApp:linkReleaseFrameworkIosArm64'
```

`org.gradle.jvmargs` was `-Xmx2048m` on an **8 GB** machine. The Kotlin/Native compiler runs
*in* the Gradle daemon, and the release link optimises across the whole program — it needs
several times the heap of the debug link that every device build had always used. Nothing
about the debug path ever approached the ceiling, which is exactly why this survived until
the first archive.

Raised to `-Xmx4g`, with the reasoning recorded in `gradle.properties` itself. Not higher:
`xcodebuild` runs immediately after, and past roughly half of physical RAM the daemon loses
to swap. If it recurs, prefer `org.gradle.parallel=false` over more heap.

**This is the clearest vindication of the dry run.** It is invisible to every debug build and
would have surfaced mid-upload otherwise.

### 9d. 🔴 Sign in with Apple broken by the split — Firebase registration, now fixed

Reported from the device mid-session. Root cause, confirmed on all three sides:

```
installed app  : com.stationly.mobile.staging     (devicectl device info apps)
plist inside it: BUNDLE_ID = com.stationly.mobile (GoogleService-Info-Staging.plist)
mindthetimefcm : one iOS app, registered com.stationly.mobile
```

Apple mints an identity token whose `aud` is the **running** bundle id. Firebase checks it
against the bundle ids registered in the project, finds no match, rejects the credential.
Google sign-in breaks identically — iOS OAuth clients are bundle-id-bound. Email/password is
unaffected, which makes it the right choice for the Beta App Review demo account.

The Swift in `AuthBridge.swift` is a textbook-correct Firebase Apple flow and was never the
problem.

**Fixed:** a new iOS app registered in `mindthetimefcm` for `com.stationly.mobile.staging`
(`GOOGLE_APP_ID …ios:40d34a719e55daba9e5ab9`). Firebase cannot rename a bundle id, so a new
registration is the only route. The plist replaced `Config/GoogleService-Info-Staging.plist`
and its new `REVERSED_CLIENT_ID` (`…-4fn7sigv0…`, was `…-g7alcuuk9ld0…`) went into
`Config/Staging.xcconfig`. **Those two move together** — the OAuth client is minted per
registered app, not per project — and the script now cross-checks them.

Two traps recorded for whoever hits this next:

- The **Android** `com.stationly.mobile` entry in the same Firebase project is live and must
  not be deleted. Android keeps one applicationId across both flavors and separates
  environments by Firebase project alone; only the *iOS* entry was vestigial.
- **iOS deliberately diverges from Android's model** here. Android shares an applicationId
  because its staging build is sideloaded, never on Play. iOS staging must go through
  TestFlight, and App Store Connect keys the app record, the tracks and the testers off the
  bundle id — so a distinct bundle id is forced by distribution, not chosen for symmetry.

### 9e. Both scripts now refuse to guess the environment

`ios-dev.sh` and `ios-testflight.sh` took `--env`, defaulting to `staging`. The environment
now has to be named — first positional argument, `prod` accepted for `production`, `--env`
still honoured, and **omitting it is an error**:

```
scripts/ios-dev.sh        staging [--kotlin] [--no-launch] [--pull-group]
scripts/ios-testflight.sh staging --build <n> [--dry-run]
```

A default is the one part of a command you cannot see when reading it back in a shell history
or a CI log, and the two apps now install side by side. `ios-testflight.sh` also prints a
banner (environment, scheme, config, bundle id, build number) before doing anything, with the
bundle id read out of the xcconfigs, and **asserts the archive's bundle id matches** the
environment named — which catches anything breaking the xcconfig → build-settings chain.

### 9f. `ios-dev.sh` device discovery fixed

Now `devicectl`, via `--json-output` rather than the text table. Three reasons the old
`xctrace` grep/sed was wrong, beyond it reporting this phone offline: the table is
column-aligned rather than delimited, device names contain spaces and typographic
apostrophes ("Nick's iPhone"), and a paired Apple Watch sits in the same list. It filters on
`platform == iOS && deviceType == iPhone && pairingState == paired` and returns the **UDID**,
because `xcodebuild -destination id=…` wants that rather than devicectl's own identifier.

The first run got as far as `▸ Device: <udid>` — discovery works, and the
UDID is the right one — then died on a bug that had been sitting in the script since it was
written:

```
./scripts/ios-dev.sh: line 158: SCHEME…: unbound variable
```

`echo "▸ Building $SCHEME…"`. macOS ships **bash 3.2**, whose identifier scan walks bytes and
treats the high bytes of a UTF-8 character as name characters — so `$SCHEME…` is read as a
variable named `SCHEME` + the three ellipsis bytes, which under `set -u` aborts immediately.
Two occurrences, both fixed by brace-delimiting (`${SCHEME}…`, `${BUNDLE_ID}…`).

Worth knowing generally, because this codebase's shell scripts use "…" in progress messages
throughout: **`$VAR` followed by any non-ASCII character needs braces.** The pattern to audit
with is `grep -nP '\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7F]'`. It is invisible until the line
actually executes, which is why an unrun script hid it.

### 9g. Disk

The machine was down to **4.3 GB free**, which the archive would not have survived. Removed
5.7 GB of regenerable build output → 9.9 GB: in-tree leftovers under `iosApp/build`
(`DerivedData`, `obj`, `sym`, `XCBuildData`, `iosApp.build`, precompiled-module dirs — all
superseded by the `DD`/`DD-release` paths the scripts pass), both simulator build products,
and Xcode's GUI DerivedData for this project plus the shared module cache.

Left in place, all still reclaimable: `iosApp/build/DD` (6.5 GB, the warm debug cache — costs
a full rebuild), `iOS DeviceSupport` (5.3 GB, re-fetched on next device attach), stale
Kotlin/Native toolchains 1.9.20 / 2.0.0 / 2.1.21 (**3.8 GB, free money** — the project uses
2.2.0), and old Gradle distributions (~300 MB). The K/N and Gradle caches could not be
touched while a build was running.

Also moved `AuthKey_FCDBFFZUBW.p8` out of `~/Downloads` to `~/.apple-keys/` (600) and deleted
two stray `GoogleService-Info` plists from there.

### 9i. The staging icon, again — grey out, charcoal + amber band in

The grey field from §5 was rejected on sight, and the reason generalises: **it preserved the
silhouette.** At home-screen size the eye separates icons by overall lightness and outline
long before it resolves detail, and grey-behind-red vs white-behind-red gives it neither —
both tiles read as "red circle with an S". Worse, a desaturated field reads as artwork that
failed to load rather than as a deliberate variant.

The replacement changes the thing that is actually legible at 60pt — a dark tile among light
ones — and then states the environment outright for anyone who looks closer:

| | |
|---|---|
| Field | `#1C1C1E` charcoal |
| Band | `#F9B21A` amber, bottom 21%, `STAGING` in charcoal HelveticaNeue-Bold |
| Mark | untouched — sampled `#E02724` in both icons at the same pixel |

Four candidates were rendered and compared before choosing (charcoal alone, amber field,
white + charcoal band, charcoal + amber band). Amber-as-field was the clear loser: warm mark
on warm field, the lowest mark/field contrast of the set.

`iosApp/Config/make-staging-icon.swift` regenerates it; the flood-fill logic and the notes on
the three failed approaches are unchanged. Two things worth knowing about the output:

- The **rim pass matters more now.** It nudges anti-aliased white/field blend pixels the rest
  of the way to field colour. Against light grey a leftover halo was invisible; against
  charcoal it would be glaring.
- The white bar runs **edge to edge**, so the S counter is connected to the outside through
  it. Counter, bar and surround are one flood-fill region and take the field colour together
  — which is why the S reads as a cut-out of the field rather than as white ink. Correct, and
  the reason §5's claim that "the S counter is enclosed by red and stays white" is wrong for
  this artwork.

iOS masks the icon to a rounded superellipse, clipping the band's bottom corners. Intended.
Output verified 1024², `hasAlpha: no` — an alpha channel is rejected at upload.

Also removed: a `stationly-backend.code-workspace` that had been saved *inside*
`AppIconStaging.appiconset`. Rewritten for its new home at
`~/workspace/Projects/stationly.code-workspace`, which is the directory actually containing
the four repos it references.

### 9j. ✅ The dry run passed — the release path is proven

`scripts/ios-testflight.sh staging --build 5 --dry-run`, **36 minutes**, exit 0. Every stage
that had never been executed now has been.

```
xcframework written to .../XCFrameworks/release/composeApp.xcframework
▸ Archiving iosApp Staging (build 5)…
  Kotlin XCFramework: release
  Firebase config: GoogleService-Info-Staging.plist (staging)
** ARCHIVE SUCCEEDED **
▸ Archive: com.stationly.mobile.staging  app=5  widget=5
** EXPORT SUCCEEDED **   →  iosApp/build/export/staging-5/Stationly Staging.ipa  (20 MB)
```

What that actually settles, each verified by reading the built artefact rather than the log:

- **The release-XCFramework trap is really fixed.** `current` repointed `debug → release` and
  the archive logged `Kotlin XCFramework: release`. This was §4's "single most likely thing to
  be discovered late"; it is now the thing most thoroughly proven.
- **The 9d Firebase fix reached the binary.** Inside the archive:
  `BUNDLE_ID = com.stationly.mobile.staging`, `GOOGLE_APP_ID …ios:40d34a719e55daba9e5ab9`, and
  both URL schemes — `com.googleusercontent.apps.…-4fn7sigv0…` and `stationly-staging`. The
  plist swap landed while Gradle was still linking, so xcodegen and the archive both picked it
  up and no third run was needed.
- **Signing is genuine App Store distribution**, on the exported `.ipa`:
  `Apple Distribution: Stationly Limited (7T7D5LLYSL)`, profile
  `iOS Team Store Provisioning Profile: com.stationly.mobile.staging`, `get-task-allow=false`.
  The App Group carried into the distribution profile exactly as §9b predicted.
- App and widget build numbers matched at 5, and the new bundle-id assertion passed.

**Distribution signing is CLOUD-MANAGED.** `security find-identity` still lists only the two
*Apple Development* certificates — no distribution cert exists locally and none is needed.
Xcode requested a cloud signing certificate from Apple during export. The §9b prediction that
the first archive would mint a local distribution cert was wrong, in a convenient direction:
there is no certificate to manage, renew, or lose.

The archive is Development-signed and `-exportArchive` re-signs it for distribution — normal,
and worth knowing before someone inspects the `.xcarchive` and thinks the export is broken.

### 9h. Open

1. ✅ **Sign-in re-tested and working** against a build carrying the new plist, confirmed by
   hand on the device. The §9d Firebase mismatch is fully closed.
2. ✅ **A guard for 9d now exists** — see §9l/L2. The archive verification asserts the bundled
   plist's `PROJECT_ID` against the one the environment names and its `BUNDLE_ID` against the
   app's, so the §9d mismatch cannot reach TestFlight. It is an upload-time rather than a
   compile-time check, which is the weaker of the two placements but catches the case that
   matters; a pre-build assertion would still be a worthwhile addition.
3. `stream:EXCEPTION JobCancellationException` — still uninvestigated (§8 item 3).
4. ✅ `ios-dev.sh` **now runs end to end** (after the bash-3.2 fix in §9f) — regenerate,
   resolve, discover via `devicectl`, build, install. The corrected staging build is on the
   iPhone 11, verified to carry `BUNDLE_ID = com.stationly.mobile.staging`, the new
   `GOOGLE_APP_ID …40d34a719e55daba9e5ab9` and the new
   `com.googleusercontent.apps.…-4fn7sigv0…` scheme. The §9d mismatch is gone from the
   installed binary; only the human sign-in test remains (item 1).
5. **Disk, still reclaimable:** stale Kotlin/Native toolchains 1.9.20 / 2.0.0 / 2.1.21
   (**3.8 GB**, project uses 2.2.0), old Gradle distributions (~300 MB), `iosApp/build/DD`
   (6.5 GB warm debug cache), `iOS DeviceSupport` (5.3 GB). Note the release archive added
   `iosApp/build/DD-release` and `build/archives`, so the tree is larger than after §9g.

### 9k. Uploaded — build 7 is in TestFlight

```bash
source ~/.appstoreconnect/env.sh
scripts/ios-testflight.sh staging --build 7
```

```
UPLOAD SUCCEEDED with no errors
Delivery UUID: d469be84-c9af-460e-888a-d7dc79db1748
```

Confirmed through the ASC API: **build 7, `processingState=VALID`, minOS 16.0, expires
2026-11-13**, `internalBuildState=IN_BETA_TESTING`,
`externalBuildState=WAITING_FOR_BETA_REVIEW`.

Both groups exist and have build 7 attached — `Staging Users` (internal) and
`Staging Users(Ext)` (external, public link
the URL is in App Store Connect and deliberately not recorded here — this repo is
public, and the link is an open invitation to the beta). Beta App Review details are filled in, demo
account included, and the submission is queued at `WAITING_FOR_REVIEW`.

**The public link stays dormant until review approves it** — "Testers cannot join public link
until this group has an approved build" is the expected state, not a misconfiguration. Beta
review is per **version string**, so once `1.0` is approved every later build reaches external
testers without another review.

Builds 5 and 6 were never accepted: 5 was the dry run (exported locally, uploaded nothing) and
6 failed validation twice — see §9m.

---

## 9m. Getting the upload to actually land — two failures after the archive

The archive was correct on the first attempt and stayed correct. Everything below happened
*after* `ARCHIVE SUCCEEDED`, which is worth stating plainly: the release build was never the
problem, and neither failure would have been caught by any amount of local building.

### 9m-1. Cloud signing 403 — the API key cannot sign

The natural form of the upload step is one `xcodebuild -exportArchive` with
`destination: upload` and the ASC API key passed in. It fails:

```
error: exportArchive Cloud signing permission error
error: exportArchive No signing certificate "iOS Distribution" found
```

The real cause is only in the `.xcdistributionlogs`, not the terminal:

```
"status": "403", "code": "FORBIDDEN_ERROR",
"detail": "You haven't been given access to cloud-managed distribution certificates.
           Please contact your team's Account Holder or an Admin to give you access."
```

**Passing `-authenticationKey*` REPLACES the identity xcodebuild signs as.** Without it, it
signs as the Apple ID signed into Xcode — the Account Holder, who has cloud-signing rights.
With it, it authenticates as the API key, whose role is App Manager, and App Manager is not
granted access to cloud-managed distribution certificates.

That is exactly why `--dry-run` passed and the real upload did not: the dry run never passed
the key. A dry run that differs from the real run in *which credential it uses* is not a
rehearsal of the real run, and this is the part of the pipeline that difference hid.

**Fix — give each half to the identity that can do it:**

| Step | Tool | Identity | Requires |
|---|---|---|---|
| export + sign | `xcodebuild`, **no** API key | Xcode Apple ID | cloud signing |
| upload | `altool --upload-app`, **with** API key | API key | App Manager |

The alternative — granting the key Admin, or explicitly granting it access to cloud-managed
distribution certificates — is one checkbox in App Store Connect and also works. It was not
taken: it hands broad signing authority to a credential sitting in a file on disk, to save a
step that costs nothing.

Also added `--resume`, which reuses an existing `.xcarchive` and goes straight to verification
and export. The archive costs two full Kotlin/Native release links (~20 min) and is complete
and self-describing, so a failure in export or upload should never cost a rebuild. Every
step-3 verification still runs; resuming skips *producing* the archive, never *checking* it.

### 9m-2. Validation 90474 — no orientations declared

With signing fixed, altool transferred the build and Apple's server-side validation rejected
it:

```
90474: Invalid bundle. No orientations were specified in the com.stationly.mobile.staging
bundle. To support iPad multitasking, specify the … UISupportedInterfaceOrientations key.
```

`TARGETED_DEVICE_FAMILY` is `"1,2"` — iPhone **and** iPad. That is Xcode's default and was
never a deliberate choice, but it means the app must declare its orientations so the system
can size it for Split View.

The tempting fix is portrait-only, and it would have been a **silent product regression**. The
app genuinely rotates:

- `DreamHost.kt:172` — `isLandscape = maxWidth > maxHeight`, driving a 30:70 landscape split
- `DreamClock.kt:266` — "56sp floor keeps phone landscape readable"
- Android declares no `screenOrientation`, so free rotation is the parity behaviour

All four orientations are declared for both idioms, which is also what Apple's remediation text
asks for. `UIInterfaceOrientationPortraitUpsideDown` is included because Apple lists it;
Face ID iPhones ignore it regardless, so it only affects iPad.

**Consequence to remember for production:** because the app declares iPad support, App Review
will test the production build **on an iPad**. If the layout is not iPad-ready, setting
`TARGETED_DEVICE_FAMILY = 1` before that submission is the cheap way out — and it must be done
before submission, not after a rejection.

### 9m-3. What this cost, and what it did not

A Kotlin change costs the full ~25 minutes (two release links). Everything else — Info.plist,
entitlements, icons, build number — costs about five, because Gradle correctly reports
up-to-date and skips both links. Build 7 went from `--build 7` to `UPLOAD SUCCEEDED` in under
four minutes for exactly that reason.

---

## 9l. Staff review of the separation — findings and fixes

A full read of the separation surface (project.yml, all four xcconfigs, both Info.plists, both
entitlements, the Kotlin platform layer, every Swift reader, both scripts) against one
question: **can staging and production be confused?** Eleven findings, all fixed.

### The three that failed toward production

**L1 — `getEnvironment()` defaulted to PRODUCTION on any read failure.** The single most
dangerous line in the split:

```kotlin
return if (env == "staging") AppEnvironment.STAGING else AppEnvironment.PRODUCTION
```

Anything but the exact string `"staging"` — missing key, empty expansion, an xcconfig that did
not apply, a typo — produced PRODUCTION, and `AppConfig` turns that straight into
`api.stationly.co.uk`. A *staging* build with one broken plist key would have talked to the
production backend, silently, holding a staging Firebase identity.

It was also the **only** reader that degraded quietly. `IosAppGroup.ID`, both
`AppGroupID.value`s and `DepartureEntry.scheme` all trap. Now matched exhaustively against
both known values, with any third value an error — a build that cannot say which backend it is
for does not run. `IOS_API_KEY`'s silent `?: ""` fallback was given the same treatment.

**L2 — the archive check never verified WHICH Firebase project shipped.** It grepped for the
presence of `GOOGLE_APP_ID`, which only proves the plist is not the placeholder stub. **A
production archive carrying the staging config passed.** Now `PROJECT_ID` is compared against
the plist the environment actually names, and the plist's `BUNDLE_ID` against the app's — the
latter being exactly the §9d failure, now caught before upload rather than by a tester.
`StationlyEnvironment` and `StationlyAppGroup` are asserted out of the built binary too.

**L3 — the "debug Kotlin binary" check did not exist.** The comment above the verification
block claimed it; nothing implemented it. The first replacement attempt was worse: looking for
`__DWARF` sections in the framework. Measured on both variants of this project, that finds
**zero in each** — Kotlin/Native emits debug info to a separate `.dSYM` — so it was a check
that could never fire, which is the same failure with more code. It now asserts the build
phase's own reported decision out of the full archive log, and the phase hard-fails on the
mismatch itself (L4), giving two independent guards.

### The traps that would have reintroduced the bug

**L4 — `STATIONLY_KOTLIN_BUILD` is an unmarked exception to the documented precedence rule.**
`Base.xcconfig` says settings defined there must not also appear in project.yml's `settings:`
blocks. This one does, and must: the override is what makes Release archives link release
Kotlin. It works because the real ladder is finer than the rule states —

```
target settings     ← beats everything (why STATIONLY_ENVIRONMENT must stay out)
target xcconfig     ← none in this project
project settings    ← STATIONLY_KOTLIN_BUILD: release lives here, and wins
project xcconfig    ← Config/*.xcconfig live here
```

— so someone applying the rule literally deletes the override and ships debug Kotlin with a
green build. The Select Kotlin XCFramework phase now refuses any `Release*` configuration
whose `STATIONLY_KOTLIN_BUILD` is not `release`, and says why in the error.

**L5 — Xcode's default scheme is `iosApp Production`.** `xcuserdata/` is git-ignored, so a
fresh clone or a new machine has no stored scheme selection and Xcode takes the first
alphabetically: `Production` sorts before `Staging`. **The first ⌘R on a new checkout builds
Debug Production** — production bundle id, production backend, production icon and name. And
because that bundle id is the App Store one, installing it *overwrites a real production
install* on the device. The placeholder Firebase config is an accidental safety net today and
disappears the moment Task D lands.

A new `Guard Accidental Production Build` phase refuses `Debug Production` unless
`iosApp/Config/.allow-debug-production` exists (git-ignored — committing it would disable the
guard for everyone), and names the likely cause in the error. `Release Production` only warns:
archiving is already deliberate, `ios-testflight.sh` refuses production separately, and a hard
block there would obstruct the legitimate path the day production goes live. All four cases
were exercised before the build that shipped.

**L6 — five generated files are committed and only one was documented as generated.** XcodeGen
writes `project.pbxproj`, **both** `Info.plist`s and **both** `.entitlements` files, from the
`info.properties` / `entitlements.properties` blocks. A hand edit to any of them survives until
the next build — every script runs xcodegen first. The proof is in this session's own diff: a
comment block explaining the WidgetKit push entitlement vanished when that target's
entitlements moved into project.yml. project.yml now opens with the list.

### The rest

| | |
|---|---|
| **L7** | `IOS_API_KEY` fell back to `""` — now trapped with L1 |
| **L8** | A stray `GoogleService-Info.plist` under `iosApp/` would beat the pre-build copy: Copy Bundle Resources runs *after* preBuildScripts. Verified absent today; L2's `PROJECT_ID` assertion now catches it, and the error names this cause |
| **L9** | The `current` symlink is shared mutable state — two concurrent builds of different configurations race and the loser links the wrong Kotlin. Recorded in the phase, not solved: the real fix needs per-configuration framework paths, which XcodeGen cannot express (Xcode does not expand build settings inside a file reference, which is why it is a symlink at all) |
| **L10** | `xcodebuild … \| grep … \|\| true` discarded the archive exit status; only the directory check stood between a failed archive and an upload. Status captured explicitly, full log kept at `iosApp/build/archive-<env>-<n>.log` — the grep had been hiding every failure reason |
| **L11** | The two `exportOptions-*.plist` are content-identical. Correct, not an oversight: with `signingStyle: automatic` the profile resolves from the archive's bundle id, so the separation is already carried there. Documented in the file so it is not "tidied" into one |

### What the review did not find

Worth recording, because it is most of the surface. The BGTask identifier composition —
suffix in the middle, runtime value built from `Bundle.main` so plist and code agree by
construction — is right. The widget reading its own Info.plist rather than the app's is a
subtlety commonly got wrong. And `WidgetRefreshService.swift:238` guards
`!baseUrl.isEmpty` and does nothing rather than defaulting, which is L1 done correctly in the
same codebase — the reason L1 reads as an oversight rather than a decision.
