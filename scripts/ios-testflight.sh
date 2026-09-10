#!/usr/bin/env bash
#
# Stationly iOS — archive and upload a build to TestFlight.
#
# The ENVIRONMENT IS ALWAYS NAMED EXPLICITLY — first argument, no default:
#
#   scripts/ios-testflight.sh staging    --build 8             # → TestFlight
#   scripts/ios-testflight.sh staging    --build 8 --dry-run   # archive + .ipa, no upload
#   scripts/ios-testflight.sh staging    --build 8 --resume    # reuse the archive on disk
#   scripts/ios-testflight.sh production --build 8             # (refused, see below)
#
# `--resume` skips producing the archive and goes straight to verification and
# export. Use it after an export or upload failure: the archive costs two full
# Kotlin/Native release links (~20 min) and is complete on its own, so a
# failure downstream of it should never cost a rebuild. Every verification
# still runs.
#
# `prod` is accepted as an alias for `production`, and `--env <name>` still
# works for callers that prefer a flag. Omitting the environment is an error
# rather than a default, because the environment decides which Firebase
# project, backend, bundle id and App Store Connect record the build lands in —
# and a default is the one part of a command you cannot see when you read it
# back in a terminal history or a CI log. Every step below re-states the
# environment in its output for the same reason.
#
# Requires an App Store Connect API key, exported once per shell (or put in a
# git-ignored .env you source):
#
#   export ASC_KEY_ID=XXXXXXXXXX
#   export ASC_ISSUER_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
#   export ASC_KEY_PATH=~/.appstoreconnect/private_keys/AuthKey_XXXXXXXXXX.p8
#
# See docs/IOS_ENV_SPLIT_AND_TESTFLIGHT.md §4 for how to create that key and
# what still has to happen in the ASC web UI (the app record and the tester
# group are one-time, manual, and cannot be scripted).

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"

ENVIRONMENT=""
BUILD_NUMBER=""
DRY_RUN=0
# --resume: reuse the .xcarchive already on disk for this environment+build and
# go straight to verification and export. The archive is the expensive artefact
# (two full Kotlin/Native release links, ~20 minutes) and it is complete and
# self-describing, so a failure in the export or upload step should not cost a
# rebuild. Every verification in step 3 still runs — resuming skips producing
# the archive, never checking it.
RESUME=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        staging|production|prod) ENVIRONMENT="$1"; shift ;;
        --env)     ENVIRONMENT="$2"; shift 2 ;;
        --build)   BUILD_NUMBER="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --resume)  RESUME=1; shift ;;
        # Prints the header block, stopping at the first non-comment line, so
        # the help can never disagree with the file. It was a hardcoded
        # `sed -n '2,30p'` and had already drifted — editing the header silently
        # truncated --help mid-sentence.
        -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ "$ENVIRONMENT" == "prod" ]] && ENVIRONMENT="production"

if [[ -z "$ENVIRONMENT" ]]; then
    echo "error: name the environment. There is no default, on purpose." >&2
    echo "  scripts/ios-testflight.sh staging    --build <n> [--dry-run]" >&2
    echo "  scripts/ios-testflight.sh production --build <n> [--dry-run]" >&2
    exit 2
fi

case "$ENVIRONMENT" in
    staging)
        SCHEME="iosApp Staging"; CONFIG="Release Staging" ;;
    production)
        # Refused rather than warned. An uploaded production build would carry
        # placeholder Firebase credentials and could not sign in — and a broken
        # build sitting in TestFlight is far more expensive to walk back than
        # one that was never made.
        echo "error: production is not ready to ship." >&2
        echo "  Its Firebase config is a placeholder, so the uploaded build could not sign in." >&2
        echo "  Complete docs/IOS_ENV_SPLIT_AND_TESTFLIGHT.md §6 steps 1-2, then remove this guard." >&2
        exit 1 ;;
    *) echo "unknown environment: $ENVIRONMENT" >&2; exit 2 ;;
esac

