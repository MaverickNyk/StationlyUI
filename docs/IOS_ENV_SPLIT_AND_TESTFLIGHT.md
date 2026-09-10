# iOS — Staging/Production split & TestFlight

**Status (2026-08-16):** Workstream A (§3) **done and running on device** — sign-in verified
against the staging Firebase registration. Workstream B (§4, TestFlight) **done for staging**:
build 7 is uploaded, `VALID`, live on the internal track, and submitted for Beta App Review;
the external group's public link is minted and waits on approval. **Production remains
placeholders by choice** — see §6.1, §6.5, and the `--env production` guard in
`scripts/ios-testflight.sh`, which is deliberate.
**Branch:** `ios-parity`, uncommitted.

**Read alongside:** `IOS_OWNER_SETUP_CHECKLIST.md` (the console runbook and its live status)
and `SESSION_2026-08-15_IOS_ENV_SPLIT.md` (what was built, what broke, and why — §9l is the
staff review of the separation, §9m the two post-archive upload failures).

> ## ✅ Implemented — the environment split
>
> All per-environment config now lives in `iosApp/Config/`, one file per environment:
> `Base.xcconfig` (shared), `Staging.xcconfig`, `Production.xcconfig`, plus a git-ignored
> `Secrets.xcconfig` (template: `Secrets.example.xcconfig`). `project.yml` only *references*
> these as `$(NAME)` — it must never assign them, or the target setting silently beats the
> xcconfig, which is the bug that made `Release Production` unusable in the first place.
>
> Verified in the built `.app`, not just in build settings:
>
> | | Staging build | Production build |
> |---|---|---|
> | Bundle id | `com.stationly.mobile.staging` | `com.stationly.mobile` |
> | Widget | `…staging.StationlyWidget` | `…mobile.StationlyWidget` |
> | Display name | Stationly Staging | Stationly |
> | App Group | `group.com.stationly.staging` | `group.com.stationly.shared` |
> | URL scheme | `stationly-staging` | `stationly` |
> | BGTask ids | `…staging.widgetrefresh` / `.activityupload` | unchanged |
> | Firebase | `mindthetimefcm` | ⚠️ placeholder stub |
> | App icon | grey field | white field |
>
> Both schemes build clean. Xcode auto-registered the new staging App ID and App Group with
> Apple during the signed build, so no console work was needed for staging.
>
> A production build compiles, signs and runs, but logs a loud build-time warning and skips
> `FirebaseApp.configure()` — so **sign-in is unavailable in production until §6.1–6.2 are
> done**. That is deliberate: a production build half-working against staging credentials is
> more dangerous than one that plainly cannot sign in.
>
> **Code changes beyond config:** the App Group identifier, URL scheme and API key are no
> longer literals anywhere. All four former copies of the App Group string
> (`iosApp/AppGroupID.swift`, `StationlyWidget/AppGroupID.swift`, `IosAppGroup.ID` in
> `core/iosMain`, and the entitlements) now resolve one `StationlyAppGroup` Info.plist key,
> which retires the "keep four copies in lockstep or silently open an empty suite" hazard
> those files each warned about. `IosAppGroup.ID` is consequently a `val`, not a `const val`.
> New `iosApp/BGTaskIdentifier.swift` composes the background-task ids from the bundle id so
> they cannot drift from `BGTaskSchedulerPermittedIdentifiers`.
>
> **Not yet done from §3:** nothing. **Not yet done at all:** everything in §4.
**Companions:** `docs/IOS_BUILD_AND_HANDOFF.md` (§2 build runbook), `iosApp/project.yml` (the only
place iOS build config lives today).

---

## 1. What is actually true today

The four configs exist and the scheme split works, but the *only* thing that differs between
staging and production on iOS is one string.

| Axis | Android | iOS today | Same? |
|---|---|---|---|
| API base URL | flavor → `AppConfig` | `STATIONLY_ENVIRONMENT` → Info.plist → `Platform.getEnvironment()` → `AppConfig` | ✅ works |
| Web URL | ✅ | ✅ same path | ✅ works |
| Firebase project | `staging/google-services.json` → `mindthetimefcm`, `prod/…` → `stationly-prod` | **one** `GoogleService-Info.plist`, project `mindthetimefcm` | ❌ **prod builds use the staging Firebase project** |
| Backend API key | per flavor from `local.properties` | one hardcoded `IOS_API_KEY` in `Platform.ios.kt:1082` | ❌ |
| Bundle / app id | `com.stationly.mobile` both | `com.stationly.mobile` both | ❌ blocks TestFlight (see §3) |
| App Group | n/a | `group.com.stationly.shared` both | ❌ would collide side-by-side |
| Display name | "Stationly Staging" vs "Stationly" | "Stationly" both | ❌ |
| Signing | prod has its own keystore | one team, automatic, no distinction | ⚠️ fine for iOS |
| APNs env | n/a | `Release *` → production, `Debug *` → development | ✅ correct |

