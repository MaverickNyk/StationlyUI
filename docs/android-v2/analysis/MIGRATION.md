# Android v2 — migration and compatibility

**Branch:** `dev_android_bring_to_v2`. **Frame:** [`GAP_ANALYSIS.md`](GAP_ANALYSIS.md) §5–§6.

Four things migrate, and they fail differently. In order of damage:

1. **The database** — fails as a crash on the longest-standing users. §1.
2. **Cloud user state** — fails as silently deleted boards. §2.
3. **Preferences** — fails as a reset arrangement. §3.
4. **FCM topics** — fails as a board that never updates. §4.

---

## 1. The database

### 1.1 The premise that expired

`StationlyDatabase.sq` opens with a banner removing migrations, resting on one
stated premise:

> **Android is live (versionCode 2) and is FROZEN** — no new Android release is
> planned, so nothing in this file can reach an Android user.

and it names its own expiry condition:

> If you find yourself writing one, check that premise first — it is the only
> thing this policy rests on.

Decision D1 falsifies the premise. Migrations come back. The banner must be
rewritten in the same commit as the migration, not left to contradict it.

### 1.2 The banner's hand-maintained list is INCOMPLETE. Do not use it as the spec.

The banner lists 7 items the upgrade "owes". The real delta, computed by
extracting column names from both revisions of the file, is **21**:

```
git show master:core/src/commonMain/sqldelight/com/stationly/db/StationlyDatabase.sq
git show HEAD:core/src/commonMain/sqldelight/com/stationly/db/StationlyDatabase.sq
```

| Table | v2 adds | In the banner? |
|---|---|---|
| `UserSelectionEntity` | `parentStationId` | **NO** |
| | `filterMode` | **NO** |
| | `viaStationId` | **NO** |
| | `viaStationName` | **NO** |
| | `routeResolvedAt` | **NO** |
| | `viaKeys` | yes |
| | `patternIds` | yes |
| | `patternNames` | yes |
| | `directionName` | yes |
| | `directionDestinations` | yes |
| | `directionTowards` | yes |
| `PredictionEntity` | `direction` | **NO** |
| | `destId` | **NO** |
| | `matchesFilter` | **NO** |
| | `viaKey` | yes |
| | **PK** `(stationId, lineId, destination, platform, eta)` → `(stationId, lineId, **direction**, destination, platform, eta)` | **NO** |
| | index `prediction_lookup` gains `direction` | **NO** |
| `SyncStatusEntity` | `direction` | **NO** |
| | **PK** `(stationId, lineId)` → `(stationId, lineId, **direction**)` | **NO** |
| `ActivityEventEntity` | whole table | yes |
| | index `activity_by_time` | yes |
| `LineStatusEntity` | unchanged | — |

**Finding:** a hand-maintained list drifted, exactly as the file warned that the
`.sqm` and the `.sq` would. The migration spec is the computed diff above, and
the fix for the class of problem is `verifyMigrations` (§1.6), not more diligence.

### 1.3 Why a table rebuild, not `ALTER TABLE`

Two primary keys change. SQLite cannot alter a primary key — the only way is
create-copy-drop-rename. The banner already reaches this conclusion for a
different reason (*"version 1" has meant several different schemas over time*)
and it is right on both counts. Rebuild every table that changes, including the
ones that would technically survive `ALTER`, so the result is identical whatever
the source database contained.

### 1.4 What is preserved and what is dropped

