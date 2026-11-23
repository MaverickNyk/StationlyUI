#!/usr/bin/env bash
#
# Stationly Android — build a signed release bundle (.aab) for Google Play.
#
# The ENVIRONMENT IS ALWAYS NAMED EXPLICITLY — first argument, no default:
#
#   scripts/android-release.sh production --version-code 5
#   scripts/android-release.sh production --version-code 5 --allow-dirty
#
# `prod` is accepted as an alias for `production`, and `--env <name>` works.
# Staging is refused: it shares the package name com.stationly.mobile, so Play
# has nowhere to put it, and it is tested with scripts/android-dev.sh instead.
#
# What it does, the Android twin of scripts/ios-testflight.sh:
#
#   1. Preflight: the production secrets exist in local.properties (never
#      printed), the keystore is on disk, the version code is above the floor.
#   2. Builds the .aab for Play AND a .apk from the same inputs. The .apk is not
#      for Play; it is what this script can inspect (aapt2 reads APKs, not
#      bundles) and what can be sideloaded for a smoke test.
#   3. Verifies the BUILT ARTEFACTS, never the settings that should produce
#      them: package, version, label, Firebase project, backend environment,
#      API key present, release signer (not the debug key), R8 ran, and the
#      bundle carries the same signer as the APK.
#   4. Copies the bundle, the APK, the R8 mapping and a BUILD_INFO.txt to
#      android/build/releases/<environment>-<code>/.
#
# Nothing is uploaded. Upload the .aab in Play Console (Testing → Internal
# testing → Create new release). The R8 mapping travels inside the bundle.

set -euo pipefail

# Every inspection below CAPTURES a tool's whole output and then parses it.
# Never `tool | awk '...; exit'` or `tool | head -1`: the reader closes the pipe
# early, the tool dies of SIGPIPE, pipefail turns that into a failure, and
# set -e ends the script with no message at all (the same trap is written up
# in scripts/ios-testflight.sh, step 4a).

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"

ENVIRONMENT=""
VERSION_CODE=""
ALLOW_DIRTY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        staging|production|prod) ENVIRONMENT="$1"; shift ;;
        --env)          ENVIRONMENT="$2"; shift 2 ;;
        --version-code) VERSION_CODE="$2"; shift 2 ;;
        --allow-dirty)  ALLOW_DIRTY=1; shift ;;
        -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ "$ENVIRONMENT" == "prod" ]] && ENVIRONMENT="production"

if [[ -z "$ENVIRONMENT" ]]; then
    echo "error: name the environment. There is no default, on purpose." >&2
    echo "  scripts/android-release.sh production --version-code <n>" >&2
    exit 2
fi

case "$ENVIRONMENT" in
    production)
        FLAVOR="prod"; TASK_SUFFIX="ProdRelease"
        EXPECT_PACKAGE="com.stationly.mobile"; EXPECT_LABEL="Stationly"
        EXPECT_SCHEME="stationly"; EXPECT_FIREBASE="stationly-prod" ;;
    staging)
        echo "error: staging has no Play release." >&2
        echo "  It shares the package com.stationly.mobile with production, so Play cannot" >&2
        echo "  hold both. Install it on a phone with: scripts/android-dev.sh staging" >&2
        exit 2 ;;
    *) echo "unknown environment: $ENVIRONMENT (want production)" >&2; exit 2 ;;
esac

# ── Version code ────────────────────────────────────────────────────────────
FLOOR="$(grep -oE 'toInt\(\) \?: [0-9]+' android/app/build.gradle.kts | grep -oE '[0-9]+$')"
if [[ -z "$VERSION_CODE" ]]; then
    echo "error: --version-code <n> is required." >&2
    echo "  Play rejects any code it has already seen, including uploads it refused." >&2
    echo "  Check Play Console → App bundle explorer for the highest used. Floor: $FLOOR" >&2
    exit 2
fi
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || { echo "error: --version-code must be a number" >&2; exit 2; }
(( VERSION_CODE >= FLOOR )) || { echo "error: version code $VERSION_CODE is below the committed floor $FLOOR" >&2; exit 2; }

