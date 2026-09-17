# iOS — production release, v1.0

**Status (2026-09-10): SUBMITTED.** Version 1.0 is `WAITING_FOR_REVIEW` with **build 2**,
release type **MANUAL**. Production APNs is configured and verified. This is the first time
this app has been submitted to the App Store.

Companion to `IOS_OWNER_SETUP_CHECKLIST.md` (console work) and
`IOS_ENV_SPLIT_AND_TESTFLIGHT.md` (the plan). This file is what actually happened, including
the parts that went wrong, because every one of them cost time that a note would have saved.

---

## 1. What shipped

| | build 1 | build 2 |
|---|---|---|
| Uploaded | 2026-09-10 02:39 PT | 2026-09-10 03:32 PT |
| `UIDeviceFamily` | `[1, 2]` — iPhone **and iPad** | `[1]` — iPhone only |
| State | VALID, superseded | VALID, **attached to 1.0** |

App record `6799715716`, `com.stationly.mobile`, "Stationly: Live Tfl Departures".
Marketing version 1.0, `MARKETING_VERSION` in `Base.xcconfig`.

**MANUAL release: approval does NOT put the app on sale.** It sits in Pending Developer
Release until someone presses Release.

### Why build 2 exists

`TARGETED_DEVICE_FAMILY` was never set, so it took Xcode's `"1,2"` default and build 1
claimed iPad support nobody had chosen. App Store Connect surfaced it as *"You must upload a
screenshot for 13-inch iPad displays"*, which undersells it: a declared device is a device
**App Review runs the app on**, and this project has never been opened on an iPad — every
test on record is the iPhone 11, and the shared Compose UI is laid out for a phone.

Pinned to `"1"` on **both** the app and the widget target. An extension whose device family
disagrees with its container fails validation at upload rather than warning.

---

## 2. The release pipeline

`scripts/ios-testflight.sh <staging|production> --build <n> [--dry-run] [--resume]`

Production used to be refused outright by this script. That guard was correct when written —
the production Firebase plist was a stub with no `GOOGLE_APP_ID` — and became wrong on
2026-09-09 when `stationly-prod` was registered for `com.stationly.mobile`. It was replaced by
checks that read the artefact, because a blanket refusal cannot tell a fixed environment from
a broken one; it goes stale in silence and is then deleted wholesale.

### What step 3 asserts on the ARCHIVE

bundle id · `StationlyEnvironment` · App Group · Firebase `PROJECT_ID` · Firebase `BUNDLE_ID` ·
**Google redirect scheme vs the plist's `REVERSED_CLIENT_ID`** · release Kotlin framework ·
privacy manifest present.

### What step 4a asserts on the EXPORTED .ipa

`aps-environment=production` · `get-task-allow` not true · signer is an Apple Distribution
identity.

**These two live in different places for a reason.** Automatic signing archives with whatever
profile is already on the machine — an iOS Team Provisioning Profile — so *every* `.xcarchive`
here carries `aps-environment=development` and `get-task-allow=true`, whatever
`APS_ENVIRONMENT` said. **Export** is what re-signs with the distribution identity. Asserting
the entitlement on the archive would fail on every correct build.

There is no missing distribution certificate: automatic signing mints one at export time. It
does not appear in `security find-identity -p codesigning`, which is misleading — check an
exported `.ipa` instead.

---

## 3. App Store Connect

Set through the API (key `XM65K63C56`, **App Manager** role): subtitle, reviewer notes, build
attachment.

Set in the console by the owner: description, keywords, screenshots (6.7" + 6.5"), promo text,
support and marketing URLs, demo account, contact details, age rating, content rights, privacy
policy URL, primary category, App Privacy.

### Two fields the API cannot touch

- **Primary category** — `PATCH appInfos/{id}` returns **200 and silently discards** the
  relationship, and after being set in the console it still **reads** as null. The API is
  unreliable for this field in both directions. Ignore what it reports; trust the console.
- **App Privacy** — requires an **Admin** role. An App Manager key cannot write it.

### Demo account

`stationlytest@gmail.com`, an **email/password** account on `stationly-prod`. Verified by
signing in through Identity Toolkit: `aud: stationly-prod`, `sign_in_provider: password`.

Two things that make an account wrong here, both of which look fine until review fails:
- **A Google or Apple account has no password to hand over.** Only email/password works.
- **A staging account does not exist in production.** Android *prod* and iOS production share
  `stationly-prod`, so a Play Console demo account is valid; a `mindthetimefcm` one is not.

---

## 4. Production APNs

**Was broken on the day of submission. Fixed 2026-09-10.**