if [[ -z "$BUILD_NUMBER" ]]; then
    echo "error: --build <n> is required." >&2
    echo "  Build numbers must be UNIQUE and INCREASING within a marketing version;" >&2
    echo "  App Store Connect rejects a repeat outright. Current committed floor:" >&2
    grep -E '^CURRENT_PROJECT_VERSION' iosApp/Config/Base.xcconfig >&2
    exit 2
fi

if [[ $DRY_RUN -eq 0 ]]; then
    : "${ASC_KEY_ID:?set ASC_KEY_ID (see header)}"
    : "${ASC_ISSUER_ID:?set ASC_ISSUER_ID (see header)}"
    : "${ASC_KEY_PATH:?set ASC_KEY_PATH (see header)}"
    [[ -f "$ASC_KEY_PATH" ]] || { echo "error: no .p8 at $ASC_KEY_PATH" >&2; exit 1; }
fi

ENV_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${ENVIRONMENT:0:1}")${ENVIRONMENT:1}"   # Staging | Production
ARCHIVE="$ROOT/iosApp/build/archives/Stationly-$ENVIRONMENT-$BUILD_NUMBER.xcarchive"
EXPORT_DIR="$ROOT/iosApp/build/export/$ENVIRONMENT-$BUILD_NUMBER"
OPTIONS="$ROOT/iosApp/Config/exportOptions-$ENV_CAP.plist"

# ── Say what is about to happen, before anything happens ──────────────────
#
# The bundle id is read out of the xcconfigs rather than written here, so this
# banner cannot claim one environment while the build produces another. It is
# re-verified against the actual archive in step 3.
xcfg() { grep -E "^$1[[:space:]]*=" "$2" 2>/dev/null | head -1 | sed -E 's/^[^=]*=[[:space:]]*//; s/[[:space:]]*$//'; }
_base="$(xcfg STATIONLY_BUNDLE_BASE   "$ROOT/iosApp/Config/Base.xcconfig")"
_sfx="$(xcfg  STATIONLY_BUNDLE_SUFFIX "$ROOT/iosApp/Config/$ENV_CAP.xcconfig")"

echo "╔══════════════════════════════════════════════════════════════════"
printf "║  %s  →  %s\n" "$(tr '[:lower:]' '[:upper:]' <<< "$ENVIRONMENT")" \
    "$([[ $DRY_RUN -eq 1 ]] && echo 'DRY RUN — local .ipa, NOTHING uploaded' || echo 'App Store Connect / TestFlight')"
echo "╟──────────────────────────────────────────────────────────────────"
printf "║  scheme     %s\n"  "$SCHEME"
printf "║  config     %s\n"  "$CONFIG"
printf "║  bundle id  %s\n"  "${_base}${_sfx}"
printf "║  build      %s\n"  "$BUILD_NUMBER"
echo "╚══════════════════════════════════════════════════════════════════"

# ── 1. RELEASE Kotlin framework ───────────────────────────────────────────
#
# Not optional, and the single most dangerous step to skip. xcodebuild links a
# PREBUILT XCFramework: with no release build present the "Select Kotlin
# XCFramework" phase fails the build (by design) — but before that guard
# existed, the project pointed at the debug framework unconditionally and a
# release archive would happily ship unoptimised debug Kotlin, with a green
# build and no warning anywhere.
BUILD_LOG="$ROOT/iosApp/build/archive-$ENVIRONMENT-$BUILD_NUMBER.log"

if [[ $RESUME -eq 1 ]]; then
    [[ -d "$ARCHIVE" ]] || { echo "error: --resume but no archive at $ARCHIVE" >&2; exit 1; }
    [[ -f "$BUILD_LOG" ]] || { echo "error: --resume but no build log at $BUILD_LOG" >&2
        echo "  Step 3 asserts the Kotlin variant from that log and cannot run without it." >&2; exit 1; }
    echo "▸ Resuming from the existing archive (skipping build)."
else

echo "▸ Assembling RELEASE XCFramework (slow)…"
./gradlew :composeApp:assembleComposeAppReleaseXCFramework -q
./gradlew :composeApp:assembleIosArm64MainResources -q

