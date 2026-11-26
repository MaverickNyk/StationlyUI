# v1 golden fixtures

Captured 2026-09-05, from Android `versionCode 2` (`versionName 1.0`) — the
build that is live on real phones — at branch point `b7b7a1c`.

**These are characterization data, not aspirations.** They record what v1 *does*,
not what v2 *should* do. When v2 deliberately changes one, update it in the same
commit with a line in this file saying what changed and why. A fixture nobody is
allowed to change becomes a fixture everybody routes around.

They exist because AV2-3.5 deletes the v1 UI. After that, "what did v1 do here?"
is answerable only from git history.

| File | What it pins | Read by |
|---|---|---|
| `selections.json` | The `StationlyPrefs.selections` blob a v1 install holds | `V1GoldenTest` |
| `topics.json` | The FCM topic set a given board list produces, and the sharing rules | `V1GoldenTest` |
| `widget-state.json` | The `WidgetState` a given prediction set formats to | `V1GoldenTest` |
| `deeplinks.json` | The scheme/host → outcome table from the v1 manifest and `MainActivity.handleDeepLink` | `V1GoldenTest` |

## Changelog

_(no changes yet — captured at b7b7a1c)_
