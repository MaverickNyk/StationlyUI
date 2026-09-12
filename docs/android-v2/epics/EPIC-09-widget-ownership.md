# EPIC-09 — The widget is the app's to run

**Raised by the owner, 2026-09-12, after walking S017's work on the phone.**

Android lets an app enumerate, bind and configure its own placed widgets. iOS
cannot do any of it. S017 used half of that and left the other half looking like
an iOS port: an empty manager screen, settings that silently edited the in-app
board, a pager bar that does not line up with the rows under it, and no sense of
where the widget went after you added one.

This epic finishes the job. It is scoped by one sentence: **the widget is a
thing the app owns, and the app should act like it.**

---

## What is NOT possible, verified before planning

**An app cannot remove a placed widget.** `AppWidgetHost.deleteAppWidgetId`
affects only widgets the CALLER hosts; placed widgets belong to the launcher's
host and no API exposes deletion to anyone else. Any story that says "remove
from the app" is unbuildable, so none does. AV2-9.2 covers the nearest honest
thing: say where the widget is and how to remove it, rather than offering a
button that cannot work.

**An app cannot scroll the launcher to a page.** There is no API. AV2-9.3 does
the achievable half: leave the app so the user is looking at their home screen
when the widget appears, rather than at the screen they added it from.

---

## The dependency graph, and what it says about order

```
        ┌──────────────────────────────────────────┐
        │  9.1  widget settings stop editing the   │   the architectural one:
        │       user's board   (M)                 │   everything about what a
        └───────────────┬──────────────────────────┘   widget SHOWS depends on
                        │                              where its settings live
             ┌──────────┴──────────┐
             ▼                     ▼
    ┌──────────────────┐  ┌──────────────────┐
    │ 9.2 the manager  │  │ 9.3 adding ends  │
    │     earns its    │  │     on the home  │
    │     place  (M)   │  │     screen  (S)  │
    └──────────────────┘  └──────────────────┘

    ── independent of 9.1, and of each other ──

    ┌──────────────────┐       ┌──────────────────┐   ┌──────────────────┐
    │ 9.4 pager bar    │──────▶│ 9.5 motion  (M)  │   │ 9.6 copy    (S)  │
    │     belongs (S)  │       └──────────────────┘   └──────────────────┘
    └──────────────────┘                              ┌──────────────────┐
                                                      │ 9.7 model    (S) │
                                                      └──────────────────┘
```

**What the graph is for.** 9.1 is the only story anything else waits on, and it
is the only one that can make the other six wrong if it lands late: 9.2 renders
settings, so it has to know whose settings they are. Everything on the bottom
row touches different files and can run at once.

**9.4 before 9.5** because animating a control that is misaligned just animates
the misalignment.

**9.6 last among the independents** only because it sweeps strings the others
write. Its test lands first so the others cannot reintroduce what it removes.

---

## AV2-9.1 — Widget settings stop editing the user's board · `M`

**The bug.** `WidgetConfigureActivity` writes `rowsPerPlatform`, `pin` and
`platformNav` through `UserSettings.update(groupingId)`, which is the SAME
`BoardConfig` the station's own settings screen edits and the home screen
renders from. Changing how a widget looks silently changes the card in the app.

The owner's rule: **widget settings are the widget's alone.**

- **T1** `WidgetSettings` — a per-`appWidgetId` record (depth, pin, nav), stored
  beside the binding in `widget_prefs`. Pure model + store, tested.
- **T2** `DepartureWidgetProvider` reads it instead of `UserSettings.configOf`.
- **T3** The configure screen writes it instead of `UserSettings.update`.
- **T4** Migration: a widget with no record of its own inherits its board's
  CURRENT values once, so nobody's existing widget changes shape on upgrade.
- **T5** Copy: the caption that says settings "apply everywhere" is now false.

**Done when** changing a widget's depth leaves the in-app board untouched, and
the reverse, proven on the device by reading both stores.

---

## AV2-9.2 — The manager earns its place · `M`

**The bug.** With no widgets it says "No widgets on your Home Screen yet." and
stops. That is a dead end on the one screen that exists to get you out of it.

- **T1** Empty state that teaches: what a widget is, what it will look like, the
  two ways to add one, and which of them this launcher supports.
- **T2** A real preview of the widget rather than a description of one.
- **T3** Every entry point the platform allows, in one place: add, choose
  station, edit settings, and find an existing one.
- **T4** Removal, stated honestly (see above): where it is and the gesture, not
  a button that cannot work.
- **T5** Layout that holds at 320dp, 400dp, a foldable's inner screen and a
  tablet. Width-capped and centred is not enough on its own.

---

## AV2-9.3 — Adding a widget ends on the home screen · `S`

After `requestPinAppWidget` the app stays in front, so the widget the user just
added is behind the screen they added it from, and the flow ends with them
pressing back twice to see whether it worked.

- **T1** On a confirmed pin, finish and hand control to the launcher.
- **T2** Do NOT do this when the launcher refused the pin.
- **T3** Verify on the device that the home screen is what is on screen.

---

## AV2-9.4 — The pager bar belongs to the board · `S`

**Measured, not guessed.** The platform header insets 2dp, a departure row
insets 4dp, the pager bar insets 0 and uses 34dp tap targets. So it runs flush
to the edges while every row is inset, and stands taller than the header it
replaces.

- **T1** Match the header's inset and height exactly.
- **T2** Chevrons that read as part of a departure board, not as Material
  buttons dropped onto one.
- **T3** Keep the target tappable while the glyph is small: padding, not size.

---

## AV2-9.5 — Motion · `M`

The board is static. iOS moves when it refreshes and when it changes.

- **T1** Stepping platforms animates rather than cutting.
- **T2** A refresh reads as a refresh.
- **T3** Within RemoteViews' actual limits, which are severe: no Compose, no
  property animators driven by the app, only what a `RemoteViews` can carry.
  The limit is the design constraint, and the story says what was possible.

---

## AV2-9.6 — Copy · `S`

**Owner: no AI sloppiness, no em dashes, in any text the user reads.**

Five confirmed in S017's own new copy: the widget manager's intro, the scroll
caption, the "applies everywhere" caption, the no-boards line, and the
screensaver's explanation. The rule is user-facing strings only; the house
comment style is unchanged and stays.

- **T1** Sweep every user-facing string added in S016 and S017.
- **T2** A test that fails on an em dash in a user-facing string, so this cannot
  come back.

---

## AV2-9.7 — Confirm the widget work fits the post-iOS model · `S`

The user and station model changed during iOS development: `boards` on the
profile, `BoardConfig` on the board, `UserSelection` in SQL keyed by grouping id,
settings device-local per uid. The widget work was written against it but has
not been checked against it deliberately.

- **T1** Confirm the binding key is the grouping id everywhere, including bus
  hubs where pole and hub differ.
- **T2** Confirm a widget survives a board list reconcile that reorders or
  re-adds boards.
- **T3** Confirm sign-out and sign-in as another account does not leave one
  person's widget showing another's station.