echo "▸ Regenerating Xcode project…"
( cd iosApp && ./xcodegen.sh >/dev/null )

echo "▸ Resolving packages…"
DD="$ROOT/iosApp/build/DD-release"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "$SCHEME" \
    -derivedDataPath "$DD" -resolvePackageDependencies >/dev/null
if [[ -d "$DD/SourcePackages/checkouts" ]]; then
    chmod -R u+w "$DD/SourcePackages/checkouts"
    find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete
fi

# ── 2. Archive ────────────────────────────────────────────────────────────
#
# CURRENT_PROJECT_VERSION is passed on the command line, which overrides the
# xcconfig, and applies to BOTH targets. That matters: App Store Connect
# rejects an upload whose extension build number differs from its app's.
echo "▸ Archiving $SCHEME (build $BUILD_NUMBER)…"
rm -rf "$ARCHIVE"
# The full log is kept, not just the filtered view. When an archive fails, the
# grep below has almost always hidden the reason.
mkdir -p "$(dirname "$BUILD_LOG")"
set +e
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "$SCHEME" -configuration "$CONFIG" \
    -destination 'generic/platform=iOS' -derivedDataPath "$DD" \
    -archivePath "$ARCHIVE" \
    CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
    -allowProvisioningUpdates archive \
    > "$BUILD_LOG" 2>&1
ARCHIVE_STATUS=$?
set -e
grep -E "Kotlin XCFramework:|Firebase config:|warning: .*PLACEHOLDER|error:|ARCHIVE SUCCEEDED|ARCHIVE FAILED" \
    "$BUILD_LOG" || true

# xcodebuild's own exit status, which used to be discarded: the command was
# piped into grep and terminated with `|| true`, so the ONLY thing standing
# between a failed archive and an upload attempt was the directory check below
# — and a failure late in the archive can leave that directory behind.
if [[ $ARCHIVE_STATUS -ne 0 ]]; then
    echo "error: xcodebuild archive failed (exit $ARCHIVE_STATUS). Full log: $BUILD_LOG" >&2
    exit 1
fi
[[ -d "$ARCHIVE" ]] || { echo "error: archive not produced. Full log: $BUILD_LOG" >&2; exit 1; }

fi   # end of the non---resume build path

# ── 3. Verify the archive before sending it ───────────────────────────────
#
# Every check below reads the BUILT ARTEFACT, never the build settings that
# were supposed to produce it. That distinction is the point: each of these
# failures produces a perfectly green archive, and the build settings always
# look right — it is the chain from setting to binary that breaks.
#
# The expensive ones to discover on Apple's side are the ASC rejections (build
# numbers, privacy manifest). The dangerous one is an environment mixup, which
# ASC will happily accept and hand to testers.
APP="$ARCHIVE/Products/Applications/iosApp.app"
APP_PLIST="$APP/Info.plist"
EXT_PLIST="$APP/PlugIns/StationlyWidget.appex/Info.plist"
plist() { /usr/libexec/PlistBuddy -c "Print :$1" "$2" 2>/dev/null; }

APP_BUILD="$(plist CFBundleVersion "$APP_PLIST")"
EXT_BUILD="$(plist CFBundleVersion "$EXT_PLIST")"
BUNDLE_ID="$(plist CFBundleIdentifier "$APP_PLIST")"
ARCHIVE_ENV="$(plist StationlyEnvironment "$APP_PLIST")"
ARCHIVE_GROUP="$(plist StationlyAppGroup "$APP_PLIST")"
FB_PROJECT="$(plist PROJECT_ID "$APP/GoogleService-Info.plist")"
FB_BUNDLE="$(plist BUNDLE_ID "$APP/GoogleService-Info.plist")"

echo "▸ Archive: $BUNDLE_ID  app=$APP_BUILD  widget=$EXT_BUILD"
echo "  env=$ARCHIVE_ENV  group=$ARCHIVE_GROUP  firebase=$FB_PROJECT"

