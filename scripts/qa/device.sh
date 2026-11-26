# Stationly release regression: phone helpers.
#
#   source scripts/qa/device.sh            # zsh or bash
#   ANDROID_SERIAL=<serial> source ...     # when more than one phone is plugged in
#
# What docs/RELEASE_REGRESSION.md drives the phone with. Every helper that taps,
# swipes or types goes through `need`, which refuses unless Stationly is the app
# in front: a tap that lands after the app has closed hits whatever is
# underneath, which on a personal phone is somebody's home screen.
#
# Screenshots and dumps go to $QA_OUT (default ./qa-out, gitignored). Delete them
# after the run: they show personal phones.
#
# Before driving a phone someone else is holding, run `idle` (see §0 of the doc).

PK=${PK:-com.stationly.mobile}
QA_OUT=${QA_OUT:-./qa-out}
mkdir -p "$QA_OUT"

# ── Reading the screen ──────────────────────────────────────────────────────

# Raw uiautomator XML. Empty on a screen whose clocks never stop ticking (v1's
# home screen, the launcher with a clock widget): use `shot` there instead.
ui() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb exec-out cat /sdcard/u.xml; adb shell rm -f /sdcard/u.xml; }

# Every text and content description on screen, joined by |, first N chars.
txt() { ui | python3 -I -c "
import sys,re,html
out=[]
for n in re.findall(r'<node [^>]*>',sys.stdin.read()):
    t=html.unescape(re.search(r' text=\"([^\"]*)\"',n).group(1)); c=html.unescape(re.search(r'content-desc=\"([^\"]*)\"',n).group(1))
    if t: out.append(t)
    elif c: out.append('['+c+']')
s='|'.join(out); s=re.sub(r'(?:(?<=\|)|^)((?:[^|]\|){4,}[^|])(?=\||$)',lambda m:m.group(1).replace('|',''),s); print(s[:int(sys.argv[1])])" "${1:-600}"; }

# Centre of the Kth element whose text or description matches a regex.
at() { ui | python3 -I -c "
import sys,re,html
pat=sys.argv[1]; k=int(sys.argv[2])
m=[n for n in re.findall(r'<node [^>]*>',sys.stdin.read()) if re.search(pat,html.unescape(re.search(r' text=\"([^\"]*)\"',n).group(1))) or re.search(pat,html.unescape(re.search(r'content-desc=\"([^\"]*)\"',n).group(1)))]
if len(m)>k:
    a=list(map(int,re.findall(r'\d+',re.search(r'bounds=\"([^\"]+)',m[k]).group(1)))); print((a[0]+a[2])//2,(a[1]+a[3])//2)" "$1" "${2:-0}"; }

shot() { adb exec-out screencap -p > "$QA_OUT/$1.png"; }
foc() { adb shell dumpsys window | grep -m1 mCurrentFocus | sed -E 's/.* ([^ ]+)\}/\1/'; }
cr() { echo "crashes=$(adb logcat -d -b crash | grep -c "Process: $PK") focus=$(foc)"; }

# Milliseconds since anyone touched the phone. Wait for minutes, not seconds.
idle() { adb shell dumpsys power | grep -m1 lastUserActivityTime= | sed -E 's/.*\(([0-9]+) ms ago\).*/\1/'; }

# ── Guards ──────────────────────────────────────────────────────────────────

inapp() { foc | grep -q "$PK/"; }
need() { inapp || { echo "STOP: not in Stationly ($(foc))"; return 1; }; }

# ── Acting (all guarded) ────────────────────────────────────────────────────

tap() { local b; b=$(at "$1" "${2:-0}"); if [ -n "$b" ]; then adb shell input tap $(printf %s "$b"); else echo "NOT FOUND: $1"; return 1; fi; }
T() { need && tap "$@"; }
SW() { need && adb shell input swipe "$1" "$2" "$3" "$4" "${5:-250}"; }
back() { adb shell input keyevent 4; sleep "${1:-2}"; }
B() { need && back "${1:-2}"; }
open() { adb shell am start -n "$PK/.MainActivity" >/dev/null 2>&1; sleep "${1:-6}"; }
typ() { need && adb shell input text "$(printf %s "$1" | sed 's/ /%s/g')"; }

# ── Stationly flows (v2 screens) ────────────────────────────────────────────

# Back to the first home page. Dismisses "Station Limit Reached" on the way.
home1() {
  for k in 1 2 3; do
    txt 300 | grep -q "^Station Limit" && { T "^Got it" || return 1; sleep 2; }
    txt 900 | grep -q "Add station" && break
    B 2 || return 1
  done
  for k in 1 2 3 4; do SW 150 1500 1130 1500 180 || return 1; sleep 1; done; sleep 1
}

# Carousel: page forward until the station named by regex $1 is in front.
find_() { home1 || return 1; for k in 1 2 3 4 5; do txt 60 | grep -q "^$1" && return 0; SW 1130 1500 150 1500 180; sleep 2; done; return 1; }

# addm MODE_REGEX "search text" STATION_REGEX   → lands on the direction step.
# Every step chained: nothing is typed unless the search field took the tap.
addm() { home1 && T "^Add station" && sleep 4 && txt 900 | grep -q "How are you travelling" && T "$1" && sleep 5 && T "^Search (stations|stops)" && sleep 1 && typ "$2" && sleep 5 && adb shell input keyevent 111 && sleep 1 && T "$3" && sleep 5; }

# On the direction step: save, and wait for home.
save() { T "^Set Up My Board" || return 1; for w in 1 2 3 4 5 6 7 8 9 10; do sleep 2; txt 900 | grep -q "Add station" && { echo "saved, home after ~$((w*2))s"; return 0; }; done; echo "not home after 20s"; return 1; }

# del STATION_REGEX → deletes it through its settings screen.
del() {
  find_ "$1" || { echo "no $1 page"; return 1; }
  T "^$1.* settings$" || return 1; sleep 3
  SW 640 2300 640 700 300; sleep 1; SW 640 2300 640 700 300; sleep 2
  T "^Delete this station" || return 1; sleep 2
  txt 60 | grep -q "^Delete $1" || { echo "wrong dialog"; return 1; }
  T "^Delete$" || return 1
  for k in 1 2 3 4 5 6 7 8 9 10; do sleep 1; txt 900 | grep -q "Add station" && { echo "deleted, home after ~${k}s"; return 0; }; done
  echo "still not home"; return 1
}

# Station names in the order the home screen shows them (list layout).
stations() { need || return 1; for k in 1 2 3 4 5 6; do SW 640 700 640 2300 250; done; local a=""; for k in 1 2 3 4 5 6 7 8; do a="$a
$(txt 2000 | tr '|' '\n' | grep -E ' settings\]$' | sed -E 's/^\[(.*) settings\]$/\1/')"; SW 640 2200 640 1000 400; sleep 1; done; echo "$a" | awk 'NF && !s[$0]++'; }

# Widgets the launcher holds for Stationly, with their ids.
widgets() { adb shell dumpsys appwidget | grep -B2 "componentName=ComponentInfo{$PK" | grep -E '^ *\[[0-9]+\] id=[0-9]+' | sed -E 's/.*id=([0-9]+).*/\1/' | sort -u | tr '\n' ' '; echo; }