**The headline defect:** `Release Production` / `Debug Production` today point the app at
`api.stationly.co.uk` while authenticating against the **staging** Firebase project. Every
production login would mint a `mindthetimefcm` uid and hand a staging ID token to the prod
backend. This has never been caught because no production build has ever been run.

**Secondary:** `stationly://` and the reversed-Google-client URL scheme are claimed by both
configs; with one bundle id only one app can exist, so it has not bitten yet — it will the moment
the ids diverge, and the Google scheme diverges naturally with the second Firebase project.

---

## 2. Target state

Two independently installable apps, side by side on the same phone, sharing zero state:

| | Staging | Production |
|---|---|---|
| Bundle id | `com.stationly.mobile.staging` | `com.stationly.mobile` |
| Widget bundle id | `com.stationly.mobile.staging.StationlyWidget` | `com.stationly.mobile.StationlyWidget` |
| Display name | **Stationly Staging** | Stationly |
| App icon | staging-tinted variant | production icon |
| App Group | `group.com.stationly.staging` | `group.com.stationly.shared` |
| Firebase | `mindthetimefcm` | `stationly-prod` |
| API | `staging-api.stationly.co.uk` | `api.stationly.co.uk` |
| API key | `staging.STATIONLY_API_KEY` | `prod.STATIONLY_API_KEY` |
| Deep-link scheme | `stationly-staging://` | `stationly://` |
| BGTask ids | `…staging.widgetrefresh` / `…staging.activityupload` | as today |
| ASC app record | separate record, TestFlight only, never submitted for sale | the real App Store record |
| APNs (Release) | production gateway, staging Firebase/backend | production gateway |

Rationale for the separate bundle id: App Store Connect keys everything — app record, TestFlight
tracks, push certificates, Sign in with Apple — off the bundle id. One bundle id means one app
record, which means staging builds and production builds compete for the same TestFlight build
numbers and the same testers, and a staging build sitting in the "latest build" slot is what
external testers get offered. Separate ids is the only arrangement that lets both run TestFlight
at once, and it is what makes side-by-side installs possible for the F&F testers.

---

## 3. Workstream A — make the environments genuinely separate

Ordered so each step is independently verifiable on device.

### A1. Per-config bundle identifiers (`project.yml`)
Add `PRODUCT_BUNDLE_IDENTIFIER` overrides under both targets' `configs:` blocks (staging gets the
`.staging` infix; the widget's id must remain `<app id>.StationlyWidget` or the extension will not
be accepted into the container). Do **not** use `PRODUCT_NAME` — the docs' pipeline depends on
`iosApp.app` paths.

*Verify:* `xcodegen generate`, build both schemes, both apps install and appear on the home screen
simultaneously.

### A2. Display name + icon
`CFBundleDisplayName`/`CFBundleName` become `$(STATIONLY_DISPLAY_NAME)`, set per config. Add a
second `.appiconset` (`AppIconStaging`) and drive `ASSETCATALOG_COMPILER_APPICON_NAME` per config —
the 1024 asset is a single file today, so this is one derived image, not an icon set.

### A3. Two `GoogleService-Info.plist` files
Rename to `GoogleService-Info-Staging.plist` and `GoogleService-Info-Production.plist`, put both in
the target's sources, and add a **pre-build** run script that copies the one matching
`$STATIONLY_ENVIRONMENT` to `${BUILT_PRODUCTS_DIR}/…/GoogleService-Info.plist`. This keeps
`AppDelegate.swift:142` (`Bundle.main.path(forResource:"GoogleService-Info")`) working untouched.

**Blocked on the owner:** a real iOS app must be registered in the `stationly-prod` Firebase project
for bundle id `com.stationly.mobile`, and the staging project needs a *second* iOS app registered
for `com.stationly.mobile.staging` (its current iOS app is registered as `com.stationly.mobile` —
that record should be re-pointed or duplicated). Download both plists.

