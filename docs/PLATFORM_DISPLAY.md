# Platform / Stop-letter Display — end-to-end contract

_How a departure row's **platform** label flows from TfL → backend → client across
every surface (home board, widget, all dreams, hero/next-train). Last reworked on
branch `dev_25Apr`._

## Principle: the platform label is **backend-owned**; the client displays it verbatim

There is **one** place each platform string is decided — the backend — and the client
never derives or rewrites it. To change the wording/format of any platform label
(e.g. "Stop C" → "Bay C", or the unassigned case), you change the **backend only**
(two files, kept in lockstep — see below) and redeploy. No app release needed.

The **ETA** is the only field the client computes (a live countdown from
`targetEpochMs`); see `PERFORMANCE.md` / the eta pipeline. Platform, destination, and
line are passthrough.

## The two backend producers (MUST stay in lockstep)

Both endpoints produce predictions and **must** format platform identically:

| Producer | File | Function |
|---|---|---|
| REST (initial/fallback) | `stationly-backend/src/utils/formatters.ts` | `formatPlatform(mode, platform)` |
| FCM live (the Syncer) | `StationlySyncer/.../service/DataTransformationService.java` | `getPresentablePlatform(mode, rawPlatform)` |

Output contract (identical in both):
- **Bus, stop assigned** → `"Stop C"` (uppercased letter)
- **Bus, no stop** → `""` (empty) ← *the fix; was the confusing "Stop not assigned"*
- **Rail/tube, platform present** → `"Platform 8"` / `"Platform 1 (Eastbound)"`
- **Rail/tube, no platform** → `"Platform not assigned"`
- Normalises TfL noise (`""`, `"null"`, `"unknown"`, `"platform unknown"`, `"no platform"`)
- Far-future unassigned rail (Overground/DLR/Elizabeth) is filtered out entirely
  (`isFarFutureUnassigned`) — see the platform-noise fix.

> ⚠️ **Lockstep invariant:** any change to one producer must be mirrored in the other,
> or live (FCM) and initial (REST) data will disagree.

## Client side — display only, no derivation

- **`core/util/SyncPredictionsUseCase`** — trusts the backend platform verbatim. It does
  **not** fill a blank platform from a sibling prediction (that old `knownPlatform`
  logic masked genuinely-unassigned bus stops). A stray legacy `"Unknown"` is mapped to
  `""`.
- **`core/util/GlobalBoardProcessor`** — groups rows by `platform` and uses it as the
  header label **verbatim** (the old client-side `"Stop $stopLetter"` relabel is gone —
  the backend already emits "Stop C"). `stopLetter` remains on the model but is not used
  for display.
- **`core/util/StationlyFormatters.platformHeaderText(linePrefix, platform)`** — the
  single helper every board surface uses to render the header:
  - `platform` non-blank → `"<linePrefix>: <platform>"` (e.g. `"Piccadilly: Platform 1"`)
  - **`platform` blank → just `"<linePrefix>"`** (keep the row, drop the `": "`) ← the
    unassigned-bus behaviour
  - `linePrefix` empty → just `platform`

## Where it's rendered (all use `platformHeaderText`, so they're consistent)

| Surface | File |
|---|---|
| Home board (SDUI + legacy header paths) | `ui/summary/components/Board.kt` |
| Widget (SDUI + legacy header paths) | `widget/DepartureWidgetProvider.kt` |
| All dreams (cluster + fullscreen + summary delegate here) | `dream/DreamBoard.kt` |

**Hero / next-train chip** (home `Board.kt` `NextDepartureRow`, dream
`DreamSummary`): a standalone platform chip that is **hidden when the platform is
blank** (`isNotBlank()` guard) — so an unassigned bus simply shows no chip (correct;
no "Line:" prefix applies to the hero).

## To change a platform label in future
1. Edit `formatPlatform` in `stationly-backend/src/utils/formatters.ts`.
2. Mirror the exact same change in `getPresentablePlatform` in the Syncer's
   `DataTransformationService.java`.
3. Redeploy both. The client picks it up with no release (it's pure passthrough).

Do **not** reintroduce client-side platform derivation (sibling-fill, `"Stop $stopLetter"`,
etc.) — it breaks the single-source-of-truth and the empty-unassigned behaviour.
