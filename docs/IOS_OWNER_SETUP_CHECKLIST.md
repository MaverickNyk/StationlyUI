# iOS — owner-side setup checklist

Everything here is console work that cannot be done from the repo. Companion to
`docs/IOS_ENV_SPLIT_AND_TESTFLIGHT.md` (the plan) and
`docs/SESSION_2026-08-15_IOS_ENV_SPLIT.md` (what was built).

**Tasks A–C unblock the friends & family staging TestFlight. Task D is production and
blocks nothing.** Apple Developer portal work — App IDs, App Groups, capabilities — is
already done: Xcode's automatic signing registered all four App IDs and both App Groups
during the 2026-08-15 session. Verified by decoding the provisioning profiles. Do not
redo it.

---

## Task A — create the staging app record in App Store Connect

**Blocks:** everything. Nothing can be uploaded until this record exists.
**Where:** <https://appstoreconnect.apple.com> → **Apps** → **+** → **New App**

| Field | Value |
|---|---|
| Platforms | iOS |
| Name | `Stationly Staging` |
| Primary language | English (U.K.) |
| Bundle ID | pick `com.stationly.mobile.staging` from the dropdown |
| SKU | `stationly-staging` (any unique string; internal only, never shown to users) |
| User Access | Full Access |

Two things to know:

- **The bundle id appears in the dropdown only because the App ID is already registered.**
  If `com.stationly.mobile.staging` is missing, the list is stale — reload the page. Do not
  create the App ID by hand; it exists.
- **App names are unique across the entire App Store, even for apps never submitted for
  sale.** If `Stationly Staging` is taken, use `Stationly Staging <something>` and tell the
  dev side, because nothing in the repo depends on the name — only the bundle id.

Then stop. Do **not** fill in the App Store tab (screenshots, description, pricing). This
record exists only to carry TestFlight builds and is never submitted for review as an app.

> **Status 2026-08-15: DONE and verified via the API.**
> `com.stationly.mobile.staging` — "Stationly Staging", SKU `stationly-staging`, en-GB,
> appId **6801918900**.
>
> The **production** record also already exists: `com.stationly.mobile` — "Stationly: Live
> Tfl Departures", appId **6799715716**. Both sit at version 1.0,
> `PREPARE_FOR_SUBMISSION`, with zero builds uploaded. So Task D step 7 is already done.

---

## Task B — create an App Store Connect API key

**Blocks:** the upload step of `scripts/ios-testflight.sh`.
**Where:** App Store Connect → **Users and Access** → **Integrations** → **App Store
Connect API** → **Team Keys**

1. Click **+**. Name it `TestFlight Upload`. Access role: **App Manager**.
2. Generate, then **download the `.p8` immediately** — Apple allows exactly one download,
   forever. Losing it means revoking the key and starting over.
3. Copy the **Key ID** (10 chars) and the **Issuer ID** (a UUID, shown at the top of the
   page — it is the same for every key on the team).

Only the **Account Holder** can create Team Keys. If the Integrations tab shows a "Request
Access" prompt instead of the key list, that is the account-holder gate.

Install it:

```bash
mkdir -p ~/.appstoreconnect/private_keys
mv ~/Downloads/AuthKey_XXXXXXXXXX.p8 ~/.appstoreconnect/private_keys/
chmod 600 ~/.appstoreconnect/private_keys/AuthKey_XXXXXXXXXX.p8
```

Then export the three variables the script reads. They live in
`~/.appstoreconnect/env.sh` (mode 600, outside the repo — this key can upload builds to
the account), so a shell that needs them does:

```bash
source ~/.appstoreconnect/env.sh
```

Exporting them by hand in one terminal tab is not enough: they vanish with that shell, and
`ios-testflight.sh` then fails on a missing credential somewhere unrelated-looking.

> **Status 2026-08-15: DONE and verified.** The key authenticates against the live API
> (`GET /v1/apps` → 200) and has since uploaded build 7. Its id, issuer id and `.p8` path
> live in `~/.appstoreconnect/env.sh` — deliberately not recorded here, so the repo carries
> no App Store Connect account identifiers.

---

## Task B2 — ✅ register the staging bundle id in Firebase