*Verify:* boot each build, log `FirebaseApp.app()?.options.projectID` once — it must read
`stationly-prod` in the production build.

### A4. Google Sign-In URL scheme per config
The reversed client id is per Firebase-project, so the hardcoded
`com.googleusercontent.apps.48865967804-…` in `CFBundleURLSchemes` is staging's. Make it
`$(GOOGLE_REVERSED_CLIENT_ID)`, set per config from each plist. Add the two SHA-less iOS OAuth
clients in the respective Google Cloud consoles if missing.

*Verify:* Google sign-in completes in **both** builds. This is the step most likely to fail
silently — a wrong scheme means the browser returns and nothing happens.

### A5. Per-environment App Group
Introduce `STATIONLY_APP_GROUP` as a build setting, expand it into (a) both `application-groups`
entitlements and (b) a new Info.plist key `StationlyAppGroup` in both targets. Then replace the four
hardcoded literals — `iosApp/AppGroupID.swift`, `StationlyWidget/AppGroupID.swift`,
`IosAppGroup.ID` in `Platform.ios.kt`, and the composeApp copy — with a read of that Info.plist key.
This both fixes the collision *and* retires the "keep four copies in lockstep, a miss reads an empty
suite" hazard called out in `AppGroupID.swift`.

**Migration note:** existing testers' widget state lives in `group.com.stationly.shared`. Production
keeps that identifier precisely so nothing is orphaned; only staging moves.

*Verify:* both apps installed, configure a widget in each, confirm the boards differ.

### A6. Per-environment deep-link scheme and BGTask identifiers
`stationly://` → `$(STATIONLY_URL_SCHEME)`; the widget's `boardURL` and any `stationly://home?…`
literal must be built from the same value rather than a string constant.
`BGTaskSchedulerPermittedIdentifiers` must be suffixed per environment **and** kept in step with
`BackgroundRefreshScheduler.taskIdentifier` / `ActivityUploadScheduler.taskIdentifier` — a mismatch
is a launch-time crash, not a warning (already noted in `project.yml`).

*Verify:* both apps launch (proves the BGTask registration matches); a widget tap in each opens its
own app, not the other one.

### A7. Backend API key per environment
Mirror Android: read `staging.STATIONLY_API_KEY` / `prod.STATIONLY_API_KEY` out of
`local.properties` in the `composeApp` iOS framework Gradle config and generate them into the
framework, or expand them into the Info.plist as `$(STATIONLY_API_KEY)` from an `.xcconfig` that is
git-ignored. **Recommendation: the Info.plist/xcconfig route** — it keeps the secret out of the
Kotlin source that is currently committed, and `Platform.ios.kt` already reads Info.plist for the
environment so the pattern exists. Delete the hardcoded `IOS_API_KEY`.

> Note: an API key inside a shipped iOS binary is extractable regardless of route. This is about
> not committing prod credentials to git, not about secrecy on device.

### A8. Where the config lives
After A1–A7 there are ~8 per-config settings. Move them out of `project.yml`'s inline `configs:`
blocks into four `.xcconfig` files (`Config/Staging.xcconfig`, `Production.xcconfig`, a shared
`Base.xcconfig`, and a git-ignored `Secrets.xcconfig`) referenced from `project.yml`. One file per
environment, readable at a glance, which is the "separate and easy places" the brief asks for.

---

## 4. Workstream B — TestFlight

TestFlight has two tester classes, and the difference decides how much process this carries:

- **Internal testers** — up to 100 people, each must be a user on your App Store Connect team
  (role can be as low as "Customer Support"). Builds are available in minutes, **no Beta App
  Review**, no compliance questionnaire beyond export compliance.
- **External testers** — up to 10,000 by email or public link, **no ASC account needed**, but the
  first build of each new version goes through **Beta App Review** (usually 24–48h) and needs a
  Beta App Description, feedback email, and a filled-in test-information block.

**Recommendation for friends & family on staging: external group.** Adding a dozen friends as ASC
team users gives them access to your entire developer account UI and is the wrong blast radius. The
cost is one Beta App Review per version — and the staging app never gets submitted for sale, so
review is only ever the beta one. For the *production* app, keep the internal track for the team and
add an external "Early Access" group later.

