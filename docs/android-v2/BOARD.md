# Android v2 — kanban board

**Protocol:** [`README.md`](README.md). **Detail:** [`epics/`](epics/). **Index:** [`BACKLOG.md`](BACKLOG.md).

---

## Live state

```
SPRINT:            2 — host cutover  (sprint 1 complete: EPIC-01 + EPIC-02)
WIP LIMIT:         1 story In Progress per agent, 2 across the project
LAST SESSION:      S010 (2026-09-06) — AV2-3.3 real actuals, batch B.
                   EPIC-03 is now code-complete except the cutover itself
GATE:              GREEN
                   :core:testDebugUnitTest                  PASS  (403 tests)
                   :core:verify…DatabaseMigration           PASS  (catches .sq/.sqm drift)
                   :composeApp:testDebugUnitTest            PASS  (58 tests, +6 asset naming)
                   :android:app:testStagingDebugUnitTest    PASS  (18 tests, +1 notif contract)
                   :android:app:compileStaging/ProdDebugKotlin  PASS
                   :composeApp:compileDebugKotlinAndroid    PASS
                   :composeApp:assembleComposeAppDebugXCFramework  PASS  (commonMain
                                                            CHANGED this session — see S009)
WORKING TREE:      clean
BLOCKED ON OWNER:  Q3 blocks EPIC-06 · Q4 keeps :android:app out of CI
                   Q5 iOS testers need telling before this reaches TestFlight
                   Q6 (NEW) both flavours share one applicationId, so they
                   cannot be installed side by side — AV2-3.4 criterion 3 is
                   unreachable, not merely unverified
                   AV2-3.1 in Review with ONE half failing, and not its fault:
                   v2-written state crashes v1's summary on launch. Read the
                   finding in the epic before signing anything off.
NEXT UP:           AV2-3.5 — THE CUTOVER — is now unblocked (3.3, 3.4 and 1.3
                   all done). It is the irreversible one: read its story and the
                   AV2-3.1 v1-crash finding together before starting.
```

---

## Board

Move a story by cutting its row and pasting it into the new column. Do not
duplicate rows. `Backlog → Ready` happens when every dependency is **Done**.

### 🅑 Backlog — dependencies not yet met

| Story | Epic | Title | Waiting on |
|---|---|---|---|
| AV2-4.1 | 04 | FCM at v2 | AV2-3.5 |
| AV2-4.2 | 04 | Topic lifecycle | AV2-4.1 |
| AV2-4.3 | 04 | Cloud state dual-write | AV2-4.2 |
| AV2-4.4 | 04 | Sessions and activity | AV2-4.3 |
| AV2-5.1 | 05 | Per-instance widget binding | AV2-4.3 |
| AV2-5.2 | 05 | In-app widget manager | AV2-5.1 |
| AV2-5.3 | 05 | Widget updates and placement | AV2-5.1 |
| AV2-5.4 | 05 | Widget guide | AV2-5.1, AV2-3.3 |
| AV2-6.1 | 06 | Real dream actuals | AV2-3.2, **Q3** |
| AV2-6.2 | 06 | Host the shared dream | AV2-6.1 |
| AV2-7.1 | 07 | The update gate | AV2-3.5 |
| AV2-7.2 | 07 | Config and quotas | AV2-3.5 |
| AV2-7.3 | 07 | Support, built and off | AV2-3.5 |
| AV2-8.1 | 08 | Release build integrity | all |
| AV2-8.2 | 08 | Upgrade verification on hardware | AV2-2.2, AV2-8.1 |
| AV2-8.3 | 08 | Ship | AV2-8.2 |

### 🅡 Ready — take the top one

| Story | Epic | Title | Size |
|---|---|---|---|
| AV2-3.5 | 03 | The cutover | L |

### 🅘 In Progress — WIP limit 2

| Story | Claimed by | Since | Note |
|---|---|---|---|
| _(none)_ | | | |

### 🅥 Review — done, awaiting owner sign-off

| Story | Session | What to look at |
|---|---|---|
| AV2-3.3 | S010 | Three stubs became real, both behavioural criteria **verified on the Pixel**: the POST_NOTIFICATIONS prompt fires on a fresh install, and nearby-station search returns stations on *Approximate* location, which v1 refuses. The thing to read is the finding: `AndroidAppContext` tracked the Activity lazily and so never saw the first resume, which silently disarmed that prompt entirely — one of the two prompts Android gives you a single chance at. Fixed with a content provider, staging-only. |
| AV2-3.4 | S009 | All three tasks done and the first two criteria **verified on hardware**: Google sign-in completes from the shared landing screen, and `stationly://` no longer resolves on a staging build while `stationly-staging://` does. Criterion 3 is unreachable (Q6). Two things to read rather than re-test: the **crash** found by backing out of a slow sign-in (fixed, in `commonMain` — one guard, nine call sites), and the still-visible **"Continue with Apple"** button on Android, deliberately left for AV2-3.5. |
| AV2-3.2 | S008 · device pass S009 | Both criteria now **verified on a Pixel 7 Pro** and the epic updated. One correction worth reading: there is no offline *banner* over a live board and there should not be — `computeBoardFallbackState` short-circuits on `hasPredictions`, so a board with cached departures keeps ticking. The offline surface is the cold-start "Can't reach servers", and it appeared. Device id held one value across a crash, force-stops, an update install and a sign-out. |
| AV2-3.1 | S007 · device pass S009 | **Half passes, half fails, and the failure is not this story's.** Two launcher icons; **Stationly v2** opens the shared UI, signs in and navigates. **Stationly Staging** (v1) now *crashes on launch* on any account that has opened v2 — duplicate LazyColumn key, because the v2 board model keeps one selection per direction and v1's keys by station+line. Read that finding before signing off; it is a trap set for AV2-8.2. Prod remains untouched and proven so at the dependency graph and the merged manifest. |

