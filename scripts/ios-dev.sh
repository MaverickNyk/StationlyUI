#!/usr/bin/env bash
#
# Stationly IOS — build, install and launch on the connected iPhone.
#
# The ENVIRONMENT IS ALWAYS NAMED EXPLICITLY — first argument, no default:
#
#   scripts/ios-dev.sh staging                 # no Kotlin rebuild (fast path)
#   scripts/ios-dev.sh staging --kotlin        # rebuild the shared framework (~8 min)
#   scripts/ios-dev.sh staging --no-launch     # install only
#   scripts/ios-dev.sh staging --pull-group    # afterwards, dump the App Group plist
#   scripts/ios-dev.sh production
#
# `prod` is accepted as an alias, and `--env <name>` still works. There is no
# default because the two apps now install SIDE BY SIDE with different bundle
# ids and different App Groups — so "which one did I just launch" is a real
# question, and the answer should be in the command, not in this file.
#
# Replaces the hand-run sequence in docs/IOS_BUILD_AND_HANDOFF.md §2. That
# sequence is not hard, but it has five steps that must happen in order, two
# non-obvious SPM workarounds, and a bundle id that now DIFFERS per
# environment — so a stale copy-paste launches the wrong app, or the previous
# build, and looks like the code change did nothing.
#
# Everything environment-specific is READ BACK from the built project rather
# than written here, so this file cannot drift from Config/*.xcconfig.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"

ENVIRONMENT=""
BUILD_KOTLIN=0
LAUNCH=1
PULL_GROUP=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        staging|production|prod) ENVIRONMENT="$1"; shift ;;
        --env)        ENVIRONMENT="$2"; shift 2 ;;
        --kotlin)     BUILD_KOTLIN=1; shift ;;
        --no-launch)  LAUNCH=0; shift ;;
        --pull-group) PULL_GROUP=1; shift ;;
        # Header block, stopping at the first non-comment line — see the same
        # note in ios-testflight.sh. A hardcoded line range drifts the moment
        # anyone edits the header.
        -h|--help)    awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ "$ENVIRONMENT" == "prod" ]] && ENVIRONMENT="production"

if [[ -z "$ENVIRONMENT" ]]; then
    echo "error: name the environment. There is no default, on purpose." >&2
    echo "  scripts/ios-dev.sh staging     [--kotlin] [--no-launch] [--pull-group]" >&2
    echo "  scripts/ios-dev.sh production  [--kotlin] [--no-launch] [--pull-group]" >&2
    exit 2
fi

case "$ENVIRONMENT" in
    staging)    SCHEME="iosApp Staging";    CONFIG="Debug Staging" ;;
    production) SCHEME="iosApp Production"; CONFIG="Debug Production"
        echo "⚠️  Production has PLACEHOLDER Firebase credentials — the app will run but cannot sign in."
        echo "   See docs/IOS_ENV_SPLIT_AND_TESTFLIGHT.md §6." ;;
    *) echo "unknown environment: $ENVIRONMENT (want staging|production)" >&2; exit 2 ;;
esac

echo "▸ ENVIRONMENT: $(tr '[:lower:]' '[:upper:]' <<< "$ENVIRONMENT")  ($SCHEME / $CONFIG)"

DD="$ROOT/iosApp/build/DD"

# ── 1. Shared Kotlin framework ────────────────────────────────────────────
#
# Off by default because it is the slow step (~8 min) and most iterations are
# Swift-only. But note the trap it guards: xcodebuild links a PREBUILT
# XCFramework, so when Kotlin has changed and this is skipped, the build still
# succeeds and still ships the OLD Kotlin. A green build proves nothing about
# whether your Kotlin change is in the binary.
if [[ $BUILD_KOTLIN -eq 1 ]]; then
    echo "▸ Assembling debug XCFramework (slow)…"
    ./gradlew :composeApp:assembleComposeAppDebugXCFramework -q
    # Compose resources are NOT inside the XCFramework; the app's postBuildScript
    # copies them from here. Without them every Res.drawable/Res.font read
    # falls back to drawn/system versions (it no longer crashes, but the real
    # logo and Inter Tight silently disappear).
    ./gradlew :composeApp:assembleIosArm64MainResources -q
fi

if [[ ! -d "$ROOT/composeApp/build/XCFrameworks/debug/composeApp.xcframework" ]]; then
    echo "error: no debug XCFramework — run once with --kotlin" >&2
    exit 1
fi

# ── 2. Regenerate the Xcode project ───────────────────────────────────────
# Idempotent, and cheap. Running it unconditionally means a project.yml or
# xcconfig change can never be half-applied.
echo "▸ Regenerating Xcode project…"
( cd iosApp && ./xcodegen.sh >/dev/null )

# ── 3. Resolve SPM, and apply the two checkout workarounds ────────────────
# Both are documented in docs/IOS_BUILD_AND_HANDOFF.md §2 and both are
# per-checkout, so they cannot be committed:
#   • nanopb ships a Bazel `BUILD` file that collides with the `build/` dir
#     Xcode wants on a case-insensitive filesystem.
#   • Firebase writes generated module maps into its own read-only checkout.
echo "▸ Resolving packages…"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "$SCHEME" \
    -derivedDataPath "$DD" -resolvePackageDependencies >/dev/null