> **DONE 2026-08-15.** New iOS app registered in `mindthetimefcm` for
> `com.stationly.mobile.staging` (`GOOGLE_APP_ID 1:48865967804:ios:40d34a719e55daba9e5ab9`).
> Its plist is committed as `iosApp/Config/GoogleService-Info-Staging.plist` and the new
> `REVERSED_CLIENT_ID` (`…-4fn7sigv0dabkv8onghcj1k595ne8hlc`, was `…-g7alcuuk9ld0…`) is in
> `Config/Staging.xcconfig`. The two are cross-checked as equal.
>
> **Still to verify on device:** Apple and Google sign-in against a build carrying the new
> plist. The fix is mechanically correct but has not been exercised by a human yet.
>
> Optional cleanup still open: delete the now-unused **iOS** app `com.stationly.mobile`
> from `mindthetimefcm` (step 5 below). Harmless to leave.

**The problem this solved — discovered 2026-08-15 on device: Sign in with Apple was broken
in the staging build.**

The staging Firebase project `mindthetimefcm` has exactly one iOS app, registered as
`com.stationly.mobile`. The staging app now runs as `com.stationly.mobile.staging`. Apple
mints an identity token whose audience is the *running* bundle id; Firebase checks it
against the bundle ids registered in the project, finds none, and rejects the sign-in.
Email/password still works (no bundle id in that flow), which is a usable stopgap and the
right choice for the Beta App Review demo account.

Uploading a build before fixing this gives testers an app they largely cannot sign in to.

**Where:** Firebase console → project `mindthetimefcm` → ⚙ Project settings → Your apps →
**Add app** → iOS

1. Bundle ID `com.stationly.mobile.staging`, nickname `Stationly Staging`.
   Firebase **cannot rename** the bundle id of an existing app — it is immutable — so
   correcting the registration means adding a new app, not editing the old one.
2. Download the new `GoogleService-Info.plist`.
3. Skip the rest of the console's "add the SDK" wizard — the app is already wired up.
4. Hand the file over; it replaces `iosApp/Config/GoogleService-Info-Staging.plist`, and its
   `REVERSED_CLIENT_ID` goes into `GOOGLE_REVERSED_CLIENT_ID` in `Config/Staging.xcconfig`.
5. Optional cleanup: delete the now-unused **iOS** app `com.stationly.mobile` from this
   project. Nothing points at it any more — production iOS authenticates against
   `stationly-prod`, a different project — and removing it closes the one path by which a
   build carrying the production bundle id could still mint a staging uid, which is the
   whole failure the environment split existed to prevent.

> ⚠️ **Do not delete the ANDROID app `com.stationly.mobile` from this project.** It is live:
> the Android staging flavor uses it. Android keeps one applicationId across both flavors
> and separates environments by Firebase project alone, so that entry is load-bearing. Both
> apps appear in the same "Your apps" list under the same name, distinguished only by their
> platform icon.

Step 4 is not optional bookkeeping: the new registration mints its **own** Google OAuth
client, so the Google Sign-In redirect scheme changes with it. Leaving the old value gives
the silent failure mode — the browser sheet opens, the user authenticates, and nothing
happens on return.

While you are in that project, confirm **Authentication → Sign-in method** still lists
Google and Apple as enabled; they are per-project, not per-app, so they should be untouched.

---

## Task C — testers

> **Status 2026-08-16: build 7 uploaded, both groups created, submitted for Beta App Review.**
>
> | | |
> |---|---|
> | Build 7 | `VALID`, minOS 16.0, expires 2026-11-13 |
> | Internal | `Staging Users` — `IN_BETA_TESTING`, installable now |
> | External | `Staging Users(Ext)` — public link enabled (URL in App Store Connect; **not recorded here**, this repo is public and the link is an open invitation to the beta) |
> | Review | `WAITING_FOR_REVIEW` |
> | Demo account, contact, description, feedback email | all filled in |
>
> **Remaining: wait for approval (~24–48h).** "Testers cannot join public link until this
> group has an approved build" is the expected state until then, not a misconfiguration —
> the link is minted but dormant. Do not share it yet.
>
> Beta review is per **version string**, so once `1.0` is approved, every later build reaches
> external testers within minutes of upload, with no further review.

Do this **after** the first build has been uploaded and finished processing (10–30 min
after upload; you get an email).

