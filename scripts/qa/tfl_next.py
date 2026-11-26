#!/usr/bin/env python3
"""What TfL itself says is next at a stop, to hold the board up against.

    python3 -I scripts/qa/tfl_next.py 940GZZLUEPG                 # Epping, Central
    python3 -I scripts/qa/tfl_next.py 490010928W --line 73         # a bus stop, one route
    python3 -I scripts/qa/tfl_next.py 910GHGHI --rail              # Overground / Elizabeth

Prints one row per vehicle: line, platform, destination, minutes. Run it within
a few seconds of reading the board (or of `shot`), and compare row by row. See
docs/RELEASE_REGRESSION.md §D for what counts as a match.

`--rail` reads ArrivalDepartures, which is what the Syncer uses for Overground
and Elizabeth line: it carries departure times and the Cancelled status that the
plain Arrivals feed leaves out. A cancelled train is printed with CANCELLED and
must NOT be on the board.

Public TfL endpoints, no key needed for occasional use. Nothing about the user
or the phone is sent.
"""
import argparse
import json
import sys
import urllib.request
from datetime import datetime, timezone

BASE = "https://api.tfl.gov.uk/StopPoint/"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "stationly-qa"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def minutes_until(iso):
    t = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return (t - datetime.now(timezone.utc)).total_seconds() / 60


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("naptan")
    ap.add_argument("--line", help="only this line id, e.g. central, 73, mildmay")
    ap.add_argument("--rail", action="store_true", help="Overground / Elizabeth: ArrivalDepartures")
    a = ap.parse_args()

    rows = []
    if a.rail:
        # ArrivalDepartures refuses a line it does not cover (400), so only the
        # Overground and Elizabeth line groups at this stop are asked about.
        groups = get(BASE + a.naptan).get("lineModeGroups", [])
        lines = a.line or ",".join(l for g in groups if g.get("modeName") in ("overground", "elizabeth-line")
                                   for l in g.get("lineIdentifier", []))
        for d in get(f"{BASE}{a.naptan}/ArrivalDepartures?lineIds={lines}"):
            when = d.get("estimatedTimeOfDeparture") or d.get("scheduledTimeOfDeparture")
            if not when:
                continue
            status = d.get("departureStatus", "")
            rows.append((d.get("lineName", "") or lines, d.get("platformName", ""),
                         d.get("destinationName", ""), minutes_until(when),
                         "CANCELLED" if status == "Cancelled" else status))
    else:
        for d in get(f"{BASE}{a.naptan}/Arrivals"):
            if a.line and d.get("lineId") != a.line.lower():
                continue
            rows.append((d.get("lineName", ""), d.get("platformName", ""),
                         d.get("destinationName", ""), d.get("timeToStation", 0) / 60, ""))

    rows.sort(key=lambda r: (r[0], r[1], r[3]))
    print(f"TfL at {datetime.now().strftime('%H:%M:%S')} for {a.naptan}")
    for line, plat, dest, mins, note in rows:
        if mins < -1:
            continue
        shown = "Due" if mins < 1 else f"{int(mins)} min"
        print(f"  {line:<12} {plat[:28]:<28} {dest[:34]:<34} {shown:>7}  ({mins:5.1f}) {note}")
    if not rows:
        print("  nothing: the board should show its empty or closed message")


if __name__ == "__main__":
    sys.exit(main())
