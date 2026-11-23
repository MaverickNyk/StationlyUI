#!/usr/bin/env bash
#
# Stationly Android — build, install and launch on the connected phone.
#
# The ENVIRONMENT IS ALWAYS NAMED EXPLICITLY — first argument, no default:
#
#   scripts/android-dev.sh staging               # build, install, launch
#   scripts/android-dev.sh staging --no-launch   # install only
#   scripts/android-dev.sh production            # debug build on the PRODUCTION backend
#   scripts/android-dev.sh production --switch   # replace an installed STAGING app
#
# `prod` is accepted as an alias, and `--env <name>` works. The Android twin of
# scripts/ios-dev.sh.
#
# ⚠️  Staging and production share the package name com.stationly.mobile, so a
# phone holds ONE of them. Android refuses to install over an app signed by a
# different key, and the only way past that is uninstalling, which deletes the
# app's local data. This script checks the signer first and stops with the
# command to run; it never uninstalls for you.
#
# Both DEBUG builds share the debug key, so Android WOULD install one over the
# other. The script refuses that too: the installed app's local boards and
# sign-in belong to the other environment's backend and Firebase project. Pass
# --switch to do it anyway (a clean uninstall is the honest way).
#
# A production DEBUG build is signed with the debug key and talks to the real
# backend with real users' data. Google sign-in on it only works if the debug
# key's SHA-1 is registered in the stationly-prod Firebase project. For the
# release path, use scripts/android-release.sh.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"

ENVIRONMENT=""
LAUNCH=1
SWITCH=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        staging|production|prod) ENVIRONMENT="$1"; shift ;;
        --env)       ENVIRONMENT="$2"; shift 2 ;;
        --no-launch) LAUNCH=0; shift ;;
        --switch)    SWITCH=1; shift ;;
        -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ "$ENVIRONMENT" == "prod" ]] && ENVIRONMENT="production"

if [[ -z "$ENVIRONMENT" ]]; then
    echo "error: name the environment. There is no default, on purpose." >&2
    echo "  scripts/android-dev.sh staging    [--no-launch]" >&2
    echo "  scripts/android-dev.sh production [--no-launch]" >&2
    exit 2
fi

case "$ENVIRONMENT" in
    staging)    FLAVOR="staging" ;;
    production) FLAVOR="prod"
        echo "⚠️  PRODUCTION backend: real users, real data, api.stationly.co.uk." ;;
    *) echo "unknown environment: $ENVIRONMENT (want staging|production)" >&2; exit 2 ;;
esac
FLAVOR_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"
PACKAGE="com.stationly.mobile"

lp() { grep -E "^$1=" "$ROOT/local.properties" 2>/dev/null | awk 'NR==1' | cut -d= -f2-; }
SDK="$(lp sdk.dir)"
APKSIGNER="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)/apksigner"
[[ -n "$(lp "$FLAVOR.STATIONLY_API_KEY")" ]] || {
    echo "error: local.properties has no $FLAVOR.STATIONLY_API_KEY; every API call would be refused." >&2; exit 1; }

# ── 1. The phone ────────────────────────────────────────────────────────────
DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(grep -c . <<< "$DEVICES" || true)"
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    grep -qx "$ANDROID_SERIAL" <<< "$DEVICES" || { echo "error: ANDROID_SERIAL=$ANDROID_SERIAL is not connected." >&2; exit 1; }
elif [[ "$COUNT" -eq 0 ]]; then
    echo "error: no phone connected (adb devices). Plug in and allow USB debugging." >&2; exit 1
elif [[ "$COUNT" -gt 1 ]]; then
    echo "error: $COUNT phones connected; pick one with ANDROID_SERIAL=<serial>." >&2; exit 1
else
    export ANDROID_SERIAL="$DEVICES"
fi
echo "▸ ENVIRONMENT: $(tr '[:lower:]' '[:upper:]' <<< "$ENVIRONMENT")  (${FLAVOR}Debug → $ANDROID_SERIAL)"

# ── 2. Build ────────────────────────────────────────────────────────────────
echo "▸ Building assemble${FLAVOR_CAP}Debug…"
./gradlew ":android:app:assemble${FLAVOR_CAP}Debug" -q
APK="$(find "$ROOT/android/app/build/outputs/apk/$FLAVOR/debug" -name '*.apk' -print -quit)"
[[ -f "$APK" ]] || { echo "error: no APK produced." >&2; exit 1; }

# ── 3. Will Android accept it over what is installed? ───────────────────────
CERTS="$("$APKSIGNER" verify --print-certs "$APK")"
NEW_SHA="$(sed -nE 's/^Signer #1 certificate SHA-256 digest: (.*)$/\1/p' <<< "$CERTS" | tr -d ':' | tr '[:upper:]' '[:lower:]')"
INSTALLED="$(adb shell dumpsys package "$PACKAGE" 2>/dev/null || true)"
if grep -q "versionName=" <<< "$INSTALLED"; then
    OLD_NAME="$(sed -nE 's/^[[:space:]]*versionName=(.*)$/\1/p' <<< "$INSTALLED" | awk 'NR==1')"
    OLD_SHA="$(sed -nE 's/.*signatures:\[([0-9A-Fa-f]+)\].*/\1/p' <<< "$INSTALLED" | awk 'NR==1' | tr '[:upper:]' '[:lower:]')"
    if [[ -n "$OLD_SHA" && "$OLD_SHA" != "$NEW_SHA" ]]; then
        echo "error: the phone has $PACKAGE $OLD_NAME signed by a DIFFERENT key." >&2
        echo "  Android will refuse this install. To replace it (this DELETES its local" >&2
        echo "  data: boards come back on sign-in, widgets must be re-added):" >&2
        echo "    adb -s $ANDROID_SERIAL uninstall $PACKAGE" >&2
        exit 1
    fi
    OLD_ENV="production"; [[ "$OLD_NAME" == *-staging* ]] && OLD_ENV="staging"
    if [[ "$OLD_ENV" != "$ENVIRONMENT" && $SWITCH -eq 0 ]]; then
        echo "error: the phone has the $OLD_ENV app ($OLD_NAME); you asked for $ENVIRONMENT." >&2
        echo "  Its boards and sign-in belong to the $OLD_ENV backend. Uninstall first:" >&2
        echo "    adb -s $ANDROID_SERIAL uninstall $PACKAGE" >&2
        echo "  or pass --switch to install over it anyway." >&2
        exit 1
    fi
    echo "  replacing installed $OLD_NAME (same signer)"
fi

# ── 4. Install and launch ───────────────────────────────────────────────────
echo "▸ Installing…"
adb install -r "$APK" >/dev/null
echo "✓ installed $(basename "$APK")"
if [[ $LAUNCH -eq 1 ]]; then
    adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    echo "✓ launched"
fi