### C1. Test it yourself first, via the internal track

You are already a user on the team, so internal testing needs **no Beta App Review** and
the build is available within minutes. This proves the whole pipeline before you spend a
review cycle on it.

TestFlight tab → **Internal Testing** → **+** next to Testers → create a group (`Team`) →
add yourself → the processed build appears immediately. Install via the TestFlight app on
the iPhone 11.

### C2. Then the external group for friends & family

TestFlight tab → **External Testing** → **+** → group name `Friends & Family`.

Add testers by email, or turn on the **Public Link** so you can invite by sharing a URL
without collecting addresses first. Recommended for F&F — no ASC accounts, no email
wrangling, and you can cap the number of redemptions.

The first build of each new version goes to **Beta App Review** (typically 24–48h). It
asks for:

| Field | What to put |
|---|---|
| Beta App Description | What the app does, one paragraph |
| Feedback email | Yours |
| Contact info | First name, last name, email, phone — a real reachable number |
| **Sign-in required** | **Yes — and you must supply a working demo account** |
| What to Test | see below |

**The demo account is the most common cause of a beta rejection.** Stationly requires
sign-in, so create a real account on the **staging** backend and hand the reviewer those
credentials. Without them the reviewer opens the app, hits the sign-in wall, and rejects.

Suggested "What to Test" note:

```
- Sign in (Google, Apple, or email)
- Search for a station and add it as a board
- Check the live departure times against the real board / TfL app
- Add the home-screen widget and configure it to a station
  (widget requires iOS 26 or later — on older iOS the app works but there is no widget)
- Screenshot anything odd; TestFlight feedback comes straight to us
```

Export compliance is already answered in the Info.plist
(`ITSAppUsesNonExemptEncryption: false`), so uploads will not stall waiting for you to
click through the encryption question.

---

## Task D — production Firebase (later; blocks nothing above)

Production is deliberately placeholders. A production build compiles and runs but skips
`FirebaseApp.configure()` and logs why, and `scripts/ios-testflight.sh --env production`
hard-refuses to upload. Do this when you want production TestFlight.

1. **Firebase console** → project `stationly-prod` → **Add app** → iOS → bundle id
   `com.stationly.mobile`.
2. **Authentication → Sign-in method** → enable **Google** and **Apple**.
3. **Cloud Messaging** → upload the APNs `.p8` auth key for team `7T7D5LLYSL`. (The same
   key already uploaded to the staging project — one key serves every app on the team.)
4. Download `GoogleService-Info.plist` and commit it over
   `iosApp/Config/GoogleService-Info-Production.plist`, replacing the stub.
5. Copy `REVERSED_CLIENT_ID` out of that plist into `GOOGLE_REVERSED_CLIENT_ID` in
   `iosApp/Config/Production.xcconfig`.
6. Put the production backend API key into `iosApp/Config/Secrets.xcconfig` (git-ignored;
   template at `Secrets.example.xcconfig`) as `STATIONLY_API_KEY_PRODUCTION`.
7. Repeat Task A for a `Stationly` app record on `com.stationly.mobile`, and remove the
   `--env production` guard in `scripts/ios-testflight.sh`.

For production, keep the **internal** track for the team and add an external "Early
Access" group only when you want outside testers.

---

## Not your job — already done

Listed here because `IOS_ENV_SPLIT_AND_TESTFLIGHT.md` §6 still asks for some of it, and
that list is stale.

- ✅ All four App IDs registered on team `7T7D5LLYSL` (`com.stationly.mobile[.staging]`
  and their `.StationlyWidget` extensions)
- ✅ Both App Groups created and attached — `group.com.stationly.staging` and
  `group.com.stationly.shared`
- ✅ App Groups, Push, and Sign in with Apple capabilities enabled on the App IDs
- ✅ Staging Firebase (`mindthetimefcm`) fully configured, APNs key uploaded, push verified
  on device

The one thing missing on the signing side is an **Apple Distribution certificate** — the
keychain has only Development certs. `xcodebuild -allowProvisioningUpdates` will create it
automatically on the first archive, using the Apple ID signed into Xcode. That account
already has the rights (it created App IDs during the session), so this should need no
action — but if the first archive fails on signing, this is why.
