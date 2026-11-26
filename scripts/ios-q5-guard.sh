# Stationly iOS: refuse to build while the Q5 database bug is unfixed.
#
# Sourced by scripts/ios-testflight.sh and scripts/ios-dev.sh, after they `cd`
# to the repo root.
#
# THE BUG (docs/ANDROID_2.0_RELEASE_RECORD.md §12.1). iOS 1.0/1.1 databases say
# schema version 1 but already hold the newer tables. A build from this code
# runs migrations/1.sqm on them, which fails ("table ActivityEventEntity
# already exists"), and the database never opens again for that user. Android
# is unaffected. The owner deferred the fix on 2026-11-26 and merged the Android
# 2.0 branch into master with this guard in front of every iOS build.
#
# WHEN IT LIFTS. The fix (re-stamp an iOS version-1 database that already has
# the v2 tables as version 2, before the driver migrates) goes in
# core/src/iosMain/kotlin/platform/Database.ios.kt and must contain the marker
#     Q5_FIXED
# in a comment next to it. With the marker present this guard passes on its
# own; delete it and its two `source` lines when convenient.
#
# ESCAPE HATCH, for building the fix itself (simulator or a test phone with a
# copy of a 1.1 database), never for a release:
#     STATIONLY_IGNORE_Q5=1 scripts/ios-dev.sh staging
#
# Xcode run directly bypasses this. Don't archive iOS from Xcode until Q5 is
# fixed.

q5_guard() {
    local db="core/src/iosMain/kotlin/platform/Database.ios.kt"
    if [[ -f "$db" ]] && grep -q "Q5_FIXED" "$db"; then
        return 0
    fi
    if [[ "${STATIONLY_IGNORE_Q5:-}" == "1" ]]; then
        echo "⚠️  Q5 guard bypassed (STATIONLY_IGNORE_Q5=1). This build will break the database of" >&2
        echo "    every iPhone that has 1.0/1.1 data, unless it carries the fix. Never ship it." >&2
        return 0
    fi
    cat >&2 <<'MSG'
✗ Refusing to build iOS: the Q5 database bug is not fixed.

  An iPhone with Stationly 1.0/1.1 data would fail its database migration on
  this build and never open its data again. Read
  docs/ANDROID_2.0_RELEASE_RECORD.md §12.1 for the bug and the fix.

  To build the fix itself (not to ship):  STATIONLY_IGNORE_Q5=1 <same command>
MSG
    exit 3
}

q5_guard