if [[ -d "$DD/SourcePackages/checkouts" ]]; then
    chmod -R u+w "$DD/SourcePackages/checkouts"
    find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete
fi

# ── 4. Find the device ────────────────────────────────────────────────────
#
# Always the connected iPhone, never the simulator: the widget, App Group,
# push and background tasks all behave differently or not at all there.
#
# `devicectl`, NOT `xctrace list devices` — which this script used to use, and
# which reported this phone *offline* while devicectl reported it
# `available (paired)`. devicectl is also the tool doing the install and launch
# below, so asking it directly means discovery cannot disagree with execution.
#
# JSON, not the text table: the table is column-aligned rather than delimited,
# device names contain spaces and typographic apostrophes ("Nick’s iPhone"),
# and a paired Apple Watch sits in the same list — all three break a
# grep/sed parse in ways that look like "no device found".
#
# The UDID is what is used downstream, not devicectl's own `identifier` UUID:
# devicectl accepts either, but `xcodebuild -destination id=…` wants the UDID.
DEVICE_JSON="$(mktemp -t stationly-devices).json"
xcrun devicectl list devices --json-output "$DEVICE_JSON" >/dev/null 2>&1 || true
DEVICE_ID="$(python3 - "$DEVICE_JSON" <<'PY'
import json, sys
try:
    devices = json.load(open(sys.argv[1]))["result"]["devices"]
except Exception:
    sys.exit(0)
for d in devices:
    hw = d.get("hardwareProperties", {})
    conn = d.get("connectionProperties", {})
    if hw.get("platform") != "iOS" or hw.get("deviceType") != "iPhone":
        continue
    if conn.get("pairingState") != "paired":
        continue
    print(hw.get("udid", ""))
    break
PY
)"
rm -f "$DEVICE_JSON"
if [[ -z "$DEVICE_ID" ]]; then
    echo "error: no paired iPhone found." >&2
    echo "  Plug it in, unlock it, and trust this Mac. Then check it appears in:" >&2
    echo "    xcrun devicectl list devices" >&2
    exit 1
fi
echo "▸ Device: $DEVICE_ID"

# ── 5. Build ──────────────────────────────────────────────────────────────
#
# NOTE the braces. `$SCHEME…` — a variable butted straight against the "…" —
# is read by /bin/bash 3.2 (what macOS ships) as a variable NAMED
# `SCHEME<the ellipsis bytes>`, because its identifier scan walks bytes and
# treats the high bytes of a UTF-8 character as name characters. Under
# `set -u` that is an immediate "unbound variable" abort. Keep the braces on
# any `$VAR` followed by a non-ASCII character in these scripts.
echo "▸ Building ${SCHEME}…"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "$SCHEME" -configuration "$CONFIG" \
    -destination "id=$DEVICE_ID" -derivedDataPath "$DD" -allowProvisioningUpdates build \
    | grep -E "Kotlin XCFramework:|Firebase config:|warning: .*PLACEHOLDER|error:|BUILD SUCCEEDED|BUILD FAILED" \
    || true

APP="$DD/Build/Products/$CONFIG-iphoneos/iosApp.app"
[[ -d "$APP" ]] || { echo "error: build produced no app at $APP" >&2; exit 1; }

# Read the identifiers out of the BUILT app. Never hardcode them here — they
# now differ per environment, and a stale literal would install one app and
# launch (or dump the container of) another.
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")"
APP_GROUP="$(/usr/libexec/PlistBuddy -c 'Print :StationlyAppGroup' "$APP/Info.plist")"

# ── 6. Install ────────────────────────────────────────────────────────────
echo "▸ Installing ${BUNDLE_ID}…"   # braces required — see the note in step 5
xcrun devicectl device install app --device "$DEVICE_ID" "$APP" >/dev/null
echo "  installed."

# ── 7. Launch ─────────────────────────────────────────────────────────────
#
# ⚠️ A CLI-launched app CANNOT READ THE KEYCHAIN. FirebaseAuth will report "no
# user" on a device that is signed in, which looks exactly like a broken
# session. Anything touching auth must be verified by opening the app BY HAND
# on the phone. This launch is for getting to a screen quickly, not for
# testing sign-in state.
if [[ $LAUNCH -eq 1 ]]; then
    echo "▸ Launching (note: keychain is unavailable to CLI launches — open by hand to test auth)"
    xcrun devicectl device process launch --terminate-existing \
        --device "$DEVICE_ID" "$BUNDLE_ID" >/dev/null
fi

# ── 8. Pull the App Group container ───────────────────────────────────────
#
# The diagnostic of last resort. `log stream` cannot target a device from
# recent macOS and devicectl's --console does not capture print() from a
# Compose/KMP process, so the ring buffers written into the App Group are how
# on-device behaviour is actually observed.
if [[ $PULL_GROUP -eq 1 ]]; then
    OUT="$(mktemp -d)"
    echo "▸ Pulling $APP_GROUP …"
    xcrun devicectl device copy from --device "$DEVICE_ID" \
        --domain-type appGroupDataContainer --domain-identifier "$APP_GROUP" \
        --source / --destination "$OUT" >/dev/null
    plutil -p "$OUT/Library/Preferences/$APP_GROUP.plist"
fi

echo "✓ done ($ENVIRONMENT)"