fail() { echo "error: $1" >&2; shift; for l in "$@"; do echo "  $l" >&2; done; exit 1; }

# ── 3a. Is this the environment we asked for? ──
#
# The xcconfig → build settings → Info.plist chain has three places to break,
# and the most likely — a stray assignment in project.yml's `settings:` block,
# which BEATS the xcconfig — leaves every file looking correct in isolation.
[[ "$BUNDLE_ID" == "${_base}${_sfx}" ]] || fail \
    "archive is $BUNDLE_ID but $ENVIRONMENT means ${_base}${_sfx}." \
    "The xcconfig did not reach the build. Check project.yml is not assigning these."
[[ "$ARCHIVE_ENV" == "$ENVIRONMENT" ]] || fail \
    "archive says StationlyEnvironment=$ARCHIVE_ENV, you asked for $ENVIRONMENT." \
    "This selects the API base URL at runtime — the app would talk to the wrong backend."
[[ "$ARCHIVE_GROUP" == "$(xcfg STATIONLY_APP_GROUP "$ROOT/iosApp/Config/$ENV_CAP.xcconfig")" ]] || fail \
    "archive App Group is $ARCHIVE_GROUP, not what $ENV_CAP.xcconfig declares."

# ── 3b. Is it the right Firebase project? ──
#
# Presence of GOOGLE_APP_ID only proves the plist is not the placeholder stub.
# It does NOT prove it is the plist for THIS environment — a production archive
# carrying the staging config passes that test, authenticates against the
# staging project, and mints staging uids for production users. Which project
# and which bundle id are the questions that actually matter.
#
# Both values are compared against the committed plist the build was supposed
# to use, rather than against a hardcoded name here, so this cannot drift.
EXPECTED_PLIST="$ROOT/iosApp/Config/$(xcfg STATIONLY_FIREBASE_PLIST "$ROOT/iosApp/Config/$ENV_CAP.xcconfig").plist"
[[ -n "$FB_PROJECT" ]] || fail \
    "archive carries a PLACEHOLDER Firebase config (no PROJECT_ID) — it could not sign in."
[[ "$FB_PROJECT" == "$(plist PROJECT_ID "$EXPECTED_PLIST")" ]] || fail \
    "archive Firebase project is '$FB_PROJECT', expected '$(plist PROJECT_ID "$EXPECTED_PLIST")'." \
    "The wrong environment's GoogleService-Info.plist is in the bundle." \
    "Most likely cause: a stray GoogleService-Info.plist under iosApp/ — Copy Bundle" \
    "Resources runs AFTER the pre-build script and would overwrite its output."
[[ "$FB_BUNDLE" == "$BUNDLE_ID" ]] || fail \
    "Firebase config is registered for '$FB_BUNDLE' but this app is '$BUNDLE_ID'." \
    "Sign in with Apple validates the identity token's audience against the bundle" \
    "ids registered in the Firebase project, so sign-in would fail for every tester."

# ── 3c. Is the Kotlin in here the RELEASE build? ──
#
# The comment above this block used to claim this check existed. It did not —
# the only signal was the "Select Kotlin XCFramework" phase echoing its choice,
# which was grepped for display and never asserted. A debug Kotlin framework in
# a TestFlight build is invisible: it runs, it is simply unoptimised, and it was
# compiled from a different Gradle variant than the one being shipped.
#
# This asserts the build phase's own reported decision, taken from the full
# archive log. Deliberately NOT a heuristic on the binary: the obvious one —
# looking for __DWARF sections — was measured on both variants of this project
# and finds zero in each, because Kotlin/Native emits debug info to a separate
# .dSYM rather than into the framework. A check that cannot fail is worse than
# no check, because it stops the next person adding a real one.
#
# The phase itself also hard-fails on this mismatch now (project.yml, "Select
# Kotlin XCFramework"), so this is the outer of two independent guards.
if grep -q "^Kotlin XCFramework: release" "$BUILD_LOG"; then
    echo "  kotlin=release (asserted from the build log)"
