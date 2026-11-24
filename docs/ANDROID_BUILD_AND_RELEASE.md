# Android: building for a phone and for Google Play

The Android twin of `IOS_BUILD_AND_HANDOFF.md` and `IOS_ENV_SPLIT_AND_TESTFLIGHT.md`.
Two scripts, both of which make you NAME the environment, with no default:

| Script | What it makes | Signed with | Backend |
|---|---|---|---|
| `scripts/android-dev.sh staging` | debug APK, installed and launched on the connected phone | debug key | staging-api.stationly.co.uk |
| `scripts/android-dev.sh production` | debug APK on the phone | debug key | **api.stationly.co.uk (real users)** |
| `scripts/android-release.sh production --version-code N` | signed `.aab` for Play, plus an inspection `.apk` | production upload key | api.stationly.co.uk |

Staging has no Play release (see "One package name" below).

## Where the production config comes from

Nothing secret is in git. A production build reads:

| Setting | Source |
|---|---|
| Backend `api.stationly.co.uk` | `BuildConfig.FLAVOR == "prod"` → `AppEnvironment.PRODUCTION` (`StationlyApplication`, `AppConfig`) |
| Firebase `stationly-prod` | `android/app/src/prod/google-services.json` (git-ignored, on the build machine) |
| API key | `prod.STATIONLY_API_KEY` in `local.properties` (git-ignored) |
| Upload signing key | `prod.keystore.path/storePassword/keyAlias/keyPassword` in `local.properties` |
| App name, deep link scheme | the `prod` flavour: "Stationly", `stationly://` |
| R8 shrink + obfuscation | `release` build type |
| Version code | `-Pstationly.versionCode=N` from the script; 4 is the committed floor |

## What the release script checks, on the BUILT files

Every check reads the artefact, never the settings that should have produced it
(the lesson from the iOS script, where every environment mix-up produced a
green build):

1. Package `com.stationly.mobile`, the version code you asked for, a version
   name without `-staging`, label "Stationly".
2. Firebase project `stationly-prod`, and the Firebase app id equal to the one
   for this package in the local `google-services.json`.
3. `BuildConfig`: `FLAVOR = "prod"`, deep link scheme `stationly`, API key
   present, equal to `prod.STATIONLY_API_KEY` and not the staging key. The key
   is never printed.
4. Signer: the production keystore's certificate, not the debug key, on both
   the APK and the bundle.
5. R8 ran (the mapping file exists).

It refuses to run with uncommitted changes unless `--allow-dirty`, and writes
the bundle, APK, mapping and a `BUILD_INFO.txt` (commit, signer, version) to
`android/build/releases/production-<code>/` (git-ignored).

## One package name, two environments

Staging and production are both `com.stationly.mobile`. A phone holds ONE of
them, and Android refuses to install over an app signed by a different key.
Switching means uninstalling, which deletes local data (boards return on
sign-in; widgets must be re-added). `android-dev.sh` checks the installed
signer and stops with the uninstall command; it never uninstalls for you.

Both DEBUG builds are signed with the debug key, so Android would happily put a
production debug build over the staging app, keeping staging's boards and
sign-in against the production backend. `android-dev.sh` refuses that switch
too (it reads `-staging` in the installed version name) unless you pass
`--switch`.

## Internal testing on Play

1. `scripts/android-release.sh production --version-code N`. Play rejects any
   code it has seen, including refused uploads: check Play Console → App bundle
   explorer for the highest used.
2. Play Console → Testing → Internal testing → Create new release → upload the
   `.aab`. The R8 mapping travels inside it.
3. Testers install from the opt-in link. Play re-signs with the app signing
   key, the same key as the live app, so it upgrades the live v1 in place:
   install v1 from Play first to test the real upgrade path.

The `.apk` beside the bundle is signed with the UPLOAD key, so it cannot update
a Play install, and Google sign-in on it works only if that key's SHA-1 is
registered in `stationly-prod`. Use it for inspection, not testing.

## Known

- The build prints `e: ... billing-ktx ... binary version of its metadata is
  2.3.0, expected version is 2.1.0` and still succeeds. Not yet traced to the
  task that prints it; the compile itself passes and every artefact check
  passes.