| Table | Action | Why |
|---|---|---|
| `UserSelectionEntity` | **REBUILD, copy all rows.** New columns take their `.sq` defaults: `parentStationId=''` (= "same as station", the correct reading of a pre-hub row), `filterMode='ALL'`, `viaStationId/Name=NULL`, `viaKeys/patternIds/patternNames/directionName/directionDestinations/directionTowards=''`, `routeResolvedAt=0`. | This is the user's boards. It is the only table whose loss the user would notice, and it is also the only one recoverable from the cloud — belt and braces. |
| `PredictionEntity` | **REBUILD, DROP all rows.** | Old rows have no `direction`, and `direction` is now in the primary key and in every board query's `WHERE`. Backfilling it by joining against `UserSelectionEntity` is guesswork on a bus hub, and the payoff is zero: departures older than ~2 minutes are filtered out on read anyway, and FCM or the first REST refresh repopulates the table within seconds of launch. Dropping is both safer and honest. |
| `SyncStatusEntity` | **REBUILD, DROP all rows.** | Same reason. The cost is one "X ago" reading "just now" after upgrade. |
| `LineStatusEntity` | Untouched. | Unchanged. |
| `ActivityEventEntity` | **CREATE.** | New. `Schema.create` never runs on an upgraded database, so without this the first enqueue is "no such table" — on the longest-standing installs and never on a dev device. |

### 1.5 Android Auto Backup widens the blast radius

`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` both carry:

```xml
<include domain="database" path="." />
```

So `stationly.db` travels through cloud backup and device-to-device transfer.
A user on v1 who buys a new phone and installs **v2 from Play as a fresh
install** still receives a **v1 database** during restore. `Schema.create` does
not run — the file is already there. Without the migration that device fails on
its first query having never had v1 installed on it.

The migration fixes this too. The point of recording it is that "upgrade path"
is a larger set than "devices that had v1 installed", and any test plan scoped to
in-place upgrades misses it.

### 1.6 `verifyMigrations`

`core/build.gradle.kts` documents why it is off: turning it on fails
`:core:build` with *"Verifying a migration requires a database file to be
present"*, and the `generate…Schema` task the error names is not registered by
SQLDelight 2.0.2 in this configuration. The note is explicit that closing it
properly means checking in a recorded schema baseline under
`sqldelight/databases/<version>.db`.

That is now worth doing, because §1.2 is the exact failure the flag prevents.
It is EPIC-02 work with its own verification, and it is the only durable answer
— the alternative is keeping two files identical by hand forever, which has
already failed once.

### 1.7 There is no rollback

A v1 APK installed over v2 opens a version-2 database with a version-1 schema.
`AndroidSqliteDriver` has no downgrade path and the framework throws. Play does
not serve downgrades, so this is a sideload-only scenario, but it means:

**Once v2 reaches a device, that device cannot go back.** A bad v2 is fixed by
shipping v2.0.1, never by rolling back. Plan the staged rollout accordingly (§5).

---

## 2. Cloud user state: `stations` → `boards`

### 2.1 The two lists

`users/{uid}` holds both, deliberately:

- `stations` — the v1 flat list, written by `/user/sync/stations`.
- `boards` — the v2 board list with filters, written by `/user/sync/boards`.

The subscription registry reads the **UNION**. Never merge them back into one
field — that decision is recorded and still holds.

### 2.2 The proven hazard

`syncStations` is a **full replace**: it diffs old ids against new to inc/dec
subscription counts. A one-element post therefore deletes every other board on
the account. This already happened — it is why `SelectionViewModel` on this
branch reads the full list out of SQLite before posting.

So during rollout, on an account with one v1 device and one v2 device:
a v1 save posts its (single) station and can prune the v2 device's work.

### 2.3 The rule for v2

**v2 dual-writes for the transition window.** On every board push, v2 posts:

1. `/user/sync/boards` with the authoritative `Board` list, and
2. `/user/sync/stations` with that same list **flattened** to
   `SubscribedStation` rows.

`SubscribedStation(id, parentStationId, name, line, mode, direction)` is exactly
`UserSelection`, and `Board.toSelections()` already produces it — so the
flattening is total, not lossy in the fields `stations` has. What the flat form
cannot carry is the filters, which is fine: a v1 device cannot render them.

