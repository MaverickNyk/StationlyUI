# Blank-screen / ghosted-navigation bug — root cause & fix

_Branch `dev_25Apr`. Investigated 2026-05-31 from an on-device repro
(adb-attached) where rapidly opening and closing the Profile screen
eventually left the app on a **blank screen** (only the staging banner
visible)._

## Symptom

Open Profile → back to Home → open Profile → back … a few times, and the
app lands on a blank screen showing **only** the "⚠ STAGING ENVIRONMENT"
banner. Not a crash — no FATAL/AndroidRuntime in any buffer, and
`MainActivity` stays focused and resumed.

## Root cause (proven from device logs): MainActivity had no launchMode

`MainActivity` is **both** the LAUNCHER entry and the `stationly://`
deep-link target, but the manifest set **no `android:launchMode`**, so it
defaulted to **`standard`**. Consequences observed live:

```
ActivityManager: Killing 12172:com.stationly.mobile (adj 905): remove task
ActivityManager: Start proc 12757 ... for next-top-activity {MainActivity}
```
and the activity stack held **multiple MainActivity instances in different
tasks at once**: `t5239`, `t5250`, `t5251`.

With `standard` launchMode, every relaunch (a deep link, or the OS killing
the cached/background process at `adj 905` with **`remove task`** and then
restoring) creates a **fresh task + fresh MainActivity** instead of resuming
the existing one. Those tasks pile up. When the user returns they land on a
freshly-restored instance whose **Compose NavHost back stack didn't survive**
the task removal → the NavHost renders nothing. The staging banner is a
**sibling of the NavHost** in MainActivity's `Box`, so it still draws — which
is exactly the "blank except the banner" signature.

### Proof it's the NavHost, not the Activity
On the blank screen, `uiautomator dump` showed a live `ComposeView`
containing only the banner `TextView`; `dumpsys window` showed
`mCurrentFocus = …/MainActivity` (alive). So: activity healthy, NavHost
back stack empty.

## Fix

`AndroidManifest.xml` — `MainActivity`:

```xml
android:launchMode="singleTask"
```

`singleTask` guarantees **one** instance in **one** task. Every launch
(icon, deep link) is routed into the existing instance via `onNewIntent`
(already implemented in `MainActivity`), so tasks can't pile up and the nav
state is never duplicated or orphaned by a task removal.

## Secondary, unrelated alignment (kept, but NOT the cause)

While investigating, the navigation-compose version was bumped to match
Compose 1.7 — see `nav_compose_version_lockstep` memory. It is a legitimate
fix in its own right (nav 2.7.5 on Compose 1.7 has stuck transitions) and
was kept, but **it did NOT fix this blank screen** — the launchMode change
did. `android/app/build.gradle.kts`:

| Dependency | Was | Now |
|---|---|---|
| `androidx.navigation:navigation-compose` | 2.7.5 | 2.8.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.6.2 | 2.8.4 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.6.2 | 2.8.4 |

## Lessons

1. **A launcher activity that also handles deep links must be `singleTask`
   (or `singleInstance`)** — never the default `standard`. Otherwise tasks
   pile up and restore-after-kill produces orphaned/blank instances.
2. **"Blank but the banner shows" = NavHost back stack empty, activity
   alive.** Check the task stack (`dumpsys activity activities`) before
   blaming Compose Navigation. The banner being a NavHost *sibling* is the
   tell.
3. Build/install the staging variant with `:android:app:installStagingDebug`
   (prod/staging flavors exist — plain `installDebug` is ambiguous).
