# Selection flow (Android) — SDUI copy + direction card

How `android/app/.../ui/selection/SelectionScreen.kt` renders the board-setup
flow, and exactly which strings/data the backend drives. Pairs with the backend
`docs/ROUTE_DIRECTIONS.md` (the route/direction data) and `DATA_CACHE_ARCHITECTURE.md`.

## The flow (one unified screen, 4 steps)
`Mode → Station → Line → Direction`. Step is derived from which keys are present
in `selections` (`computeStep`/`screenIdx`). Back pops one selection at a time.
A `StepHeader` carries the chosen **mode icon** forward on the later steps so the
user always sees what they picked.

## Copy is server-driven (SDUI)
Every user-facing string is `layout?.sdText(key, vars) ?: <offline fallback>`.
`sdText` interpolates `{...}` tokens from the live selection, so the backend can
ship templates like "Find a {mode} {stop}". Tokens the client provides:

| token | value |
|---|---|
| `{mode}` | mode label (Bus, Tube, …) |
| `{stop}` | "stop" (bus/tram) / "station" |
| `{station}` | chosen station name |
| `{line}` | chosen line name |
| `{lines}` / `{line_noun}` | "Lines"/"Routes" · "line"/"route" |
| `{vehicle}` | "Trains" / "Buses" / "Trams" / "Boats" |

### SDUI keys (shipped by `getSelectionLayout()` in the backend)
- **Mode:** `screen_mode_title` ("How are you travelling?"), `screen_mode_subtitle`.
- **Station:** `screen_station_title` ("Find a {mode} {stop}"), `screen_station_subtitle`.
- **Line:** `screen_line_title` ("{lines} from {station}"), `screen_line_subtitle`.
- **Direction:** `screen_direction_title` ("Which direction?"), `screen_direction_subtitle` ("{vehicle} from {station}").
- **Direction-card chrome:** `dir_towards_label` ("towards"), `dir_stations_label`
  ("STATIONS THIS WAY"), `dir_stations_to_label` ("STATIONS TO {dest}"),
  `dir_split_hint`.

The client strings are **offline fallbacks only**; whenever the layout supplies a
key it wins. (The old "Pick your chariot." came from the deployed backend, not the
app — changing it required the backend, not an APK.)

## Direction card (`DirCard`) — design
Per `feedback`/design decisions:
- **Compass** (rail only) = a small, muted tag — a confirmation cue, not the
  headline. Buses get none. Mapping is backend-owned (`getCompassDirection`);
  see ROUTE_DIRECTIONS.md.
- **`towards {…}`** = the bold, highlighted headline (18sp). Content:
  selected branch's next stop → next common stop → at a junction, the destination
  list ("towards Wimbledon, Richmond & 2 more").
- **Destination chips** = reachable termini; **tappable when >1** — tapping swaps
  the timeline to that branch (`destination.upcomingStations`), tap again → common.
- **Timeline** = the common trunk by default ("STATIONS THIS WAY"), or the selected
  branch ("STATIONS TO {DEST}"). A "routes split" note sits below when it branches
  and nothing's selected. (Full data model: backend ROUTE_DIRECTIONS.md.)

## Station step — location handling
- Permission granted → nearby stations (mode-filtered), recents shown first.
- Search bar always present (debounced).
- **No location / GPS unavailable** → currently shows a **search prompt**
  ("Search for a {stop} · Location unavailable — type to find stops").

### Known gap
The no-location state shows a search prompt, **not a "popular stops" list** that
was discussed. Implementing popular-stops needs a backend source (popular stations
per mode); not built yet.

## Caching
`/modes` and the SDUI layout are cached stale-while-revalidate (`SduiCache` /
`cached_app_layout`); dropdown data (`/lines`, `/stations/search`) cached in prefs.
Instant first paint, refresh in background.