`/home/ubuntu/config/apns-auth-key.p8` did not exist on the production server, so
`ApnsService.isConfigured()` was false and every push failed as `ApnsNotConfigured` before
reaching Apple.

**No deploy script ships that file, for either environment**, and that is deliberate — the
deploy's assembly loop echoes every key it writes, so key material must never travel through
it. `FIREBASE_KEY_PATH` follows the same convention. The consequence is a manual step, and
until this document it was written down nowhere. Staging got it in August; production never
did, and nothing noticed because until 2026-09-10 no production iOS build existed.

### One key serves everything

An APNs auth key is issued **per team** (`7T7D5LLYSL`), not per app or environment. The same
`AuthKey_FCDBFFZUBW.p8` is byte-identical on staging and production. Apple allows exactly one
download, ever.

What varies per environment is **not** the key:
- **topic** — `ApnsService.bundleId()` derives it from `APP_ENV`
  (`com.stationly.mobile` / `…​.staging`). Wrong topic reads as nothing happening at all.
- **gateway** — `api.push.apple.com` vs `api.sandbox.push.apple.com`, chosen **per device**
  from the `environment` on its own record. Debug builds mint sandbox tokens, Release builds
  production ones, and the two are not interchangeable.

### The one-call health check

```
GET /api/v1/admin/device-push/status
Authorization: Bearer $STATIONLY_ADMIN_KEY
```

Run it from the box against `localhost:3000` to skip Cloudflare Access. The header is
`Authorization: Bearer` — the `X-Stationly-Admin-Key` comment in `adminAuthMiddleware.ts` is
stale.

Read the response in this order:

| Field | Want | Wrong means |
|---|---|---|
| `apnsConfigured` | `true` | the `.p8` is missing or unreadable |
| `bundleId` | `com.stationly.mobile` | **this is the `APP_ENV` check** |
| `withAppToken` | ≥ 1 | no device registered |
| `sandbox` | `0` | tokens are sandbox; the prod gateway will reject them |

**This belongs in the release routine.** It needs no phone and no build, and it would have
caught the missing key months earlier.

### Restoring the key

`scp` it to `APNS_P8_PATH`, `chmod 600`, then **`pm2 reload stationly-backend --update-env`** —
`privateKey()` caches after first read, so a reload is mandatory.

Verified working 2026-09-10: `delivered: 3, failed: 0`.

### Still open: widget tokens

`withWidgetToken: 0`, so every push goes out as a background app push. Widget push *is*
implemented (`StationlyWidgetBundle` registers the `.pushHandler` variant, iOS 26+). The flow
is WidgetKit issues a token → `StationlyWidgetPushHandler` writes it to the App Group → the app
uploads it on next launch. Untested rather than known-broken: add the widget from a production
build on iOS 26, open the app, then re-check the count.

---

## 5. Traps hit, so they are not hit twice

**`altool` can succeed and leave no trace.** Build 2 uploaded, the process was killed before
the script's success echo, and the `builds` endpoint had not indexed it yet — so it looked
like nothing had happened. Re-uploading is what proved otherwise, by being refused with
`previousBundleVersion: 2`. **Check `preReleaseVersions` before concluding an upload failed.**

**`ageRatingDeclaration` hangs off `appInfos`, not `appStoreVersions`.** Querying the version
reports it missing when it is set.

**`grep -m1` in a pipeline is fatal under `set -euo pipefail`.** It closes the pipe on the
first match, the producer dies of SIGPIPE, `pipefail` promotes 141 and `set -e` ends the run —
silently, and with a 0 exit code if the caller pipes the script anywhere. Capture first, match
afterwards, and prefer `awk` over `head -1`. Verifying a check in a shell *without* those
options proves nothing about how it behaves in this script.

**`master` only accepts PRs from `dev_*`, `feature/*`, `fix/*`, `hotfix/*`**, and
`enforce_admins` is on, so nobody can override it. GitHub will not let you change a PR's head
branch, so a wrongly-named branch means: rename, push, open a new PR, close the old one.

---

## 6. Open items

- [ ] Widget token: add the widget on a production build, open the app, re-check
      `withWidgetToken`
- [ ] Press **Release** after approval — MANUAL release does not publish itself
- [ ] `docs/IOS_OWNER_SETUP_CHECKLIST.md` Task D describes production as placeholders; it is
      now shipped
- [ ] Android stays **frozen**. The schema on `master` moved +247/−16 with no migrations, so
      an Android release cut from `master` today breaks existing users' databases.
      `dev_android_bring_to_v2` reinstates them.
