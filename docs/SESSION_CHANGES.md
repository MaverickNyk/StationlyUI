# StationlyUI — Recent change notes (for future agents)

Three changes landed in this session. Each is small and self-contained.

## 1. Honest "X ago" timer — survives 0-row updates

**Problem:** the "updated X ago" timer on home / widget / dream was driven by
`SqlStorage.getPredictionsTimestamp` (the MAX timestamp of *prediction rows*).
A backend update that returned **0 rows** wrote no row → no timestamp → the
home screen showed a stale ">8 min ago" even while FCM kept arriving, and the
widget fell back to render-time "now" (dishonest). The timer's real meaning is
**"time since the last backend update for this board"**, and a 0-row update is
still an update.

**Fix:** a dedicated sync timestamp, independent of row count.

- New SQL table `SyncStatusEntity(stationId, lineId, lastSyncMs)` +
  `upsertSyncStatus` / `getSyncStatus` / `clearSyncStatuses`
  (`core/.../StationlyDatabase.sq`).
- `SyncPredictionsUseCase.execute` stamps the sync time **once at the top** —
  the instant a backend payload lands, before checking whether it has rows —
  and threads that **same** `syncMs` into `savePredictions`, so the sync stamp
  and the prediction rows agree to the millisecond.
- `SqlStorage.getLastUpdatedTimestamp()` = `getSyncTimestamp ?: getPredictionsTimestamp`
  (the fallback only matters for boards persisted before this existed).
- All three surfaces now read `getLastUpdatedTimestamp`:
  `SummaryViewModel` (home), `DepartureWidgetProvider` (widget), `DreamData`
  (dream). **Supersedes** the older `getPredictionsTimestamp` references in
  `widget/CLAUDE.md`.

**Note:** the 8-minute row-drop in `getPredictions` still uses the *row*
timestamp on purpose — that measures the age of the ETAs being displayed
("data staleness"), which is a different question from "when did we last hear
from the backend" ("connection staleness", driven by the sync timestamp and the
`BoardFallbackState` OFFLINE / SIGNAL_LOST kinds). Keep them on separate clocks.

## 2. Dream weather — always shows a temperature

`dream/WeatherStation.kt`:
- Falls back to **central London** (`51.5074, -0.1278`) when no device location
  is available, so the dream's weather chip never silently disappears.
- Accepts **coarse** location too (`ACCESS_COARSE_LOCATION`), not just fine —
  users who granted "Approximate" location now get their real local temp
  instead of the London fallback. (The fine-only check was why the chip "went
  invisible".)

## 3. Direction card shape no longer jumps on select (`ui/selection/SelectionScreen.kt`)

`DirCard`: the compass/tick header was gated on `compassIcon != null || sel`, so
for a **bus** card (no compass) the whole header + 8dp spacer only existed while
selected → ticking changed the card's height. Now the header depends **only on
the compass** (pinned to the tick height), and bus cards carry the tick on the
"towards …" headline row instead — so selecting/deselecting never changes the
card's shape.
