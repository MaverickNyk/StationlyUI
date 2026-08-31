# The widget guide, an SDUI screen with pictures

How the in-app "Widgets" screen is served, what the backend has to return, and
why it is shaped the way it is.

**Audience:** whoever writes the backend half, and whoever records the demos.
**Last updated:** 2026-08-31. **Branch:** `ios-parity`.
**Companion docs:** `docs/SDUI.md` (the config channel and its ground rules),
`docs/IOS_WIDGET_DESIGN.md` (the widget itself).

---

## 1. Why the screen exists at all

iOS has **no API that adds a widget**. There is no counterpart to Android's
`requestPinAppWidget`, and no public URL that opens the widget gallery. An app
can only ever explain the gesture.

That fact already killed one surface here. A "add a home screen widget" promo
card shipped on the home screen and was removed on 2026-08-23
(`docs/SESSION_2026-08-23_IOS_POLISH.md` §3): with no button to offer, it could
only recite Home Screen instructions at somebody who had not asked, and a banner
that asks without being asked is an advert.

The removal note drew the line itself. The right home for this is *"an ordinary
in-app screen, reachable from home settings like every other setting"*. That is
what this is. **Do not turn it back into a banner.**

## 2. The thing everybody trips over first

The app deploys to **iOS 16**. The widget extension deploys to **iOS 26**
(`iosApp/project.yml`, forced by the unconditional `.pushHandler`, see
`StationlyWidgetBundle.swift`).

So on any device below iOS 26 the widget is not merely missing from the app, it
is **missing from the system widget gallery**, and a guide that walks that user
through the gesture sends them hunting for something that was never installed.

Every "add a widget" block in the payload is therefore gated on
`widget.supported`, and one card is gated on its complement to explain the
absence. The floor lives in the client as `SduiFacts.WIDGET_MIN_IOS`, not in the
payload: it changes when the extension's deployment target changes and at no
other time, which fails test 1 in `docs/SDUI.md` §3.

## 3. What loads, in order

```
open Home settings → "Widgets"
   │
   ├─ 1. WidgetGuideDefaults.screen      compiled in, paints immediately
   ├─ 2. WidgetGuideCache.load()         last good payload, from NSUserDefaults
   └─ 3. GET /sdui/app/widget-guide           replaces both, and re-seeds the cache
```

Each step overwrites the last and any step may be the final one. A cold offline
install shows the compiled guide in full words with no pictures; every later
visit shows whatever the backend last said.

The network answer is **discarded if `components` is empty**. A 200 with nothing
in it is a deploy in progress, not a decision to delete the guide.

## 4. The payload

`GET /sdui/app/widget-guide` returns the ordinary `SduiAppScreen` shape, the same one
`/sdui/app/about` returns. Three component types are new.

### `demo`: a picture or a looping recording

```json
{
  "type": "demo",
  "id": "demo_add",
  "frames": ["https://…/add/frame_001.webp", "https://…/add/frame_002.webp"],
  "frameMs": 125,
  "loop": true,
  "aspectRatio": 0.462,
  "caption": "Adding the board to your Home Screen",
  "condition": { "dependsOn": "widget.supported", "operator": "not_empty" }
}
```

**Frames, not a `.gif`.** Coil decodes animated GIFs on Android only; `coil-gif`
ships no decoder for iOS, so a `.gif` URL renders there as a frozen first frame.
Animating a real GIF on iOS would mean bridging ImageIO from Kotlin/Native to
assemble an animated `UIImage`, a platform detour for a container format that
compresses worse than the frames inside it.

Authoring is unchanged by that: record a GIF or a screen recording, then

```
scripts/demo_frames.py add_widget.mov --out web/static/guide/add \
    --base-url https://stationly.app/guide/add --fps 8
```

which writes the WebP frames, measures the aspect ratio off the source, and
prints the JSON block above ready to paste.

- `url` alone → a still image. Works everywhere, no frames needed.
- `url` **and** `frames` → the still is the poster shown while the strip warms.
- `aspectRatio` is width ÷ height and **must be right**. The client reserves the
  box at that ratio before the first byte arrives; a wrong value makes the whole
  screen jump when the image lands. The script measures it for you.
- Keep a demo under ~1.5 MB across all its frames. It is a help screen.

### `steps`: a numbered walkthrough

```json
{
  "type": "steps",
  "id": "add_steps",
  "title": "Add one",
  "steps": [
    { "title": "Touch and hold the Home Screen",
      "body": "Hold any empty part of the screen until the icons jiggle.",
      "frames": ["…"], "frameMs": 125, "aspectRatio": 0.462 }
  ],
  "condition": { "dependsOn": "widget.supported", "operator": "not_empty" }
}
```

Each step may carry its own media, on the same contract as `demo`. The numbering
is the content: this gesture is performed on the Home Screen with the app closed,
so it has to be memorised in order before the reader leaves.

### `stat_row`: the reader's own state

```json
{
  "type": "stat_row",
  "id": "widget_count",
  "fact": "widget.count",
  "zero": "No widgets on your Home Screen yet",
  "one":  "1 widget on your Home Screen",
  "many": "{count} widgets on your Home Screen"
}
```