### 🅧 Blocked

| Story | Blocked by | Since |
|---|---|---|
| _(none)_ | | |

### 🅓 Done

| Story | Session | Date | Gate |
|---|---|---|---|
| AV2-1.1 | S001 | 2026-09-05 | GREEN |
| AV2-1.2 | S002 | 2026-09-05 | GREEN |
| AV2-1.3 | S003 | 2026-09-05 | GREEN |
| AV2-2.1 | S004 | 2026-09-05 | GREEN |
| AV2-2.2 | S005 | 2026-09-05 | GREEN |
| AV2-2.3 | S006 | 2026-09-05 | GREEN |

---

## Open questions for the owner

Do not block on these unless a story names one as a dependency. Log and continue.

| # | Question | Raised | Blocks | Status |
|---|---|---|---|---|
| Q1 | Play policy route for tips — Play Billing, or a charity/non-profit exemption for external checkout? | S000 | AV2-7.3 scope only (the surface ships off either way) | OPEN |
| Q2 | At what remaining-v1-install count do we drop the `stations` dual-write? | S000 | AV2-8.3 | OPEN |
| Q5 | **iOS TestFlight testers must delete and reinstall once** when the schema-version bump ships. Their databases are stamped version 1 but already hold the v2 schema, so `1.sqm` refuses to run on them (safely — see MIGRATION.md §1.4b). Standing iOS policy already says wipe-on-schema-change and boards restore from the cloud, but these are live testers. Tell them, or hold the bump until the next TestFlight build? | S004 | iOS TestFlight | OPEN |
| Q4 | Add `GOOGLE_SERVICES_STAGING_B64` as a repo secret so CI can compile and test `:android:app`? The file is gitignored, so CI skips those steps today and says so with a warning annotation. | S001 | `:android:app` coverage in CI | OPEN |
| Q3 | Does Daydream survive into v2? It is deprecated on newer Android, v1 ships it, and users may rely on it. Two sessions ride on the answer. | S000 | **EPIC-06 entirely** | OPEN |
| Q6 | Should staging get its own `applicationId` (e.g. `com.stationly.mobile.staging`)? Today both flavours are `com.stationly.mobile`, so staging and prod **cannot be installed side by side** — which is why AV2-3.4's third criterion is unreachable rather than unverified. Not a build-file change: the google-services plugin fails unless that exact package is registered as an Android app in the staging Firebase project, and Google sign-in additionally needs the (package, SHA-1) pair registered there. Owner-side console work. iOS already did this split (`com.stationly.mobile.staging`). | S009 | AV2-3.4 criterion 3 · AV2-8.2 side-by-side testing | OPEN |

---

## Sprint plan

Sprints are groupings for reporting; the dependency graph is what actually
orders the work.

| Sprint | Stories | Theme | Exit |
|---|---|---|---|
| 1 ✅ | AV2-1.1 → AV2-2.3 | Safety net + database | **Done.** A real v1 database migrates without loss, provably, on both schema shapes, and the build fails if the migration and the schema drift apart |
| 2 | AV2-3.1 → AV2-3.5 | Host cutover | The shared UI **is** the Android app |
| 3 | AV2-4.1 → AV2-4.4 | Data plane | FCM feeds the v2 board model; cloud state dual-writes |
| 4 | AV2-5.1 → AV2-5.4, AV2-6.1 → AV2-6.2 | Widget + dream | One widget per station, configurable in-app |
| 5 | AV2-7.1 → AV2-7.3 | Release surfaces | Update gate, quotas, support (off) |
| 6 | AV2-8.1 → AV2-8.3 | Rollout | `versionCode 3` on a staged rollout |

Epics 05, 06 and 07 are independent of one another and may be reordered or run
in parallel lanes. Epics 01 → 02 → 03 → 04 are strictly sequential: 02 protects
the users, 03 is the foundation 04 builds on.

---

## Burn-up

| Epic | Stories | Done | Points | Done |
|---|---|---|---|---|
| 01 Safety net | 3 | **3** ✅ | 9 | **9** |
| 02 Database | 3 | **3** ✅ | 11 | **11** |
| 03 Host cutover | 5 | 0 (4 in review) | 21 | 0 |
| 04 Data plane | 4 | 0 | 18 | 0 |
| 05 Widget | 4 | 0 | 16 | 0 |
| 06 Dream | 2 | 0 | 6 | 0 |
| 07 Release surfaces | 3 | 0 | 10 | 0 |
| 08 Rollout | 3 | 0 | 11 | 0 |
| **Total** | **27** | **6** | **102** | **20** |

Sizes: `S`=2, `M`=3, `L`=5, `XL`=8. One point is roughly one focused hour, so a
5h session is a `L` with room to close out, or an `XL` that will need two.
