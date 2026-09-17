# Staff review — branch-aware map and filter

Two strict review passes over the whole change set. **Everything below is fixed
and in the working tree**; nothing here is a proposal. The change itself is in
`ROUTE_BRANCHES_AND_REJOINS.md` — this is only what review found.

**246 tests, 0 failures.** `:core`, `:composeApp`, `:android:app` and the
backend all compile. Android untouched throughout.

---

## Correctness bugs found and fixed

### 1. The filter sheet lied about what it would save
`BoardFilterSheet` computed its live preview with `BoardFilterResolver.resolve(…)`
but **omitted `chosenPatternIds`**. Taking a branch resolved an empty via-stop
set, so the sheet announced *"Nothing matches. All trains will be shown."* — in
warning styling — for a filter about to save perfectly well. Preview and save
must be called with identical arguments.

### 2. Switching filter mode silently discarded branch picks
`setFilterMode` rebuilds the filter from scratch and did not carry `patterns`.
Touching the mode row after taking a branch threw it away with nothing on screen
to say so.

### 3. The board card called a branch filter "Filtered"
`BoardLabels.filterLabel` read `viaStationNames` alone. A board narrowed to a
whole service stores no via STOP, only a pattern, so the card fell through to a
bare "Filtered". Now names the service — and does NOT prefix it with "Via",
because *"Via Morden via Bank"* is not a sentence.

### 4. List columns were comma-joined and could desynchronise
`viaStationNames` and `destinations` hold stop NAMES, joined with `,` into one
TEXT column. One comma in a name splits an entry in two and shifts every later
name onto the wrong id in the list beside it — a silent mislabel, on columns that
are index-aligned by contract. Now a JSON array via `encodeList`/`decodeList`,
with a fallback that still reads the old form.

### 5. `patternsFrom` / `downstreamOf` read only the first occurrence of a stop
Both took `segments.firstOrNull { … }`. For a stop the route calls at twice —
Edgware Road on the Circle's spiral — that ignored the second call, so the
"one pick per branch" rule and the covered-branch highlight worked off half the
truth. Both now union across every occurrence.

### 6. Stream clients could have been served unstamped departures
`PredictionCache.set` stamps `viaKey` **in place** and `StationStreamHub.broadcast`
serialises the same object. It stores before it sends, so the frame is correct —
but nothing said so, and reordering those two lines would have shipped unstamped
departures to every streaming client while the cached copy had them. Documented
at the call site as load-bearing.

---

## Performance

All of it was work being redone on the render path.

| Fix | Was |
|---|---|
| `RouteGraph` memoised per (line, direction) in the ViewModel | `toggleFilterVia` rebuilt the **entire graph on every stop tap** — sort, topological order, segment, row-pack — then threw it away |
| `byId` + `segmentsByStop` built once, lazily | `downstreamOf` rebuilt an `associateBy` map on **every call**, once per selected stop, per recomposition |
| `positionOf` / `allStops` / `repeatedStops` use the index | Each re-sorted the segment list on every access. `segments` is already ordered by `startCol` |
| Terminus chips resolved once per graph (`ChipSpec`) | `graph.patterns.filter { … }` ran inside the render loop, per terminal segment, per recomposition |
| Source segments hoisted into `remember` | Filtered inside the **Canvas draw lambda**, so every scroll frame re-walked the segments |
| Sheet summary memoised | Built four strings on every recomposition of a sheet being actively tapped |

---

## Cleanliness

- **`shadeFor` deleted.** Once branches stopped being shaded it was a function
  taking an unused parameter and returning `lineColor`. The `tint` aliases went
  with it.
- **`StackedNode.sublabel` / `sublabelColor` deleted.** No caller passed them.
- **Chips moved out of the stop loop** into their own pass. A chip is a property
  of the *service*, not of a stop.
- **`viaKey` derivation removed from the prediction source.** It is stamped once
  at `PredictionCache.set`, which covers both producers. Two implementations of
  one rule drift — that is exactly how the Syncer bug happened.
- **Backend: `viaKeyOf(\`via ${run.via}\`)` → `canonicalToken(run.via)`.** A
  round trip through a regex for nothing.
- **`canonicalise` alias deleted**; callers use the exported name.
- **Migrations removed entirely** (see below).

---

## Invariants — breaking these is silent

1. **`segments` is ordered by `startCol`.** Everything downstream assumes it.
2. **`BoardFeed.admits` is a line-for-line mirror of `SqlStorage.matchesFilter`.**
   Change one, change the other. The widget and the board disagreeing is worse
   than either being wrong alone.
3. **`sanitiseBoards` is an ALLOW-LIST.** A filter field missing from it is
   dropped on every sync. Add new fields there as well as to the client model.
4. **`PredictionCache.set` runs before the stream send.** See bug 6.
5. **Preview and save call the resolver identically.** See bug 1.
6. **Fail open, everywhere.** Absent `viaKey`, empty token set, empty allow-list,
   blank `destId` all mean *show the train*.

---

## Migrations: deliberately absent

`migrations/` was deleted during active development. iOS has never shipped, so
there is no install to upgrade, and `Schema.create` applies the `.sq` in full to
every fresh database.

**Android is live (versionCode 2) and writes to `UserSelectionEntity` through the
same `insertSelection`.** `Schema.create` runs only on an EMPTY database, so an
existing Android install never gains a column added to the `.sq` — the first
board save fails with "no such column". A banner at the top of
`StationlyDatabase.sq` spells out what a reinstated `migrations/1.sqm` must
contain. **Reinstate it before the next Android release.**

---

## Test coverage

| Suite | Tests | Covers |
|---|---|---|
| `util.BranchFilterTest` | 17 | resolution, branch exclusion, divergence, unions, legacy fallback |
| `util.RouteGraphTest` | 11 | merges, columns, rows, revisits, empty payloads |
| `util.SelectionListEncodingTest` | 7 | lossless list columns, comma-in-name, legacy form, malformed input |
| `util.ViaKeyWireFormatTest` | 5 | absent / null / unknown token all fail open |
| `BoardFilterSummaryTest` | 8 | summary text, mode-switch state |
| `util.BoardLabelsTest` | +4 | branch-aware card labels |

`composeApp` had **no test source set at all**; one was added, because the filter
summary is assembled from two kinds of pick and shipped straight to the screen —
it once shipped with its template escaped rather than evaluated.

---

## Known limitation, deliberate

Mixing a stop pick with a branch pick whose service carries no TfL label drops
branch narrowing and fails open. A board stores ONE flat `(ids, tokens)` pair,
which cannot express a union of two clauses. Fixing it means a clause list, a new
column, and a change to the synced payload — not worth it for that combination.
Pinned by `mixing a stop and an unlabelled service fails open`.

---

## Open, needing a product call

- Vertical map orientation, like a carriage line diagram.
- Platform labels on the branches leaving the origin. Data verified clean at
  Euston and Kennington, noisy at Camden Town; blocked on the selection sheet
  having no live arrivals for the origin.
- `routeResolvedAt` is written but never read, so no saved filter is ever
  re-resolved. Pre-existing, and heavier now that `viaKeys` and `patternIds` are
  part of the resolution.