# ── 1. Preflight ────────────────────────────────────────────────────────────
fail() { echo "error: $1" >&2; shift; for l in "$@"; do echo "  $l" >&2; done; exit 1; }
lp() { grep -E "^$1=" "$ROOT/local.properties" 2>/dev/null | awk 'NR==1' | cut -d= -f2-; }

[[ -f local.properties ]] || fail "no local.properties at the repo root."
for key in prod.keystore.path prod.keystore.storePassword prod.keystore.keyAlias \
           prod.keystore.keyPassword prod.STATIONLY_API_KEY sdk.dir; do
    [[ -n "$(lp "$key")" ]] || fail "local.properties has no $key." \
        "Production secrets live only there (git-ignored). Nothing is printed."
done

KS_PATH="$(lp prod.keystore.path)"
[[ "$KS_PATH" = /* ]] || KS_PATH="$ROOT/android/app/$KS_PATH"   # relative to the app module, as Gradle reads it
[[ -f "$KS_PATH" ]] || fail "the production keystore is not on disk (prod.keystore.path)."

SDK="$(lp sdk.dir)"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AAPT2="$BT/aapt2"; APKSIGNER="$BT/apksigner"
[[ -x "$AAPT2" && -x "$APKSIGNER" ]] || fail "aapt2/apksigner not found under $BT."

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
COMMIT="$(git rev-parse --short HEAD)"
DIRTY="$(git status --porcelain)"
if [[ -n "$DIRTY" && $ALLOW_DIRTY -eq 0 ]]; then
    fail "the working tree has uncommitted changes." \
        "A release should be exactly a commit, so it can be rebuilt and blamed." \
        "Commit first, or pass --allow-dirty for a throwaway test build."
fi

echo "╔══════════════════════════════════════════════════════════════════"
printf "║  %s  →  Play bundle (.aab), NOTHING uploaded\n" "$(tr '[:lower:]' '[:upper:]' <<< "$ENVIRONMENT")"
echo "╟──────────────────────────────────────────────────────────────────"
printf "║  package       %s\n" "$EXPECT_PACKAGE"
printf "║  version       2.0 (code %s)\n" "$VERSION_CODE"
printf "║  backend       api.stationly.co.uk\n"
printf "║  firebase      %s\n" "$EXPECT_FIREBASE"
printf "║  source        %s @ %s%s\n" "$BRANCH" "$COMMIT" "$([[ -n "$DIRTY" ]] && echo '  (DIRTY)')"
echo "╚══════════════════════════════════════════════════════════════════"

# ── 2. Build ────────────────────────────────────────────────────────────────
echo "▸ Building bundle${TASK_SUFFIX} and assemble${TASK_SUFFIX} (R8, a few minutes)…"
./gradlew ":android:app:bundle${TASK_SUFFIX}" ":android:app:assemble${TASK_SUFFIX}" \
    -Pstationly.versionCode="$VERSION_CODE" -q

OUT="$ROOT/android/app/build/outputs"
AAB="$(find "$OUT/bundle/${FLAVOR}Release" -name '*.aab' -print -quit)"
APK="$(find "$OUT/apk/$FLAVOR/release" -name '*.apk' -print -quit)"
MAPPING="$OUT/mapping/${FLAVOR}Release/mapping.txt"
BUILDCONFIG="$(find "$ROOT/android/app/build/generated/source/buildConfig/$FLAVOR/release" -name BuildConfig.java -print -quit)"
[[ -f "$AAB" ]] || fail "no .aab produced under $OUT/bundle/${FLAVOR}Release."
[[ -f "$APK" ]] || fail "no .apk produced under $OUT/apk/$FLAVOR/release."

# ── 3. Verify the artefacts ─────────────────────────────────────────────────
echo "▸ Verifying what was built…"

# 3a. Identity: package, version, name.
BADGING="$("$AAPT2" dump badging "$APK")"
PKG="$(sed -nE "s/^package: name='([^']*)'.*/\1/p" <<< "$BADGING")"
VC="$(sed -nE "s/.* versionCode='([^']*)'.*/\1/p" <<< "$BADGING" | awk 'NR==1')"
VN="$(sed -nE "s/.* versionName='([^']*)'.*/\1/p" <<< "$BADGING" | awk 'NR==1')"
LABEL="$(sed -nE "s/^application-label:'(.*)'$/\1/p" <<< "$BADGING")"
[[ "$PKG" == "$EXPECT_PACKAGE" ]] || fail "APK package is '$PKG', expected '$EXPECT_PACKAGE'."
[[ "$VC" == "$VERSION_CODE" ]] || fail "APK versionCode is '$VC', you asked for $VERSION_CODE." \
    "-Pstationly.versionCode did not reach the build. Check android/app/build.gradle.kts."