### B1. Prerequisites still missing from the repo

| Gap | Why it blocks | Action |
|---|---|---|
| `PrivacyInfo.xcprivacy` absent in both targets | Apple **rejects** uploads without required-reason API declarations | Add manifests declaring `NSPrivacyAccessedAPICategoryUserDefaults` (reason `CA92.1` — App Group access) and `…FileTimestamp`/`DiskSpace` if used; declare data collected: account (email), identifiers (device id), location |
| Location purpose string exists ✅, no others audited | runtime crash on first use of an undeclared API | Audit for camera/photos/contacts — likely none |
| `ITSAppUsesNonExemptEncryption` not set | ASC asks the export-compliance question on **every** upload, blocking automation | Add `ITSAppUsesNonExemptEncryption: false` to both Info.plists (true only if you ship non-exempt crypto; HTTPS is exempt) |
| App icon set has only the 1024 slot | modern single-size sets are fine, but verify the alpha channel is stripped | Validate with a real archive before relying on it |
| `CURRENT_PROJECT_VERSION: "4"` hardcoded in two places | every upload must have a **unique, increasing** build number per version string; two hardcoded copies will drift, and a drift between app and extension is a hard validation failure | §B2 |
| No `exportOptions.plist`, no CI, no fastlane | every upload is a manual Xcode Organizer session | §B3 |
| Widget requires iOS 26.0 | testers below 26 get the app with **no widget** and no explanation | Accept, but say so in the TestFlight "What to Test" notes |
| Sign in with Apple entitlement present, provider possibly not enabled in `stationly-prod` | prod sign-in fails | Enable Apple provider in both Firebase projects' Authentication → Sign-in method |
| Push: APNs key uploaded per Firebase project? | prod pushes silently dead | Upload the team's APNs auth key (`.p8`, team `7T7D5LLYSL`) to **both** Firebase projects |