This gives a v1 device on a shared account a **degraded but correct** view
instead of a stale or emptied one, and it keeps the registry's union consistent
from either side.

Drop the dual write when the v1 population falls below the threshold set in §5,
not before.

**Open verification (EPIC-04):** confirm on the backend that `syncStations` does
not touch `boards` and `syncBoards` does not touch `stations`. The plan above
depends on it and it has not been read.

### 2.4 First-run fold-up

On a v2 device's first launch after upgrade:

1. Read state. If `boards` is non-empty, it wins; ignore `stations`.
2. If `boards` is empty and `stations` is not, fold the flat rows into boards
   with `Board.from(...)`, grouping on `groupingId`, and push.
3. If both are empty, fall back to the local `UserSelectionEntity` rows the
   migration preserved (§1.4) and push those.

Step 3 is the one that matters for a user who has never signed in. `Board.isUsable`
already exists so an empty board list reads as ABSENT rather than as "delete
everything" — rely on it rather than adding a second guard.

---

## 3. Preferences

Three SharedPreferences files exist today:

| File | Owner | v2 disposition |
|---|---|---|
| `StationlyPrefs` | `AndroidStorageManager` — `selections`, `line_status_*`, `fcm_topics`, `subscribed_all_topic` | Keep. Same keys, same meanings. |
| `stationly_durable_prefs` | `saveDurable`/`loadDurable` — survives the logout wipe | Keep. `UserSettings` writes here, uid-namespaced. |
| `widget_prefs` | `last_refresh_ms` debounce | Extended in EPIC-05 to hold the per-`appWidgetId` station binding. |

v1's home/appearance state lives in `HomeConfigStore` and `ThemeRepository`
(`com.stationly.mobile.util` / `.ui.theme`). v2's equivalents are `UserSettings`
(uid-namespaced, durable) and `composeApp`'s `ThemeRepository`. **A one-shot
translation on first v2 launch** should carry theme choice across; the rest of
the arrangement is per-account appearance state that defaults cleanly and is
recoverable by the user in seconds. Do not over-invest here.

⚠️ `UserSettings` prunes rows equal to the default, so never read a preference
straight off the map and never treat a default as an answer. Flipping a default
is a one-way change.

---

## 4. FCM topics

v1 and v2 emit the **same topic shapes** — `Station_{naptan}` and
`LineStatus_{mode}_{line}` — so there is no rename to migrate. Two differences:

1. **Who emits them.** v1 subscribes from `FirebaseAuthManager` and
   `SelectionViewModel`; v2 uses `StationLifecycleUseCase` in `commonMain`.
   Delete the v1 paths rather than leaving both running.
2. **How many.** A v2 user with several boards and several lines each subscribes
   to many more topics than a v1 user ever did.

`AndroidNotificationManager` already persists the subscribed set under
`StationlyPrefs.fcm_topics`. Diff against it on launch and subscribe only the
difference — on upgrade day every device would otherwise re-subscribe its whole
set at once (R6).

Keep `stationly_all`. It is how `audience: {type:"all"}` pushes fan out with no
Firestore reads, and it is guarded by `subscribed_all_topic` already.

---

## 5. Rollout

| Step | Gate |
|---|---|
| `versionCode 3`, `versionName 2.0` | Migration test suite green on a real v1 database fixture. |
| Internal testing track | Upgrade-in-place verified on a device carrying a genuine v1 install. |
| Closed testing | Backup-restore path verified (§1.5): v1 backup restored onto a fresh v2 install. |
| Staged rollout, small first slice | Crash-free rate on the migration path. There is no rollback (§1.7). |
| Set `ReleasePolicy.android.minVersion` floor | Only after the staged rollout is healthy. This is what eventually retires R2. |
| Drop the `stations` dual write (§2.3) | v1 population below threshold, measured, not assumed. |

`ReleasePolicy` already carries an `android: PlatformRelease` alongside `ios`, so
the floor is a config change, not a code change.
