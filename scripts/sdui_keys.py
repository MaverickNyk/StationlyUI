#!/usr/bin/env python3
"""
The SDUI config-key inventory: who serves each key, and who reads it.

## Why this exists
The `/sdui/app/home-config` payload is a flat string map shared by three source
trees — the backend that sends it, the iOS client, and the frozen production
Android client. Nothing connects them at compile time, so every kind of drift is
silent: a key can be read and never sent, sent and never read, or renamed on one
side while the other keeps its compiled fallback forever. Both directions were
found in production by hand (see docs/SDUI_AUDIT.md), and one of them nearly led
to deleting 30 keys the live Android app was reading.

## The rule this enforces
    Adding a key to the payload is safe. Removing one is not.

An unread key costs bytes. A removed key that some shipped client still reads is
a blank string in production, and Android is frozen at versionCode 2 with no
rollback — so `--check` fails on a key that has DISAPPEARED from the payload,
and says nothing about keys that have merely been added.

## What it cannot know
Which keys the SHIPPED Android binary reads. It scans `android/`, and that tree
is ahead of the store build by an unknown number of commits. Treat the Android
column as a floor, never as a complete list, and never delete on its say-so.

Usage:
    scripts/sdui_keys.py            regenerate docs/CONFIG_KEYS.md
    scripts/sdui_keys.py --check    exit 1 if a recorded key is no longer served
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(os.path.dirname(ROOT), "stationly-backend")
INVENTORY = os.path.join(ROOT, "docs", "CONFIG_KEYS.md")

# A dotted key, as it appears as a map key on the backend or a lookup on a client.
KEY = r"[a-z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+"


def walk(root, exts):
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ("build", "node_modules", ".git", "dist")]
        for f in files:
            if f.endswith(exts):
                yield os.path.join(base, f)


def served():
    """
    Keys the backend actually serves.

    ASKS THE BACKEND rather than reading its source. `getHomeConfig()` spreads in
    keys that several services generate programmatically — `LineSeverityService`
    emits one display key per severity from a loop, `SupportMoneyConfigService`
    builds its own block — and none of those appear as a literal anywhere. A
    regex over the source reported 177 keys where the payload has 217, which is
    the same false-negative that made this tool call live keys droppable three
    times on the client side.

    Falls back to the regex when node cannot run, and SAYS so, because a silent
    downgrade to the worse method is exactly how the wrong number gets trusted.
    """
    src = os.path.join(BACKEND, "src")
    if not os.path.isdir(src):
        sys.exit(f"backend not found at {BACKEND} — clone it beside this repo")

    probe = (
        "const s = require('./src/services/sduiService');"
        "const c = (s.SduiService || s.default).getHomeConfig();"
        "console.log(JSON.stringify(Object.keys(c.strings || {})));"
    )
    try:
        out = subprocess.run(
            ["npx", "--no-install", "ts-node", "-e", probe],
            cwd=BACKEND, capture_output=True, text=True, timeout=180,
        )
        line = [l for l in out.stdout.splitlines() if l.startswith("[")]
        if line:
            return set(json.loads(line[-1]))
        print("warning: could not run the backend, falling back to a source scan "
              "(under-reports generated keys)", file=sys.stderr)
    except Exception as e:
        print(f"warning: backend probe failed ({e}); falling back to a source scan "
              "(under-reports generated keys)", file=sys.stderr)

    pat = re.compile(r"""['"](""" + KEY + r""")['"]\s*:""")
    keys = set()
    for p in walk(src, (".ts",)):
        keys |= set(pat.findall(open(p, encoding="utf-8").read()))
    return keys


# ── Detecting a client read ──────────────────────────────────────────────────
#
# Generous about the CALL SHAPE, strict about the NAMESPACE. The two failure
# directions are not symmetric:
#
#   false positive — a key is marked read when it is not. It becomes undeletable.
#                    Costs bytes. Harmless.
#   false negative — a live key looks unread. Someone deletes it, and a shipped
#                    client renders a blank string with no release to fix it.
#
# So this does not try to recognise every way a key can be read. An earlier
# version matched only `strings["k"]` and missed every key `AuthStrings` reaches
# through a helper — twenty live keys reported as droppable, which is precisely
# the failure this file exists to prevent. Instead: ANY dotted literal counts,
# provided its top-level segment is one the backend actually serves.
#
# That last clause is what keeps the report readable. Kotlin source is full of
# dotted strings that are not config — activity event names (`board.added`),
# Android intent actions, package ids, hostnames, filenames — and listing them as
# unserved keys would bury the real ones under permanent phantoms.
# Two shapes, because a client key is not always a whole literal:
#
#   "explore.title"                          a plain key
#   "widget.state.${state.lowercase()}.$part" a Kotlin template, one key per state
#
# The template form has to be matched too, and its punctuation ($ { } ( ) ) is
# not in `KEY`. Missing it is how this tool under-reported three times running —
# first keys read through a helper, then keys built from a template — each time
# marking live keys droppable. When in doubt, match more: see the asymmetry note
# above.
KEY_TEMPLATE = r"[a-z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_${}().]+)+"
LITERAL = re.compile(r'"(' + KEY_TEMPLATE + r')"')