### B2. Versioning
Make the build number a single source of truth: define `CURRENT_PROJECT_VERSION` once in
`Base.xcconfig` so both targets inherit it (they currently declare it separately, which is exactly
the drift the widget's own comment warns about). Bump it with `agvtool next-version -all` or a CI
step; `MARKETING_VERSION` stays `1.0` until there is a user-facing release.

### B3. Upload pipeline
1. Create an **App Store Connect API key** (Integrations → Keys, role *App Manager*). Store the
   `.p8`, key id and issuer id outside the repo.
2. Add `Config/exportOptions-<env>.plist` with `method: app-store-connect` and
   `destination: upload`.
3. A `scripts/ios-testflight.sh` doing: build the Kotlin framework **first**
   (`./gradlew :composeApp:assembleComposeAppReleaseXCFramework` — a green `xcodebuild` without this
   ships stale Kotlin), then `xcodebuild archive` with the chosen scheme, then
   `xcodebuild -exportArchive` with the API key. Two arguments: environment and build number.

   > ⚠️ **Corrected in practice (2026-08-16): do NOT pass the API key to `-exportArchive`.**
   > Doing so replaces the identity xcodebuild signs as — instead of the Apple ID signed into
   > Xcode (the Account Holder), it authenticates as the key, whose App Manager role has no
   > access to cloud-managed distribution certificates. The result is a 403
   > `FORBIDDEN_ERROR` surfacing as the misleading "No signing certificate iOS Distribution
   > found". Export **without** the key, then upload the `.ipa` with
   > `xcrun altool --upload-app --apiKey … --apiIssuer …`, which needs only App Manager. See
   > `SESSION_2026-08-15_IOS_ENV_SPLIT.md` §9m-1.
   >
   > The environment is also a required argument, not a default — `staging` or `production`
   > as the first positional. And `--resume` reuses an existing archive so an export or
   > upload failure never costs the ~20-minute Kotlin relink.
4. Later, move that script into a manually-dispatched GitHub Actions workflow (there is already a
   `.github/workflows/` with `deploy-prod.yml` to mirror) on a macOS runner, with the `.p8` and the
   git-ignored `Secrets.xcconfig` values as repository secrets.

**Note on the release archive:** the current `project.yml` only wires the *debug* XCFramework
(`XCFrameworks/debug/composeApp.xcframework`). A Release archive must consume the release
XCFramework — make the `FRAMEWORK_SEARCH_PATHS`/dependency path config-dependent, or every
TestFlight build ships a debug Kotlin binary. **This is the single most likely thing to be
discovered late.**

### B4. Two App Store Connect records
Create `com.stationly.mobile.staging` as a new app record named "Stationly Staging". It never needs
App Store metadata beyond what Beta App Review asks for. Keep the production record's TestFlight
internal group for the team from day one so the same pipeline is exercised on both.

### B5. Tester onboarding
One external group per app ("Friends & Family" on staging, "Early Access" on production), a public
link for staging so you can invite without collecting emails, and a standing "What to Test" note
covering: sign in, add a board, widget add/configure (iOS 26+ only), and how to send feedback
(screenshot → TestFlight feedback goes to ASC automatically).

---

## 5. Sequencing

**Phase 1 — correctness (blocks everything):** A1, A3, A5, A7. After this a production build is no
longer pointed at the staging Firebase project and the two apps cannot corrupt each other. Owner
work in Firebase consoles (A3) is on the critical path — start it first.

**Phase 2 — distinguishable builds:** A2, A4, A6, A8.

**Phase 3 — shippable:** B1 (privacy manifest, export compliance), B2, and the release-XCFramework
fix in B3. Verify a manual Organizer upload of the **staging** app reaches TestFlight.

**Phase 4 — F&F:** B4, B5, first Beta App Review, invite testers.

**Phase 5 — repeatable:** B3 script + CI workflow, then run the same pipeline against production
with an internal-only track.

## 6. Owner-side actions, collected

> **Superseded by `docs/IOS_OWNER_SETUP_CHECKLIST.md`**, which has the step-by-step console
> instructions and current status. Kept here for the overview; statuses below are as of
> 2026-08-15.

These cannot be done from the repo:

1. ⬜ Firebase `stationly-prod`: register iOS app `com.stationly.mobile`, enable Google + Apple
   sign-in providers, upload the APNs `.p8` key for team `7T7D5LLYSL`.
2. ✅ **Firebase `mindthetimefcm`: iOS app re-registered under the split bundle id** (done
   2026-08-15, after the split broke it).
   *What broke:* the project's only iOS app was registered as `com.stationly.mobile`, which is
   what the old `GoogleService-Info-Staging.plist` declared — but the staging app now runs as
   `com.stationly.mobile.staging`. Sign in with Apple failed as a result: Apple mints an
   identity token whose `aud` is the **running** bundle id, Firebase checks that against the
   bundle ids registered in the project, finds no match, and rejects the credential. Google
   sign-in was broken the same way, iOS OAuth clients being bundle-id-bound.
   *The fix:* a new iOS app for `com.stationly.mobile.staging`; Firebase cannot rename a
   bundle id, so a new registration is the only route. Its plist and its **new**
   `REVERSED_CLIENT_ID` are both committed — they move together, because the OAuth client is
   minted per registered app, not per project.
   *Note for later:* the leftover **iOS** `com.stationly.mobile` entry in this project can be
   deleted; nothing points at it, as production iOS authenticates against `stationly-prod`.
   The **Android** `com.stationly.mobile` entry in the same project must stay — the Android
   staging flavor uses it, Android keeping one applicationId across both flavors.
3. ✅ **Apple Developer: already done, do not redo.** Xcode's automatic signing registered all
   four App IDs (`com.stationly.mobile[.staging]` and their `.StationlyWidget` extensions) and
   created both App Groups during the 2026-08-15 session. Confirmed by decoding the minted
   provisioning profiles: the staging profile carries `group.com.stationly.staging`, the
   production one `group.com.stationly.shared`, plus `applesignin` and `aps-environment`.
   The App Group capability lives on the **App ID**, not the profile, so the App Store
   distribution profile inherits it — the switch to release signing does not re-open this.
4. ✅ App Store Connect: **both** records exist — "Stationly Staging"
   (`com.stationly.mobile.staging`, appId 6801918900) and "Stationly: Live Tfl Departures"
   (`com.stationly.mobile`, appId 6799715716). Upload API key `XM65K63C56` created and
   verified against the live API.
5. ⬜ Provide `prod.STATIONLY_API_KEY` for `Secrets.xcconfig`.

The one signing gap left is that the keychain holds only **Apple Development** certificates —
no distribution cert. `xcodebuild -allowProvisioningUpdates` creates one on the first archive
using the Apple ID signed into Xcode, which already has the rights (it created the App IDs).
