# Pending — branch-aware map and filter

Everything still open after the work committed on 2026-08-19
(`a5ee96a` in StationlyUI, `b105f69` in stationly-backend).

Background: `ROUTE_BRANCHES_AND_REJOINS.md` (what changed),
`REVIEW_2026-08-19_BRANCH_FILTER.md` (what review found),
`stationly-backend/docs/BRANCH_VIA_KEYS.md` (the `viaKey` contract).

---

## P0 — will break something if forgotten

### 1. Reinstate migrations before the next ANDROID release
**Blocking. Silent until it isn't.**

`core/src/commonMain/sqldelight/.../migrations/` was deleted during active
development. Fine for iOS, which has never shipped — but **Android is live
(versionCode 2)** and writes to `UserSelectionEntity` through the same
`SqlStorage.saveSelection` → `insertSelection`, which now names four columns
that did not exist in the shipped build.

`Schema.create` runs only on an EMPTY database, so an existing Android install
never gains them. The first board save fails with **"no such column"** — at
runtime, on the devices that have been using the app longest, and never on the
freshly wiped one a developer tests with.

The required SQL is in the banner at the top of `StationlyDatabase.sq`. **Diff
the `.sq` against the last released Android build before writing it** — that
list is only what was outstanding on the day migrations were dropped.

### 2. The Android app has not been touched at all
Deliberate — Android was explicitly held back for a later cycle. It compiles and
its behaviour is unchanged (all core changes are additive with defaults, and the
backend's `destinations[]` is byte-identical). But it does **not** benefit from
any of this: Android boards still filter on destination id alone and are still
wrong on the Northern and Central lines.

Whoever does that cycle needs: item 1 above, the `patterns[]` payload, and the
`viaKey` field. No backend work — it is all already deployed.

---

## P1 — unverified on real hardware

### 3. No board on the test device has ever exercised a branch filter
Every saved board has an empty `viaKeys`. The Camden Town board is **northbound**,
where the Bank / Charing Cross split is behind you and `viaKey` is correctly
null — so the branch half of the filter has never run on a device.

**To close:** Camden Town → Northern → **Southbound** → filter → Going through →
pick Bank → save. Expect every "via CX" train to vanish, including
*Kennington via CX*. Then confirm the row has `viaKeys = bank`:

```bash
sqlite3 stationly.db "select stationName,direction,viaKeys,patternIds \
  from UserSelectionEntity where filterMode<>'ALL';"
```

### 4. The widget's own refresh is untested
`WidgetRefreshService` never decoded `destId` and so never filtered; that is
fixed but unproven. Pushed boards were always filtered by KMP, so the path to
exercise is **the refresh button on the widget**, on a board with a live filter.

---

## P2 — known-wrong, accepted for now

### 5. Metropolitan cannot be narrowed by branch
Four patterns from Harrow-on-the-Hill all end at Aldgate and differ only in
whether they call at **Willesden Green**. Live arrivals say just
`towards: "Aldgate"`. Nothing to match on, so 12 origin/direction pairs fail
open. **Do not invent a discriminator for these.**

### 6. Circle draws Edgware Road twice with no "again" affordance
The Circle is a spiral, not a rejoin: it calls at Edgware Road on the way round
AND terminates there. Both calls are real and both are drawn, but nothing on the
map says the second is the same station later in the journey. 9 origins.

Narrow risk beyond the cosmetics: a train short-turning at the FIRST Edgware Road
would match a filter on a stop after it.

### 7. Piccadilly — "through Terminals 2 & 3" excludes T4 trains
TfL models the one-way T4 loop as *westbound ends at T4*, with the continuation
`T4 → T2&3 → east` living in the **outbound** sequence. Our data matches and the
filter is right under that model. But a T4 train does reach T2&3, two stops
later, going round the loop — so at Hatton Cross westbound those trains are
excluded from a "through T2&3" filter.

Splicing across directions would contradict the direction model everywhere else.
**Decide before touching it.**

### 8. A stop pick + an unlabelled branch pick over-admits
A board stores ONE flat `(destinationIds, viaKeys)` pair, which cannot express a
union of two different clauses. Mixing a stop with a branch whose service carries
no TfL label (Battersea) drops branch narrowing entirely and fails open. Fixing
it means a clause list, a new column and a change to the synced payload. Pinned
by `mixing a stop and an unlabelled service fails open`.

---

## P3 — pre-existing, now heavier

### 9. `routeResolvedAt` is written but never read
No saved filter is ever re-resolved. It exists so a stale allow-list can be
refreshed when branches change (engineering works, closures), and nothing
queries it.

This mattered less when the resolution was a list of destination ids. It matters
more now that `viaKeys` and `patternIds` are part of it: a board saved today
keeps its branch resolution indefinitely, even if TfL renames a via or reroutes
a branch.

### 10. Night Tube is invisible to the route model
`fetchSequences` hardcodes `serviceTypes: 'Regular'`, so a Night Tube
destination cannot appear on the map or in any allow-list built from it.

### 11. `verifyMigrations` is off, and `.sq`/`.sqm` drift silently
Pre-existing and documented in `core/build.gradle.kts`. Relevant again the moment
item 1 is done: the two are kept identical BY HAND and nothing checks them.

### 12. Route cache re-enriches once per line, lazily
`!routeData.sequenceVias` in the inline-enrich condition means every route cached
before the deploy re-fetches its sequences from TfL on first request. ~697 lines
including buses, so first-touch of a bus route is slow until it settles. Tube
lines are already warm.

Self-limiting and correct, but if the lazy storm is unwanted it could be a
background warm at boot instead. **No decision taken.**

---

## Open product questions (not code)

### 13. Vertical map orientation
The map is horizontal and scrolls sideways. Real carriage line diagrams are
vertical, which would look more familiar and read better on a phone. A
significant rewrite of the layout pass in `RouteGraphPicker`.

### 14. Platform labels on the branches leaving the origin
Verified feasible and verified partial:

| Station | Southbound | |
|---|---|---|
| Euston | Plat 2 = via CX · Plat 6 = via Bank | clean |
| Kennington | Plat 2 = via CX · Plat 4 = via Bank | clean |
| Camden Town | both branches on both platforms | **noisy** |

**Blocked:** TfL returns a platform only for the station you query, and the
selection sheet has no live arrivals for the origin. Downstream stops can never
be labelled. If built, show the label only when every observed arrival for that
branch agrees, and show nothing at Camden Town rather than something wrong.