[[ "$VN" != *staging* ]] || fail "APK versionName is '$VN': this is a STAGING build."
[[ "$LABEL" == "$EXPECT_LABEL" ]] || fail "APK label is '$LABEL', expected '$EXPECT_LABEL'."
echo "  package=$PKG  version=$VN ($VC)  label=$LABEL"

# 3b. Firebase project, read from the compiled resources. A production app
# carrying staging's google-services.json signs users into the staging project
# and receives staging pushes, with every screen looking normal.
RES_DUMP="$("$AAPT2" dump resources "$APK")"
res_string() { awk -v r="string/$1" '$0 ~ r"$" && !done {getline; print; done=1}' <<< "$RES_DUMP" | sed -nE 's/^[^"]*"(.*)"[^"]*$/\1/p'; }
FB="$(res_string project_id)"
FB_APP="$(res_string google_app_id)"
unset RES_DUMP
# The app id is per REGISTERED APP, so it pins this exact package in this exact
# project; compared with the committed google-services.json, not a literal.
EXPECT_FB_APP="$(python3 -c 'import json,sys
d=json.load(open(sys.argv[1]))
print(next(c["client_info"]["mobilesdk_app_id"] for c in d["client"]
           if c["client_info"]["android_client_info"]["package_name"]==sys.argv[2]))' \
    "$ROOT/android/app/src/$FLAVOR/google-services.json" "$EXPECT_PACKAGE")"
[[ "$FB" == "$EXPECT_FIREBASE" ]] || fail "APK Firebase project is '$FB', expected '$EXPECT_FIREBASE'." \
    "The wrong google-services.json reached the build (android/app/src/$FLAVOR/)."
[[ "$FB_APP" == "$EXPECT_FB_APP" ]] || fail "APK Firebase app id does not match android/app/src/$FLAVOR/google-services.json."
echo "  firebase=$FB  app=$FB_APP"

# 3c. Backend environment and API key, from the BuildConfig this build compiled.
# FLAVOR selects api.stationly.co.uk at runtime (StationlyApplication). The key
# is checked for presence and for not being staging's, never printed.
[[ -f "$BUILDCONFIG" ]] || fail "no generated BuildConfig.java for $FLAVOR/release."
grep -q "FLAVOR = \"$FLAVOR\"" "$BUILDCONFIG" || fail "BuildConfig.FLAVOR is not \"$FLAVOR\": the app would pick the wrong backend."
grep -q "DEEP_LINK_SCHEME = \"$EXPECT_SCHEME\"" "$BUILDCONFIG" || fail "BuildConfig.DEEP_LINK_SCHEME is not \"$EXPECT_SCHEME\"."
BUILT_KEY="$(sed -nE 's/.*STATIONLY_API_KEY = "(.*)";/\1/p' "$BUILDCONFIG")"
[[ -n "$BUILT_KEY" ]] || fail "BuildConfig.STATIONLY_API_KEY is EMPTY: every API call would be refused."
[[ "$BUILT_KEY" == "$(lp prod.STATIONLY_API_KEY)" ]] || fail "BuildConfig.STATIONLY_API_KEY is not prod.STATIONLY_API_KEY."
[[ "$BUILT_KEY" != "$(lp staging.STATIONLY_API_KEY)" ]] || fail "BuildConfig.STATIONLY_API_KEY is the STAGING key."
unset BUILT_KEY
echo "  backend=production (FLAVOR=$FLAVOR)  api-key=present, production  scheme=$EXPECT_SCHEME://"

