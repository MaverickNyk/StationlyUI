#!/bin/bash
# Put a STAGING DEBUG build into the state each support flow needs, so a person
# can walk through it by hand, and show what the app logged.
#
#   scripts/qa/support_flows.sh whatsnew   # next open shows the one-time What's new screen
#   scripts/qa/support_flows.sh ask        # next visit to home raises the support ask
#   scripts/qa/support_flows.sh events     # the support / What's new events this phone logged today
#   scripts/qa/support_flows.sh restore    # put the support record back as it was before `ask`
#
# ANDROID_SERIAL=<serial> picks the phone when more than one is plugged in.
#
# Works only on a debuggable build (staging debug), because it reads and writes
# the app's own storage with `run-as`. A Play install refuses, which is the
# point: nothing here can touch a real user's phone.
#
# `ask` makes this device look like a week-old regular who has not been asked
# recently: days of use set to at least 7, the quiet window and the last-ask
# time cleared. The server decides the rest (ask_on_open must be true for
# Android, support enabled, account not an active supporter). The record as it
# was is saved first, and `restore` puts it back byte for byte.
#
# See docs/SUPPORT_CONTRIBUTIONS.md §9 for what each flow should do.
set -euo pipefail

PK=com.stationly.mobile
PREFS=shared_prefs/stationly_durable_prefs.xml
SAVE="${TMPDIR:-/tmp}/stationly_support_prefs_backup.xml"

need_debug() {
  adb shell run-as $PK true 2>/dev/null || { echo "Not a debuggable Stationly build on this phone (run-as refused). Install the staging debug APK."; exit 1; }
}

write_prefs() {
  adb push "$1" /data/local/tmp/stationly_prefs.xml >/dev/null 2>&1
  adb shell "run-as $PK sh -c 'cat /data/local/tmp/stationly_prefs.xml > $PREFS'"
  adb shell rm -f /data/local/tmp/stationly_prefs.xml
}

case "${1:-}" in
  whatsnew)
    need_debug
    adb shell am force-stop $PK
    tmp=$(mktemp)
    adb exec-out run-as $PK cat $PREFS > "$tmp"
    if grep -q 'name="whats_new_v2_pending"' "$tmp"; then
      echo "Already pending."
    else
      python3 -I - "$tmp" <<'EOF2'
import sys
p = sys.argv[1]; s = open(p).read()
line = '    <string name="whats_new_v2_pending">pending</string>\n'
if '</map>' in s:
    s = s.replace('</map>', line + '</map>', 1)
elif '<map />' in s or '<map/>' in s:
    s = s.replace('<map />', '<map>\n' + line + '</map>').replace('<map/>', '<map>\n' + line + '</map>')
else:
    sys.exit("The app's prefs file is missing or unreadable. Open Stationly once, then try again.")
open(p, 'w').write(s)
EOF2
      write_prefs "$tmp"
      adb exec-out run-as $PK cat $PREFS | grep -q 'name="whats_new_v2_pending"' \
        || { echo "Could not write the marker."; exit 1; }
      echo "What's new is pending. Open Stationly: it shows once the home screen has boards."
    fi
    rm -f "$tmp"
    ;;
  ask)
    need_debug
    adb shell am force-stop $PK
    tmp=$(mktemp)
    adb exec-out run-as $PK cat $PREFS > "$tmp"
    [ -f "$SAVE" ] || cp "$tmp" "$SAVE"
    python3 -I - "$tmp" <<'EOF'
import sys, re, html, json
p = sys.argv[1]; s = open(p).read()
# The signed-in account's record, never the `_signed_out` slot.
m = next((x for x in re.finditer(r'(<string name="support_money_state_v3:([^"]*)">)([^<]*)(</string>)', s)
          if x.group(2) != '_signed_out'), None)
if not m:
    sys.exit("No support record for a signed-in account yet. Sign in and open the app once, then run this again.")
d = json.loads(html.unescape(m.group(3)))
d["quietUntilMs"] = 0
d["lastAskedMs"] = 0
d["activeDays"] = max(7, d.get("activeDays", 0))
s = s[:m.start(3)] + html.escape(json.dumps(d, separators=(",", ":")), quote=True) + s[m.end(3):]
open(p, "w").write(s)
EOF
    write_prefs "$tmp"
    rm -f "$tmp"
    echo "Ready: open Stationly and land on home. The banner should rise below the board."
    echo "Saved the previous record; run 'restore' when done."
    ;;
  restore)
    need_debug
    [ -f "$SAVE" ] || { echo "Nothing saved."; exit 0; }
    adb shell am force-stop $PK
    write_prefs "$SAVE"
    rm -f "$SAVE"
    echo "Support record restored."
    ;;
  events)
    need_debug
    d=$(mktemp -d)
    # -wal too: Android keeps recent rows there until a checkpoint.
    # Only files that exist: a "No such file" message copied into a -wal would
    # make sqlite3 read garbage.
    for f in stationly.db stationly.db-journal stationly.db-wal stationly.db-shm; do
      adb shell run-as $PK test -f databases/$f && adb exec-out run-as $PK cat databases/$f > "$d/$f"
    done
    sqlite3 "$d/stationly.db" "select time(t/1000,'unixepoch','localtime'), name, props from ActivityEventEntity
      where (name like 'support.%' or name like 'whats_new.%') and t > (strftime('%s','now','start of day'))*1000 order by t" \
      | sed 's/|/   /g'
    echo "(Only events not yet uploaded. The app uploads in batches, so older ones may already have gone to the server.)"
    rm -rf "$d"
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
