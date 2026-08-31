# App update policy

How Stationly tells a user their build is too old, and how it stops one that
genuinely cannot be served.

## The short version

Nothing polls the App Store. The server decides, the client obeys, and there are
two separate statements with two separate surfaces:

| | `minimumVersion` | `recommendedVersion` |
|---|---|---|
| Meaning | the backend cannot serve this build | something newer exists |
| Surface | full screen, no way past it | small dialog, dismissible |
| Enforced by | HTTP 426 on every route, plus the client's own check | client only |
| Frequency | until they update | once per `nudgeIntervalDays`, per version |
| Expected in a user's lifetime | never | rarely |

Publishing a build to App Store Connect triggers none of this. The trigger is
editing `AppReleaseService.POLICY` and deploying.

## Why not check the App Store

The `itunes.apple.com/lookup` trick is a small-app pattern. It is
unauthenticated, rate-limited, tells you what is *published* rather than what is
*supported*, and it lies during a phased release. More importantly it answers
the wrong question: the gate is not about what is newest, it is about what this
backend can still talk to.

## Why the floor is about the backend, not about features

`minimumVersion` is raised when the server genuinely cannot serve a build any
more — a response shape changed, an auth scheme was retired, a client bug is
corrupting sync state. It is not raised because a release shipped something
nice. This is why large apps show a forced update to clients a year or more old
and never to last month's release, and why most users should never see this
screen once.

Nudging is the weaker claim and it is genuinely optional. iOS automatic updates
are on by default and carry the overwhelming majority of the base forward within
a couple of weeks, so the honest default for `recommendedVersion` is to match
`latestVersion` and say nothing at all.

## The parts

### Backend

| File | Role |
|---|---|
| `src/services/appReleaseService.ts` | the policy document, version comparison, verdict, and `assertSafe` |
| `src/middleware/versionGateMiddleware.ts` | 426 enforcement on every data route |
| `src/controllers/sduiController.ts` | `getReleasePolicy` |
| `src/routes/apiRoutes.ts` | mounts the gate, serves `GET /sdui/app/release-policy` |

### Client

| File | Role |
|---|---|
| `core/.../model/release/ReleasePolicy.kt` | wire model, `UpdateVerdict`, `StoreLink` |
| `core/.../config/ReleaseGate.kt` | the decision, the snooze state, `isVersionBelow` |
| `core/.../service/StationlyAuth.kt` | `X-Stationly-Client` on every request |
| `core/.../service/NetworkModule.kt` | `upgradeRequiredGuard` — the 426 handler |
| `composeApp/.../ui/update/UpdateSurfaces.kt` | the blocking screen and the nudge |
| `composeApp/.../App.kt` | mounts both at the root, fetches the policy |

## How a client is identified

Every request carries:

```
X-Stationly-Client: ios;1.2.0;47
                    │   │     └── CFBundleVersion / versionCode — diagnostic only
                    │   └──────── CFBundleShortVersionString / versionName — what is gated
                    └──────────── lower-case platform
```

On every request, not just the config fetch. A client that only identifies
itself when it asks for config can only be refused when it asks for config, and
the point of server enforcement is to refuse the routes it actually calls.

## Why the server enforces it at all

The client evaluates the same document, and for a healthy client that is enough.
It is not enough for the case the gate exists for:

1. **A client check is one screen.** The version this replaced ran in
   `SummaryViewModel` after a config fetch, so it could only fire on home. A
   launch into a board, a widget tap or a deep link never reached it.
2. **A client check can be cached away.** Offline, the client falls back to a
   cached document that predates the floor being raised.
3. **A client check asks the broken build to police itself.** The floor is
   raised precisely when an old build is doing something the server cannot cope
   with. Trusting it to evaluate a document correctly and stop is trusting the
   thing already concluded to be wrong.

So the hard gate rests on `426 Upgrade Required`, which reaches every route,
cannot come from a cache, and needs no cooperation.

**426 specifically, never 401 or 403.** `authExpiryGuard` signs users out on
some 401s. A gate that borrowed that status would start ending sessions as a
side effect of a build being old.

## The three lockout guards

A gate that blocks a user who cannot act on it is worse than no gate. Three
independent checks prevent it, deliberately redundant because none of them can
be verified in production without shipping the failure they prevent:

1. **`AppReleaseService.assertSafe()`**, run at module load. Refuses
   `minimumVersion > latestVersion` and `recommendedVersion > latestVersion`. A
   bad edit fails the deploy rather than the user's launch.
2. **`ReleaseGate.shouldBlock`** re-checks the same invariant on device, in case
   a document that fails it ever reaches one — a rollback to an older server, a
   hand-edited deploy, a cached document from either.
3. **`ReleaseGate.blockedOrOk`** drops the block entirely when there is no store
   URL to send the user to. The blocking screen is one button; showing it with a
   button that goes nowhere is the exact outcome the design exists to prevent.

### The phased-release trap

Apple rolls an automatic update out over 7 days. A `minimumVersion` set to a
build that has not finished rolling out tells a user "you must update" and then
hands them a store page with no Update button. They are locked out with no
action available.

**`latestVersion` means "finished rolling out and installable by anyone".** Keep
it lagging the newest submitted build until the rollout completes. Guard 1 then
makes it impossible to set a floor above it.

## Nudge rules

Four independent reasons not to show one, all in `ReleaseGate.shouldNudge`:

- installed version is at or above `recommendedVersion`
- inside `POST_INSTALL_GRACE_MS` (3 days) of first launch — a fresh install
  cannot act on "please update", and during a phased release the store may have
  handed them an older build through no choice of theirs
- inside `nudgeIntervalDays` of the last nudge shown (clamped to 1–365, so a
  backend typo of `0` cannot nudge on every evaluation)
- the user already dismissed a nudge for this version

That last one is the important one. **A dismissal is an answer about a version,
not a timer.** Re-asking the same question in a fortnight is how a nudge becomes
nagging. A newer `recommendedVersion` is a new question and does get asked.

Tapping Update is recorded the same way as a dismissal: the app cannot observe
whether the store visit ended in an install, and re-nudging someone who did
update is the one outcome that reads as broken.

## Turning it on

The gate ships dormant. Two independent switches, either of which being false
passes everything through:

| Switch | Where | Meaning |
|---|---|---|
| `VERSION_GATE_ENABLED` | backend env | is this server allowed to enforce |
| `ReleasePolicy.gateEnabled` | the document | is anything gated at all |

Two of them so there are two ways to recover from a mis-set floor and neither
needs a code change. The env var is the faster one.

### Before the first real gate

- [ ] Fill in the App Store numeric id in `appReleaseService.ts`
      (`itms-apps://apps.apple.com/app/id…`) and in the `app.ios.storeUrl`
      home-config key. Both are placeholder `id0000000000` today, which is why
      nothing is gated.
- [ ] Set `latestVersion` to a build whose phased rollout has **completed**.
- [ ] Set `VERSION_GATE_ENABLED=true` on staging and confirm a 426 against an
      old build before touching production.

## The legacy keys

`app.minVersion` and `app.storeUrl` in `/sdui/app/home-config` are read by the
Android binary already in the Play Store. Under the additive rule they can never
be removed or repurposed, so they stay frozen at the floor and Android keeps its
existing dialog. `app.storeUrl` remains a Play Store URL on purpose — new
clients read `storeUrl` off the release policy, and `app.ios.storeUrl` was added
alongside for anything in between.

Android's client-side gate is untouched. That tree is frozen at versionCode 2;
porting `ReleaseGate` to it is phase 2, alongside the rest of the SDUI work.

## Not built

**What's New after an update.** The opposite direction — release notes on first
launch of a new version — needs `last_seen_version` in storage and per-version
copy in config. It is a separate feature from the gate and was left out
deliberately rather than half-built.

## Tests

- Backend: `npm test` — 17 cases covering version comparison, header parsing,
  the verdict matrix, both `assertSafe` invariants, middleware dormancy, the
  exempt list, the 426 body, and the legacy keys still being served.
- Client: `./gradlew :core:testDebugUnitTest --tests "*ReleaseGateTest*"` — 21
  cases covering comparison parity with the backend, every branch that refuses a
  block, and all four nudge suppression rules.

Both suites weight the refusal cases heavily. A gate that never fires looks
exactly like a healthy fleet, and a gate that fires wrongly looks exactly like an
outage; neither is reproducible against a correct backend, which is why they are
asserted rather than tried.