# Test sources define fixture keys ("a.num") no payload will ever serve.
# `activity/` defines event names that collide with real namespaces by
# coincidence — `auth.logged_in`, `board.added` — and are not config.
SKIP_PATHS = ("commonTest", "androidTest", "iosTest", "jvmTest", "src/test", "/activity/")


def reads(root, namespaces):
    """Keys a client looks up in the config map, including computed families."""
    exact, families = set(), set()
    for p in walk(root, (".kt",)):
        if any(t in p for t in SKIP_PATHS):
            continue
        for k in LITERAL.findall(open(p, encoding="utf-8").read()):
            if k.split(".")[0] not in namespaces:
                continue
            if "$" in k:
                # "dream.settings.layout.${x}.name" — record the literal prefix,
                # which is what a served key has to start with to be reachable.
                families.add(k.split("$")[0].rstrip("."))
            else:
                exact.add(k)
    return exact, families


def reachable(key, exact, families):
    return key in exact or any(key.startswith(f + ".") for f in families)


def build():
    s = served()
    # The namespaces the payload actually uses. A client literal outside these is
    # something else that happens to have a dot in it.
    ns = {k.split(".")[0] for k in s}

    ios_x, ios_f = reads(os.path.join(ROOT, "composeApp", "src"), ns)
    ios_x2, ios_f2 = reads(os.path.join(ROOT, "core", "src"), ns)
    ios_x |= ios_x2
    ios_f |= ios_f2
    and_x, and_f = reads(os.path.join(ROOT, "android"), ns)

    rows = []
    for k in sorted(s | ios_x | and_x):
        rows.append((
            k,
            k in s,
            reachable(k, ios_x, ios_f),
            reachable(k, and_x, and_f),
        ))
    return rows


def render(rows):
    served_n = sum(1 for r in rows if r[1])
    ios_n = sum(1 for r in rows if r[2])
    and_n = sum(1 for r in rows if r[3])
    both = sum(1 for r in rows if r[1] and (r[2] or r[3]))
    unserved = [r[0] for r in rows if not r[1]]
    unread = [r[0] for r in rows if r[1] and not r[2] and not r[3]]

    out = [
        "# SDUI config keys",
        "",
        "Generated by `scripts/sdui_keys.py`. Do not edit by hand.",
        "",
        "**The payload is additive: adding a key is safe, removing one is not.**",
        "A key marked `android` is read by the production client and can never be",
        "dropped. The Android column is a floor, not a complete list — that tree is",
        "ahead of the shipped binary, which only the Play Console can tell you about.",
        "",
        f"- keys served: **{served_n}**",
        f"- read by iOS: **{ios_n}**",
        f"- read by Android: **{and_n}**",
        f"- served and read by at least one client: **{both}**",
        f"- read but never served (client keeps its fallback): **{len(unserved)}**",
        f"- served but read by neither (retained under the additive rule): **{len(unread)}**",
        "",
        "| Key | Served | iOS | Android |",
        "|---|---|---|---|",
    ]
    tick = lambda b: "yes" if b else "—"
    for k, sv, io, an in rows:
        out.append(f"| `{k}` | {tick(sv)} | {tick(io)} | {tick(an)} |")
    return "\n".join(out) + "\n"


def recorded_served():
    """
    Keys the COMMITTED inventory says are served — the baseline for --check.

    Read from git, not from the working tree. Reading the file on disk makes the
    check vacuous the moment anyone regenerates before running it: the tool then
    compares the new payload against a record it just wrote from that same
    payload, and reports "unchanged" no matter what was deleted.

    Returns None when there is no committed copy yet, which is a real state on
    the commit that introduces this file.
    """
    try:
        out = subprocess.run(
            ["git", "show", f"HEAD:docs/{os.path.basename(INVENTORY)}"],
            cwd=ROOT, capture_output=True, text=True, timeout=30,
        )
        if out.returncode != 0:
            return None
        text = out.stdout
    except Exception:
        return None

    keys = set()
    for line in text.splitlines():
        m = re.match(r"\|\s*`([^`]+)`\s*\|\s*yes\s*\|", line)
        if m:
            keys.add(m.group(1))
    return keys or None


def main():
    rows = build()
    if "--check" not in sys.argv:
        os.makedirs(os.path.dirname(INVENTORY), exist_ok=True)
        open(INVENTORY, "w", encoding="utf-8").write(render(rows))
        print(f"wrote {INVENTORY} ({len(rows)} keys)")
        return 0

    was = recorded_served()
    if was is None:
        print("no COMMITTED inventory to check against — nothing to compare yet")
        return 0
    now = {r[0] for r in rows if r[1]}
    gone = sorted(was - now)
    if gone:
        readers = {r[0]: (r[2], r[3]) for r in rows}
        print("FAIL: keys removed from the payload. The payload is additive.\n")
        for k in gone:
            io, an = readers.get(k, (False, False))
            who = ", ".join(c for c, f in (("iOS", io), ("Android", an)) if f) or "no scanned client"
            print(f"  {k}   (read by: {who})")
        print("\nRestore them. A shipped client reading a key you removed renders a blank")
        print("string, and Android is frozen — there is no release to fix it with.")
        return 1
    added = sorted(now - was)
    if added:
        print(f"ok: {len(added)} key(s) added, none removed")
    else:
        print("ok: payload unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