The client picks the template and substitutes `{count}`; the backend keeps the
wording and the grammar. This row is what stops the screen reading as an advert:
it opens by saying what the reader already has, not by asking for something.

## 5. Conditions, and the facts behind them

`SduiCondition` is the type the auth forms already use. It resolves against a
flat string map, and the caller decides what fills it: form inputs on the auth
screens, **device facts** here (`SduiFacts`).

| Fact | Example | Notes |
|---|---|---|
| `platform` | `ios` | |
| `os.version` | `26.0` | number only, no OS name |
| `os.major` | `26` | what a `gte` gate compares against |
| `widget.supported` | `yes` / `` (blank) | §2. Blank rather than `"no"`, so `empty` reads naturally |
| `widget.count` | `2` | widget **instances**, not boards |
| `board.count` | `4` | boards the user has saved |

Operators: `not_empty`, `empty`, `equals`, `not_equals`, `gte`, `lte`. The two
numeric ones compare as numbers and are **false when either side is not a
number**. A string comparison would read `"9"` as newer than `"26"`.

**A fact the client does not publish hides its block.** A missing key fails
`not_empty`, `equals`, `gte` and `lte`. That direction is deliberate: a client
that cannot say what OS it is on must not be walked through a gesture that will
find an empty gallery.

A card or section whose children are all hidden is dropped with them. An empty
titled card is a heading over nothing, which reads as a screen that failed.

### `widget.count` is the honest count

It comes from `UserSettings.widgetTotal`, threaded up from `HomeStateProbe`'s
raw descriptor list. It is **not** derived from `UserSettings.widgets`, which is
keyed by board with `distinct()` families: two medium widgets on the same
station collapse to one entry there. That collapse is right for the question
that map answers ("is this board on the Home Screen") and wrong for a count.

## 6. What the guide should say

The sections in `WidgetGuideDefaults` are the floor, and the backend is expected
to improve on all of them. Two are load-bearing and should not be dropped:

- **Stacking.** One widget shows one station, so a two-ended commute is two
  widgets, dragged on top of each other into a stack. Nobody works this out
  alone, and it is the single highest-value paragraph on the screen.
- **Freshness.** iOS decides how often a widget may fetch, not the app; between
  fetches the board counts down on its own. Saying this up front pre-empts the
  standard widget complaint before it becomes a review.

Worth keeping: the widget is already **interactive** (tap the platform name to
page through platforms, via `WidgetPageIntent`), which shipped and which nobody
knows about.

## 7. File map

| File | Role |
|---|---|
| `ui/widgets/WidgetGuideScreen.kt` | The screen. Cache → network, conditions resolved once per visit |
| `ui/widgets/WidgetGuideDefaults.kt` | The compiled guide. `docs/SDUI.md` §3 test 3 |
| `ui/widgets/WidgetGuideCache.kt` | Last good payload on disk |
| `ui/sdui/SduiFacts.kt` | The device facts, and `WIDGET_MIN_IOS` |
| `ui/sdui/SduiConditions.kt` | The one condition evaluator, and `visibleFor` |
| `ui/sdui/SduiDemoMedia.kt` | The frame player |
| `ui/sdui/SduiRenderer.kt` | `demo` / `steps` / `stat_row` renderers |
| `scripts/demo_frames.py` | Recording → frames → JSON |
| `core/model/sdui/SduiAppModels.kt` | The three new component types |
| `core/service/SduiApiService.kt` | `getWidgetGuideLayout()` |

## 8. Status

**Deployed to staging 2026-08-31.** `https://staging-api.stationly.co.uk/api/v1/sdui/app/widget-guide`
serves all ten components, and `getBaseUrl()` rewrites the media URL to the
staging host, so the board image resolves to
`https://staging-api.stationly.co.uk/assets/widget_guide_medium.png` (200,
61 KB). Backend suite 207/207. The app was built against the
`iosApp Staging` scheme and installed on the iPhone 11
(`com.stationly.mobile.staging`).

Still outstanding:

- **The two recordings.** Adding a widget, and dragging two into a stack.
  Nothing is recorded yet, so both `demo` blocks in the served payload carry a
  still (the board screenshot) rather than frames. Use `scripts/demo_frames.py`.
- **Eyes on the screen.** Installed is not verified. Nobody has yet opened
  Home settings → Widgets on the device and looked at it.
- **Production.** Staging only. The endpoint and the asset both need a prod
  deploy before this ships.

## 9. A `demo` with no media renders nothing

`SduiDemoMedia` returns before drawing anything when a block has neither `url`
nor `frames`. That case is the compiled guide's ordinary state: it declares two
`demo` blocks with no media, because the recordings are backend assets rather
than megabytes in the app bundle, and without the early return each one would
paint an empty grey rectangle at its declared aspect ratio.

The reserved painted box is still correct for a demo that is LOADING: it holds
the layout at the right ratio so nothing jumps when the image lands, and painted
grey reads as loading where an unpainted gap reads as a fault. It is only ever
skipped when there is nothing coming.