# 3d. Signer. Must be the production key, and must not be the debug key: a
# debug-signed bundle is refused by Play, and a debug-signed APK cannot update
# a Play install.
sha256_of() { tr -d ':' | tr '[:upper:]' '[:lower:]'; }
first_sha256() { sed -nE 's/^[[:space:]]*SHA256: (.*)$/\1/p' | awk 'NR==1' | sha256_of; }
APK_CERTS="$("$APKSIGNER" verify --print-certs "$APK")"
APK_SHA="$(sed -nE 's/^Signer #1 certificate SHA-256 digest: (.*)$/\1/p' <<< "$APK_CERTS" | sha256_of)"
KS_LIST="$(KSPASS="$(lp prod.keystore.storePassword)" keytool -list -v -keystore "$KS_PATH" \
    -alias "$(lp prod.keystore.keyAlias)" -storepass:env KSPASS 2>/dev/null || true)"
KS_SHA="$(first_sha256 <<< "$KS_LIST")"
DEBUG_LIST="$(keytool -list -v -keystore "$HOME/.android/debug.keystore" -alias androiddebugkey \
    -storepass android 2>/dev/null || true)"
DEBUG_SHA="$(first_sha256 <<< "$DEBUG_LIST")"
AAB_CERT="$(keytool -printcert -jarfile "$AAB" 2>/dev/null || true)"
AAB_SHA="$(first_sha256 <<< "$AAB_CERT")"
[[ -n "$APK_SHA" ]] || fail "could not read the APK's signer."
[[ -n "$KS_SHA" ]]  || fail "could not read the production keystore's certificate (alias or password wrong?)."
[[ "$APK_SHA" != "$DEBUG_SHA" ]] || fail "the APK is signed with the DEBUG key."
[[ "$APK_SHA" == "$KS_SHA" ]]    || fail "the APK is not signed by the production keystore."
[[ "$AAB_SHA" == "$KS_SHA" ]]    || fail "the bundle is not signed by the production keystore (Play will refuse it)."
echo "  signer=production keystore (sha256 ${KS_SHA:0:16}…), bundle and APK match"

# 3e. R8 ran. Its mapping is the proof, and Play needs it to read crash traces.
[[ -s "$MAPPING" ]] || fail "no R8 mapping at $MAPPING: the release was not minified."
echo "  r8=on (mapping $(wc -l < "$MAPPING" | tr -d ' ') lines)"

# ── 4. Collect ──────────────────────────────────────────────────────────────
DEST="$ROOT/android/build/releases/$ENVIRONMENT-$VERSION_CODE"
rm -rf "$DEST"; mkdir -p "$DEST"
cp "$AAB" "$DEST/stationly-$ENVIRONMENT-$VERSION_CODE.aab"
cp "$APK" "$DEST/stationly-$ENVIRONMENT-$VERSION_CODE.apk"
cp "$MAPPING" "$DEST/mapping.txt"
cat > "$DEST/BUILD_INFO.txt" <<EOF
environment   $ENVIRONMENT
package       $PKG
version       $VN ($VC)
firebase      $FB
backend       https://api.stationly.co.uk
signer sha256 $KS_SHA
source        $BRANCH @ $(git rev-parse HEAD)$([[ -n "$DIRTY" ]] && echo ' (DIRTY working tree)')
built         $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

echo "✓ $(tr '[:lower:]' '[:upper:]' <<< "$ENVIRONMENT") $VN ($VC) ready in $DEST"
echo "  Upload stationly-$ENVIRONMENT-$VERSION_CODE.aab in Play Console → Testing → Internal testing."
echo "  The .apk is for inspection only: signed with the upload key, it cannot update a Play install."
