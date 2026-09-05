# Android v2 — session journal

Append-only. Newest first. One entry per session, written at close-out.
**Never delete or rewrite an entry** — a wrong turn recorded is worth more than a
tidy log, because the next agent would otherwise take it again.

Template:

```
## S0NN — YYYY-MM-DD — AV2-x.y "story title"
**Outcome:** DONE | PARTIAL | BLOCKED | GATE-FIX
**Gate:** GREEN | RED (which task)
**Commits:** <sha> <sha>
**Did:** one or two lines.
**Learned:** anything that changes the map. Move it into analysis/ if it does.
**Next agent needs to know:** the thing you would tell them in person.
```

---

## S000 — 2026-09-05 — project setup
**Outcome:** DONE
**Gate:** GREEN (`:core:testDebugUnitTest`, `:composeApp:testDebugUnitTest`,
`:android:app:compileStagingDebugKotlin`, `:composeApp:compileDebugKotlinAndroid`)
**Commits:** _(see branch head)_

**Did:** Cut `dev_android_bring_to_v2` from `ios-parity` @ `b7b7a1c`. Measured
the actual state of the repo rather than assuming it, took four decisions
(D1–D4), and built this workspace: gap analysis, migration plan, test strategy,
gap graph, and the board.

**Learned** — three measurements reframed the project:

1. `ios-parity` is a **strict superset** of `master` (117 ahead, 0 behind). There
   was never an Android branch to merge back.
2. `:android:app:compileStagingDebugKotlin` is **green** on `ios-parity`. The
   shipped app was kept compiling against the v2 core as it moved — 38 lines
   across 5 files is the whole of its adaptation.
3. `:composeApp:compileDebugKotlinAndroid` is **green**. The entire iOS v2 UI
   already builds for Android; its Android `actual`s exist and are deliberate
   placeholders, each one commented as a "build-verification surface only".

And one finding that changes a plan rather than the map: the hand-maintained
list in the `StationlyDatabase.sq` banner, of what an Android upgrade owes, is
**incomplete**. It names 7 items. The computed delta is 21 — it omits five
`UserSelectionEntity` columns, three `PredictionEntity` columns, and **both**
primary-key changes. Anyone writing the migration from that list would ship a
broken one. See `analysis/MIGRATION.md` §1.2.

Also recorded: `backup_rules.xml` includes `domain="database"`, so Android Auto
Backup can restore a **v1 database into a fresh v2 install**. The upgrade path is
a larger set than "devices that had v1 installed", and a test plan scoped to
in-place upgrades would miss it entirely.

**Next agent needs to know:** start at AV2-1.1. Do not start the migration
(EPIC-02) before the gate runs in CI — it is the one piece of work where a
regression reaches users who have been using the app the longest, and it is
invisible on every development device, all of which are fresh installs.