elif grep -q "^Kotlin XCFramework:" "$BUILD_LOG"; then
    fail "the archive was built against the $(grep -m1 '^Kotlin XCFramework:' "$BUILD_LOG" | cut -d' ' -f3) Kotlin framework, not release." \
         "STATIONLY_KOTLIN_BUILD did not resolve to 'release' for $CONFIG." \
         "Check project.yml still sets it under settings.configs for the Release * configs." \
         "(That override BEATS Base.xcconfig by design — it is not a duplicate to delete.)"
else
    fail "no 'Kotlin XCFramework:' line in the build log — the select phase did not run." \
         "Cannot prove which Kotlin variant is in this archive. Log: $BUILD_LOG"
fi

[[ -f "$APP/PrivacyInfo.xcprivacy" ]] || fail \
    "privacy manifest missing from the app — ASC will reject the upload."

# ── 4. Export ─────────────────────────────────────────────────────────────
#
# ── Why export and upload are two separate tools ──
#
# The obvious form of this step is one `xcodebuild -exportArchive` with
# `destination: upload` and the ASC API key passed in, so it signs and uploads
# in one go. That was the original, and it FAILS at signing:
#
#   403 FORBIDDEN_ERROR — "You haven't been given access to cloud-managed
#   distribution certificates. Please contact your team's Account Holder or an
#   Admin to give you access."
#
# The cause is not obvious from the message. Passing `-authenticationKey*`
# REPLACES the identity xcodebuild signs as: instead of the Apple ID signed
# into Xcode (the Account Holder here), it authenticates as the API key, whose
# role is App Manager — and App Manager is not granted access to cloud-managed
# distribution certificates. The same export without the key succeeds and mints
# the certificate, which is exactly why `--dry-run` passed while the real
# upload did not.
#
# So the two halves are done by the two identities that can actually do them:
#
#   export  — xcodebuild with NO API key → signs as the Xcode Apple ID, which
#             has cloud signing rights.
#   upload  — altool WITH the API key → uploading needs only App Manager.
#
# The alternative is granting the API key Admin, or granting it explicit access
# to cloud-managed distribution certificates in App Store Connect. That works
# too, and is one checkbox — but it hands broad signing authority to a
# credential sitting in a file on disk, to save a step that costs nothing.
rm -rf "$EXPORT_DIR"
echo "▸ Exporting .ipa (signing as the Xcode Apple ID — see the note above)…"
TMP_OPTIONS="$(mktemp -t exportOptions).plist"
cp "$OPTIONS" "$TMP_OPTIONS"
/usr/libexec/PlistBuddy -c "Set :destination export" "$TMP_OPTIONS"
xcodebuild -exportArchive -archivePath "$ARCHIVE" \
    -exportPath "$EXPORT_DIR" -exportOptionsPlist "$TMP_OPTIONS" \
    -allowProvisioningUpdates

IPA="$(find "$EXPORT_DIR" -maxdepth 1 -name '*.ipa' | head -1)"
[[ -n "$IPA" ]] || { echo "error: export produced no .ipa in $EXPORT_DIR" >&2; exit 1; }
echo "✓ .ipa at $IPA"

if [[ $DRY_RUN -eq 1 ]]; then
    echo "▸ Dry run — stopping here, NOTHING uploaded."
    exit 0
fi

# ── 5. Upload ─────────────────────────────────────────────────────────────
#
# altool finds the .p8 by key id in the standard search paths, one of which is
# ~/.appstoreconnect/private_keys — where ASC_KEY_PATH already points. It is
# passed the id rather than the path for that reason.
echo "▸ Uploading to App Store Connect…"
xcrun altool --upload-app --type ios --file "$IPA" \
    --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"

echo "✓ uploaded build $BUILD_NUMBER ($ENVIRONMENT)."
echo "  Processing takes ~5-15 min. Internal testers get it as soon as that finishes;"
echo "  a NEW marketing version needs Beta App Review before external testers do."
