# Accounts, devices, sessions and sync — the target design

_2026-08-24. Design only; no code changed. Spans `StationlyUI` (iOS/KMP, the
frozen Android app, the future web target) and `stationly-backend`. Written to
be implemented by a later session without re-deriving the reasoning._

**Revised the same day, after the storage decision changed.** The first draft
kept devices in a root collection and stored the session as `devices/{id}.uid`.
It justified that with a claim about signed-out devices which turned out to be
false in the code (§2), and once the product answer came back — a signed-out app
is an empty state, it receives nothing at all — the root collection had no
remaining argument. Devices now nest under the account and the row's existence
is the session. §2 and §3 are the affected sections; §4, §5, §9, §11, §12 and
§14 follow from them.

**This is the single design document for accounts, devices, sessions and sync.**
`SESSION_STATE_CONSOLIDATION.md` was folded into it and deleted; its client
audit survives as §8.1 and its backend assessment as §1–§5. One further file
remains beside this one on purpose: `HANDOVER_SESSION_SYNC.md`, the record of
what was actually built and how production must follow. This document is the
DESIGN; that one is the IMPLEMENTATION and it wins wherever they disagree.

Relation to the audits it was built from: it **absorbs** that consolidation doc
(2026-08-24, same day) and **supersedes its §4.3/§5 claim that
`users/{uid}.sessions` must stay a map on the user document** — §4 below says
why that changed. The audits it builds on:
`SESSION_AUDIT_2026-08-15_SESSION_STATE.md`, `SESSION_2026-08-12_SYNC_AND_IDENTITY.md`,
`SESSION_2026-08-23_AUTH_TOKEN_EXPIRY.md`, `USER_STATE_AND_ACTIVITY.md`,
`stationly-backend/docs/DEVICE_IDENTITY_AND_SESSIONS.md`,
`stationly-backend/docs/SESSIONS_AND_SUBSCRIPTIONS.md`,
`stationly-backend/docs/REPLICATION.md`.

---

## 0. Read this first

One account. Any number of devices, across Android, iOS and web. A change made
anywhere shows up everywhere else, fast, without burning Firestore reads to get
there. That is the whole brief, and this document is the complete shape of it.

The five decisions, so you know where this lands before the detail:

1. **A session is stored exactly once, as a row: `users/{uid}/devices/{deviceId}`,
   whose *existence* is the session.** Both the `sessions` map on the user
   document and the root `devices` collection are deleted. Signing in creates
   the row; signing out deletes it; there is no flag to get wrong and no second
   record to disagree with the first. The entire class of "three stores that
   disagree about which account a device belongs to" — the source of every bug
   in the August handovers — becomes unrepresentable, because there is one
   store. §2, §3, §4.
2. **Clients never touch Firestore.** Everything goes through the API, which is
   what makes the storage schema backend-private and lets us reshape it with
   zero client migration. This is already true and is now stated as an
   invariant. §2.
3. **Sync is three tiers: socket (foreground, instant, zero Firestore), silent
   push (background), and a revision check on foreground (zero Firestore reads
   when nothing changed).** All three carry the same monotonic `stateRev`, so a
   device fetches at most once per real change, and never for its own echo. §6.
4. **The read/write budget is enforced by design, not by hope.** A typical
   active user costs ~2–3 document reads and ~2 hot-document writes per day.
   The Syncer, auth validation and foreground reconciles cost zero, via the
   SQLite mirror pattern the backend already runs. §7.
5. **The frozen Android APK (versionCode 2) keeps working untouched.** Its wire
   contract is enumerated in §11 and nothing in this design changes a byte of
   it. Its FCM tokens stay in their legacy store, read on their own path; the
   union with device-row tokens described in §5 is not built until Android-next
   exists to need it (§12).

---

## 1. Requirements, restated precisely

**Must hold:**

- One account (Firebase Auth uid) usable from many devices simultaneously.
- Platforms: Android (shipped, **frozen**), iOS (unshipped, **the only client
  being built**), web (unshipped, parked). The design must accommodate all
  three; §12 builds only iOS. The frozen APK is untouched throughout, which is
  a constraint on the backend rather than work on a client.
- A board/profile change on one device reaches every other signed-in device:
  instantly when the other device is foregrounded, on next open otherwise.
- Signing in on a new device restores boards, lines, directions, filters,
  profile — everything in `USER_STATE_AND_ACTIVITY.md` §2b's "syncs" column.
- Signing out of one device leaves the others untouched; signing out of the
  last device releases the account's subscription holds; deleting the account
  signs out every device and removes every trace.
- Firestore reads and writes stay minimal — the backend's stated motto
  (`REPLICATION.md`: read from memory first, write only on real change).
- The subscription ref-count invariant survives everything: a station leaves
  the registry only at total count 0, and no failure mode may under-count.

**Non-goals (deliberate):**

- Appearance/settings sync — declined 2026-08-15; a slot is reserved (§13) so
  reversing the decision needs no redesign, but this design does not build it.
- Offline-first concurrent editing (CRDTs, op logs). Boards change a few times
  a session; §6.5 defines a merge policy that never loses an addition, and
  that is the right amount of machinery for this data.
- Multiple accounts signed in at once on one device.
- End-to-end encryption. Nothing stored is sensitive beyond an email address.

---

## 2. The model — three entities, three lifetimes

| Entity | Identity | Lifetime | Lives in |
|---|---|---|---|
| **Account** | Firebase uid | signup → deletion | `users/{uid}` |
| **Session** | the pair (account, device) | sign-in → sign-out / TTL | **`users/{uid}/devices/{deviceId}` — the row's existence IS the session** |
| **Subscription** | naptanId | derived | `metadata/subscribed_stations` + SQLite mirror |

A device used to be a fourth entity with a root collection of its own. It is
not one any more, and what removed it is a product fact rather than a modelling
preference: **a signed-out app is an empty state.** It shows no boards, its
widgets have no station to render, and it wants no pushes — decided
2026-08-24, and already what the app does at `ContentView.swift:54`, which
swaps the entire UI on `hasSession`. A device with no account therefore has
nothing to store and nobody to be told anything, so it gets no record. And
everything that used to describe a device — APNs environment, both APNs
tokens, the FCM token, model, app version, which stations and lines its widgets
watch — describes it *as used by one account*, so it belongs on that account's
row for it.

That is what collapses two records into one. The old shape had a device row and
a session, with the session as a field on the device (and before that, an entry
in a map on the user). Now the row and the session are the same object: created
at sign-in, deleted at sign-out, and while it exists it states both "this
account is signed in on this device" and "here is how to reach that device".
Signing in on a second account moves and overwrites nothing — it writes a row
under a different parent, and §4.1's steal is what removes the old one.

**Correcting the first draft, because the mistake is instructive.** That draft
argued the root collection survived inspection while the signed-out-widgets
argument for it did not, on the grounds that "a signed-out device's row matches
no scoped audience and the widget refuses to render". The first half is false
in the code as shipped: `listWhereArrayContains`
(`deviceRegistryService.ts:191-211`, behind `listForStations`/`listForLines`)
filters on `stations`/`lines` with `array-contains-any` and **never mentions
uid**, so signed-out rows are in the disruption audience today. Uid-less rows
are legal by construction — `deviceRegistryService.ts:143` writes uid only
`if (input.uid)` — and the client attaches its bearer only when it has one
(`DevicePushCoordinator.swift:154`, "no token simply means no uid, not a
rejected registration"). So signed-out devices really were being pushed to.
The root collection was load-bearing for a behaviour nobody wanted; asking
whether that behaviour was wanted is what dissolved it.

**What the nesting costs, stated up front so nobody meets it as a surprise.**
Three reads that were plain collection queries become **collection group**
queries, because the rows now sit under many parents: the two disruption
audiences (`stations`, `lines`), the broadcast list, and the steal check
(`deviceId`). Firestore auto-indexes single fields at collection scope but
**not** at collection-group scope, so each needs an explicit index — the list
is in §3, and this repo has never had to manage a Firestore index deliberately
before (§12).

Be precise about what the nesting buys, because it is easy to overclaim: the
per-account audience does **not** get cheaper in billed reads. `where uid == X`
is a single-field query on an automatically indexed field and returns the same
document count as the subcollection read that replaces it. What it buys is that
the query stops existing — a read on a known path needs no index, cannot fail
for want of one, and cannot return a row belonging to somebody else. The real
read-budget win in this document is `stateRev` (§6), and it holds whichever way
devices are stored.

**Invariant: clients never read or write Firestore directly.** All access is
through the REST/WS API with the API key and (for `/user/*`) a Firebase bearer.
Consequences, both load-bearing: the Firestore schema is a backend
implementation detail (which is why this redesign needs no client migration at
all), and every write path is a place the backend can bump a revision and
notify — no listeners, no triggers, no Cloud Functions.

---

## 3. The Firestore schema (target)

```
users/{uid}
  uid, email, displayName, photoURL?, signInProvider?, emailVerified
  createdAt, updatedAt, welcomeSent
  loggedIn: boolean            // denormalised "the devices subcollection is non-empty";
                               // maintained ONLY inside the session transactions (§4)
                               // and healed by the drift cron (§9). Kept because it is
                               // queryable and gates deleteAccount.
  lastLoggedInTime: ISO
  stateRev: number             // NEW — monotonic, bumped on every CONTENT change
                               // (boards, stations, profile fields). Never bumped by
                               // session/device churn. The heart of §6.
  stations:  SubscribedStation[]   // LEGACY v1 — the frozen APK's list. Unchanged.
  boards:    SavedBoard[]          // v2 — iOS (and Android-next, web) list. Unchanged.
  boardsUpdatedAt: number          // LWW clock for boards, unchanged.
  // NO sessions map (deleted — §4). NO preferences (stays on the deny list).

users/{uid}/devices/{deviceId}     // THE SESSION AND THE DEVICE — one row. Its
                                   // existence means "this account is signed in on
                                   // this device". Created at sign-in, deleted at
                                   // sign-out. No `uid` field: the parent is the uid.
                                   // ELEVEN fields, and every one of them is true of
                                   // this device and of nothing else — see the audit
                                   // below for what was removed and why.
  deviceId                         // stored as a FIELD as well as the doc id: a
                                   // collection group query cannot filter on document
                                   // id across unknown parents, and §4.1's steal
                                   // check is one. The root collection already does
                                   // this, for the same reason.
  platform: "android"|"ios"|"web"
  model?                           // hardware, or the browser on web
  osVersion?                       // RENAMED from the registry's `iosVersion`
  appVersion?                      // this install's build
  environment?: "sandbox"|"production"   // APNs ONLY. Never give this an FCM meaning:
                                         // it exists because an APNs token is valid
                                         // against exactly one host.
  appToken?, widgetToken?          // APNs, iOS only
  fcmToken?                        // NEW — FCM, Android-next and web push
  firstSeen, lastSeen: number      // epoch ms (house watermark convention).
                                   // lastSeen refreshed at most once per 24h and is
                                   // what the 90-day TTL and the sweep read.

users/{uid}/fcm_tokens/{token}     // LEGACY — written only by the frozen APK's
                                   // /user/fcm/register. Union-read (§5), purged on
                                   // last-device-out, deleted as a store when no
                                   // client in the field writes it.

users/{uid}/activity/{YYYY-MM-DD}_{deviceId}   // unchanged — arrayUnion append,
                                               // one doc per device per day.

metadata/subscribed_stations       // unchanged — stationCounts map, SQLite-mirrored,
                                   // written with field-path updates and
                                   // FieldValue.delete at zero (the merge trap)
```

What changed against the running system, in full: `users.sessions` deleted;
`users.stateRev` added; the **root `devices` collection deleted**, its contents
moved to `users/{uid}/devices/{deviceId}`. Everything else is byte-identical to
what runs today.

### 3.1 The field audit

Every attribute on both merged stores was checked for one thing: is this true of
the device, or is it a copy of something true of the account? Merging two stores
is the only chance to settle that, so it was settled.

**Removed, because it is account data that was being copied per device.**
`stations[]` and `lines[]`. The registry's own comment calls `stations` "the
stations this device's *widgets* show", but the value comes from
`WIDGET_STATIONS`, which `Platform.ios.kt:513` writes as the directory of every
station **the account tracks**, rebuilt from the synced boards. So the field
name says device and the value says account, and it was written once per device.
It is also `effectiveStationIds(user)`, which the backend already computes for
the subscription registry — the derivation existed and got copied, rather than
being needed in N places.

It does not move to the user document either, and that is deliberate. A derived
array sitting beside its own source, maintained by a different write path, is
the drift bug this whole redesign exists to remove; it would be cheap and it
would still be a second copy. The line→account index belongs in the SQLite
mirror (`localDbService.ts:102` already has a `users` table), which is where §5
already resolves audiences at zero Firestore reads, and which re-derives from
the boards it mirrors. Firestore stores `boards[]` once and stores nothing
derived from it.

The write path gains from this as much as the read path: today a board edit
eventually rewrites the device row on *every* device, because each one
re-registers its copy of the list on next foreground. After this it is one write
to one document.

**Renamed, because the merged row is cross-platform.** `iosVersion` →
`osVersion`. The two stores being merged already disagreed — the sessions map
called it `osVersion` — and the iOS-specific spelling was about to describe
Android and web devices.

**Deduplicated by the merge.** `appVersion` existed in both stores. `platform`
and `model` existed only in the sessions map, which is why the push registry
today cannot say what a device it is pushing to actually is — a real handicap
when diagnosing an APNs environment mismatch.

**Normalised.** `firstSeen`/`lastSeen` were ISO strings in the sessions map
while the registry used epoch ms. One row cannot carry both conventions, and
`REPLICATION.md`'s house rule is integer watermarks, so they are epoch ms.

**Dropped, no reader.** `updatedAt` on the device row: nothing in the backend
reads it. `lastSeen` answers the only question anyone asks of it, and a
registration is itself a sign of life, so the two had converged anyway.

**On the account document, and dead.** `UserProfile.address` and
`UserProfile.phoneNumber` are declared and never written or read by any backend
path, and no client sends them. Delete them. They are PII this product does not
collect, and a declared field is an invitation to start.

**Kept as accepted redundancy, with the reason recorded so it is not
"tidied".** `uid` as a field on `users/{uid}` duplicates the document id and is
harmless; removing it is churn across every read path for nothing. `loggedIn`
is a denormalisation of "the devices subcollection is non-empty", kept because
Firestore cannot query subcollection emptiness and the drift cron needs
something queryable. `lastLoggedInTime` is `max(lastSeen)` across the
subcollection, kept so analytics need not walk it.

**Indexes — the one thing this shape does not get for free.** Firestore
auto-indexes single fields at *collection* scope only, so every query that now
spans parents needs an explicit collection-group index on `devices`:

| Field | Needed by |
|---|---|
| `deviceId` | the steal check, §4.1 |
| `lastSeen` | the sweep cron, §9 |

**Two, not the four an earlier draft listed.** The audit took `stations` and
`lines` off the device row (§3.1), and with them went the only two queries that
would have needed a collection-group index on an array field. What is left is
one query on the login path and one in a nightly job. The audience path — the
one that fires during an incident, and the one where a failure would be
worst — no longer touches a collection group at all.

Create both in the deploy that ships P2, and prove each with a read-only probe
*before* the code depending on it goes live. A missing collection-group index
fails the query outright rather than degrading. Also verify on staging that an
**unfiltered** `collectionGroup('devices').get()` (the broadcast list) needs no
index of its own, rather than assuming it: this repo has never managed a
Firestore index deliberately (§12), so none of the usual instincts about what
is automatic have been tested here.

The per-account read (`users/{uid}/devices`) needs no index, being a read on a
known path — and that is the query this design runs most.

---

## 4. Session lifecycle — the transactions

Why the sessions map can move now, when two handovers said not to: they said
the `loggedIn` gate and the session state must be readable in ONE transaction,
and that folding the move into an unrelated pass was the risk. Both stay true.
The Admin SDK allows **queries inside transactions** (`tx.get(query)`), so the
transaction reads the `users/{uid}/devices` subcollection instead of a map on
the doc — same atomicity, same serialisation, one fewer copy of the truth. And
this is its own phase (§12), not a rider.

One thing to prove on staging before this ships on the login path: §4.1 also
does `tx.get()` on a **collection group** query, which is a shape this codebase
has never run inside a transaction. Confirm it serialises the same way a plain
query does rather than assuming the Admin SDK treats them identically.

All transactions follow the two hard rules the codebase already learned:
**reset all result flags at the top of every attempt** (Firestore retries the
callback on contention; a stale flag double-counts the registry), and **all
reads before any writes**.

### 4.1 Login — one transaction, steal-aware

`POST /user/sync/profile {deviceId, deviceInfo}` + bearer:

```
runTransaction:
  reset per-attempt state
  user   = tx.get(users/{uid})                        // may not exist (signup)
  mine   = tx.get(users/{uid}/devices)                // subcollection read, no index
  others = tx.get(collectionGroup('devices')
                    .where('deviceId','==',deviceId)) // this device under ANY parent
  victims = { parent(row) for row in others } − { uid }
  for v in victims: vUser = tx.get(users/{v})
                    vDevs = tx.get(users/{v}/devices)
  // ── writes ──
  activated = !user.exists || user.loggedIn != true
  write users/{uid}: profile fields ONLY where changed; loggedIn: true;
        lastLoggedInTime; stateRev += 1 ONLY if a profile field changed
  write users/{uid}/devices/{deviceId}: MERGE — deviceId, platform, model,
        osVersion, appVersion, lastSeen; firstSeen only if absent.
        Never writes a token field (see the rule below).
  for v in victims: delete users/{v}/devices/{deviceId}
                    if vDevs held nothing else: write users/{v}: loggedIn: false
  delete any row in `mine` whose lastSeen is older than 90d (lazy TTL prune)

post-transaction (setImmediate, best-effort, cron-healed — existing pattern):
  if activated:           registry += effectiveStationIds(user)
  for each deactivated v: registry -= effectiveStationIds(vUser)
  invalidate the notifier audience cache for uid and for every victim
```

The **steal** is the piece today's system lacks. Sign out of A offline, sign in
as B: today A's registry hold and push binding linger until a TTL, or forever.
Here the transaction that creates B's row sees that A still holds a row for the
same device, deletes it, and runs A's last-out transition atomically. The
abandoned-switch hole closes at its root rather than by a compensating job.

Nesting makes this one indexed collection-group query rather than a field read
on a row you already had, and that is the honest price of the shape. It is also
why `deviceId` is stored as a field: a collection group query filters on fields,
not on document ids. One extra query on the login path, against a query that
returns at most a handful of rows, is a good trade for a subcollection read on
every push fan-out.

Rules preserved from today: a request with **no deviceId** writes the profile
only — no session row, `loggedIn` untouched, which kills the
`loggedIn:true, sessions:{}` legacy artefact at its source. **The login write
never invents token fields.** Only `/device/register` supplies tokens; a login
that created a row before registration ran would put a token-less phantom into
the broadcast audience, which is the same trap the old `bind` had, one store
over. A plain re-open with nothing changed writes **nothing** — the `needWrite`
elision survives, judged now against the row's `lastSeen`, refreshed at most
once per 24h.

### 4.2 Logout — a delete, idempotent, replayable

`POST /user/logout {uid, deviceId?}`:

```
runTransaction:
  reset per-attempt state
  user = tx.get(users/{uid})
  mine = tx.get(users/{uid}/devices)
  targets = deviceId ? (mine rows with that id) : mine
  for row in targets ∪ stale(mine): delete row
  deactivated = user.loggedIn && (mine − deleted is empty)
  if deactivated: write users/{uid}: loggedIn: false
  // self-heal: loggedIn true with zero rows → also deactivate

post-transaction: if deactivated → registry -= effectiveStationIds;
  purge legacy fcm_tokens (LAST-device-out gate, unchanged — the store is keyed
  by token and cannot be attributed per device for the frozen APK);
  invalidate audience cache
```

Deleting the row, rather than clearing a field on a row that survives, is now
the *correct* teardown and not a destructive one. A signed-out device receives
nothing, so leaving its tokens addressable would itself be the bug. Nothing is
lost that matters: `/device/register` runs on every foreground and on every
APNs token callback, so the next sign-in rebuilds the row from the client's own
state within a second of the app opening.

Replay safety gets stronger, and it comes free from the path. The logout
addresses `users/{uid}/devices/{deviceId}` — a path that names the account. If B
has since signed in on that device, B's row is under `users/{B}`, and A's
replayed logout cannot see it, let alone delete it; A's own transition already
ran, via B's steal. So a queued logout (§8) can be replayed as often as you
like, years late, with no conditional check to get wrong. Under the old shape
that was true by construction of a *query*; here it is true by construction of
a *path*, which is a stronger guarantee and a cheaper one to review.

### 4.3 Account deletion

```
revokeRefreshTokens(uid)          // close the ~1h token window FIRST
notify(uid, 'deleted')            // while the audience still resolves
logout(uid)                       // every row deleted, last-out transition runs
purgeUserSubtree(uid)             // listCollections() — never name subcollections
delete users/{uid}; delete the Auth user
```

The old ordering trap — "the device purge must precede the session teardown,
because the teardown clears the uid the purge queries on" — dissolves entirely,
and this is the single clearest win of the nesting. There is no separate device
purge left to order: the rows are *inside* the subtree, so `purgeUserSubtree`
removes them by walking `listCollections()`, which it already does, and which
is exactly why it must keep discovering subcollections instead of naming them.
`logout(uid)` still runs first, but now only for its transition — the registry
decrement and the audience invalidation — not for the deletion.

`checkRevoked: true` on the auth middleware (already live) is what guarantees
every other device is rejected with `code=account_gone` on its next request
even if the push never arrives.

### 4.4 Ghost hygiene

- Lazy: every login/logout transaction deletes rows of that uid past the
  90-day TTL, which is today's behaviour one store over.
- Sweep cron (§9) catches accounts that never come back.
- iOS reinstall ghosts are already prevented at the source: `deviceId`
  survives reinstall via the Keychain (`DeviceIdentityStore`), so a reinstall
  re-presents the same identity instead of minting a sibling. Android's id
  lives in its own prefs file; web accepts churn (localStorage) and lets the
  TTL clean up.

---

## 5. Push addressing — one union, then one store

A device's push address lives on the account's row for it —
`users/{uid}/devices/{deviceId}` — as `fcmToken` (Android-next, web) and
`appToken`/`widgetToken` (iOS APNs). `/device/register` gains optional
`fcmToken` and `platform` and becomes the one registration endpoint for every
platform we ship from here on.

**`/device/register` becomes bearer-gated, and that is a genuine behaviour
change rather than an additive one.** It is API-key-only today, sitting on
`apiRoutes` with the public endpoints, and `DevicePushCoordinator.swift:154`
attaches the bearer only when it happens to have one, with a comment saying a
token-less registration is additive rather than rejected. Under this shape
there is no row to write without a uid, so an unauthenticated registration has
nowhere to go and is refused. The matching client change: iOS must not attempt
registration until it holds a session, and must treat signed-out as *skip*, not
as failure to retry. Android never calls this endpoint — `deviceRegistryService`
says so in its own comment — so the frozen APK is untouched.

Audience resolution changes shape, and the disruption path changes most,
because §3.1 took its scoping arrays off the device row:

| Audience | Today | Target |
|---|---|---|
| every device on one account (`user.sync`) | `devices where uid == X` | `users/{X}/devices` — read on a known path, no index |
| devices watching a station or line (disruption) | `devices where stations`/`lines array-contains-any […]` | **two hops**: the mirror answers "which accounts watch this line", then each account's device rows supply the tokens |
| every device (broadcast) | `devices.get()` | `collectionGroup('devices').get()` |

The two-hop disruption path is not a regression, for two reasons. It reads the
same number of device documents in the worst case, because billing is per
document returned and the same devices are returned either way. And at steady
state it reads none: the first hop is SQLite, and the second is the per-uid
audience cache this section already relies on. What it removes is the reason the
device row was carrying account data at all.

Be careful not to overclaim the per-account path either. `where uid == X` is a
single-field query on an auto-indexed field and returns the same document count
as the subcollection read replacing it. The gain is that a query stops
existing — a read on a known path needs no index, cannot fail for want of one,
and cannot return somebody else's row.

The frozen APK cannot move: its `/user/fcm/register` carries no deviceId, so
its tokens stay in `users/{uid}/fcm_tokens` as today, purged on last-device-out
and pruned at 90 days. `UserSyncNotifier` reads the **union** — this account's
device rows carrying an `fcmToken`, plus the legacy subcollection —
deduplicated by token string. This is the pattern `effectiveStationIds` already
uses for the two board lists: two writers disagree, the union is safe, and the
loser is deleted once nothing writes it. When production shows the legacy store
empty for a few weeks after Android-next ships, delete the store, its service
and `pruneStale` with it.

**Under the current iOS-only scope the union is not built yet**, and §12 says
why: the only writer of `fcm_tokens` is the frozen APK and the only writer of
device rows is iOS, so nothing can appear in both and there is nothing to
deduplicate. The notifier reads the two subcollections and concatenates. The
deduplicating union is written when Android-next starts putting an `fcmToken`
on a device row, and not before.

Note what it becomes under nesting either way: both halves are subcollections of
`users/{uid}`, so an account's full push audience is two reads on two known
paths. At steady state it costs zero anyway — the backend is the only writer of
every store involved, so an in-memory per-uid audience cache invalidated by
sign-in, sign-out, steal and register is exactly correct. `UserFcmTokenService`
already caches this way; extend the idea to the device rows.

---

## 6. The sync fabric

### 6.1 `stateRev` — one integer that gates everything

Every mutation of account **content** — boards, stations, profile fields —
bumps `users/{uid}.stateRev` by 1 in the same write. Session and device churn
never bumps it (nothing to refetch). The backend mirrors the current rev into
its SQLite (`user_revs(uid, rev)`), maintained by its own write path — no
listener needed, because *all* writes flow through this backend
(§2 invariant). This is the house replication pattern (`REPLICATION.md`:
Firestore master, SQLite slave, integer watermark) applied to one more row.
On a cache miss (cold process), one document read seeds it.

Client side, `localRev` is persisted per account. The rule is one sentence:
**fetch the profile iff an observed rev exceeds `localRev`; after applying,
set `localRev` to the rev fetched.** Duplicate signals, late pushes, and
socket/push double-delivery all collapse into "compare two integers, do
nothing".

### 6.2 Three tiers of delivery

| Tier | When it applies | Latency | Firestore cost |
|---|---|---|---|
| **Socket** | device is foregrounded — it already holds the live-departures WebSocket, and the hub already knows the socket's uid (`stationStreamHub.register(socket, uid)`) | instant | **0** |
| **Silent push** | backgrounded devices — APNs (device rows) / FCM (union) with `{type:"user_sync", reason, uid, rev, ts}` | seconds, best-effort | 0 to send; 1 read per device that actually fetches |
| **Foreground rev check** | every app open / `visibilitychange` — `GET /user/state/rev`, answered from the SQLite ledger | on open | **0 when unchanged**, which is the overwhelmingly common case |

The socket tier is new and nearly free: `UserSyncNotifier.notify` additionally
hands the frame to the stream hub for the uid's live connections. It is also
what makes **web** work properly — browser push permission is routinely denied,
and a web tab that is open holds the socket anyway. A backgrounded/closed tab
simply catches up from the rev check on next focus. Web needs no push to be a
first-class sync citizen.

Payload additions are additive-only: the frozen APK reads `type/reason/uid/ts`
and ignores unknown keys, so `rev` rides along safely.

### 6.3 Echo suppression, finished

The server half (`excludeDeviceId`) exists. The client half is one line: the
mutating calls (`syncBoards`, `syncStations`) send `deviceId`, the notifier
skips that device's APNs tokens and socket. FCM cannot exclude (legacy tokens
carry no deviceId) — unchanged, and harmless: the frozen APK guards on uid and
its reconcile is idempotent; the rev gate makes the wasted fetch a no-op
anyway. The field stays advisory and never authorises anything.

### 6.4 What flows through this fabric

Exactly the synced set already decided (boards + filters + profile). The signal
is trigger-then-fetch, never push-the-data: the push says "something changed,
rev N", the fetch returns the whole profile, the client applies it as a
**non-destructive diff** mid-session (`reconcileBoards` — never wipes on a
failed fetch) or as the guarded destructive restore on the login path only
(`WidgetRestore.during`, `allowEmpty`, `BoardPushGate` — all unchanged; they
are shipped and correct).

Rejected alternative, so nobody "improves" this later: client-side Firestore
`onSnapshot` listeners. They would bill a read per change per listening device,
require security rules and client SDK coupling to a schema that is deliberately
backend-private, and bypass the LWW/allowEmpty guards on the write path. The
trigger-then-fetch shape with a rev gate delivers the same freshness for a
fraction of the cost and keeps the schema private.

### 6.5 Concurrent edits — the boards merge policy

`POST /user/sync/boards` gains optional `baseRev` (the rev the client last
applied) and per-board `updatedAt` (epoch ms, additive fields):

- `baseRev == current stateRev` → **full replace** (today's behaviour;
  deletions work; the `allowEmpty` guard still applies to empty lists).
- `baseRev < current` → a concurrent edit happened somewhere else. Server
  merges: **union by board id, newer `updatedAt` wins per id, deletions are
  NOT applied**, result bumps rev, both devices are notified and converge.
- No `baseRev` (older client) → today's LWW via `boardsUpdatedAt`, unchanged.

The bias is chosen, not accidental: silently resurrecting a deleted board costs
one annoyed extra tap; silently losing an added board is real loss. Two devices
editing while one is offline is rare-times-rare; this is the right amount of
conflict machinery for a departures app, and the per-board timestamps mean a
finer policy later needs no wire change.

---

## 7. The read/write budget

The numbers this design is accountable to. "User" = one account, two active
devices, ~20 app opens/day, 2 board edits/day.

| Path | Today (iOS as built) | Target |
|---|---|---|
| App open, nothing changed | 1 profile read per open past the 2-min debounce (~20/day) | **0** (rev ledger; 1 on backend cold start) |
| Board edit | 1 write + push fan-out; **self-echo fetch** (1 read); other device 1 read; **plus 1 device-row write per device**, as each re-registers its copy of the station list on next foreground | 1 write (rev bump included); echo suppressed; other device 1 read — or 0 network at all via socket signal then 1 read. The per-device rewrites are **gone**: §3.1 took the station list off the device row |
| Session heartbeat | 1 user-doc write per device per 24h | 1 **device-row** write per device per 24h — the hot document stops taking device traffic entirely |
| Login (warm, same device) | reads+writes elided (needWrite) | same, judged on the device row |
| Login (new device) | 1 read + tx write | 1 tx (user doc + subcollection + **1 collection-group steal query**) + 1 profile read. The steal query is the one read this design adds, and it runs on real sign-ins only — a handful per device, ever |
| Activity | 1 arrayUnion write per device per day | unchanged |
| Syncer polling | 0 (SQLite mirror) | unchanged |
| API-key auth | 0 (RAM mirror) | unchanged |
| Push audience resolution | cached (FCM); 1 query (APNs) | 0 at steady state (write-invalidated cache) |

Net: **~24 reads/day/user → ~2–3**, and the `users/{uid}` document — the one
every login reads — is written only when the user actually changes something.

---

## 8. The client architecture (KMP `core/session/`)

One module, used by iOS now, Android-next and web later. The frozen `android/`
app is exempt and untouched. This is the consolidation from
the consolidation doc's Phase 1, unchanged in intent, restated as the
component list the implementer builds:

| Component | Job | Notes |
|---|---|---|
| `Session` + `SessionStore` | THE answer to "who is signed in": `StateFlow<Session?>`, sole reader/writer of every identity key | Replaces 7 implementations / 12 key spellings. Domain (standard vs App Group) explicit per key — the `AppGroupKeys` misnomer caused a real outage |
| `DeviceIdentity` | stable per-install id + `DeviceInfo` | moves into `core`; iOS actual = App Group + Keychain restore (exists); Android actual adopts the shipping `StationlyDevice` prefs file; web actual = localStorage |
| `TokenAuthority` | `Platform.getAuthToken()` contract: valid-when-used, refresh-capable | exists (iOS `IosAuthTokenAuthority`, Android SDK-native); web actual = Firebase JS `getIdToken()` |
| `SessionLifecycle` | ONE `finalizeSignIn`, ONE `signOut`, ONE `deleteAccount`, in the canonical order (flush → activity row → capped server calls → push release → platform sign-out → wipe → in-memory resets) | replaces the 4 divergent sign-outs; platform differences become declared interface no-ops |
| `PendingOps` | durable queue, replayed on launch/network-regain; first op: `logout{uid, deviceId, at}` | closes the offline-logout hole; server replay-safety guaranteed by §4.2; drop ops older than the 90d TTL |
| `SyncEngine` | one reconcile entry point, mutex-serialised, rev-gated; sources: push, socket frame, foreground | absorbs `UserSyncBridge`/`UserSyncCoordinator` duplication |
| `PushRegistrar` | `register(uid)` / `release()` | iOS actual wraps `DevicePushCoordinator` (+ the never-built `release()`); Android-next actual posts `/device/register` with `fcmToken`; the dead `composeApp` `FcmTokenRegistrar` is **deleted** |

Also folded in (small, already-agreed): `DevicePushCoordinator` takes its
bearer from the token resolver instead of the stored key; dream-settings keys
gain the uid namespace; `app_theme` moves to durable storage.

**Two changes `PushRegistrar` must make on iOS, and the second one is a trap.**
First, registration is now gated on having a session: no uid means no row to
write, so a signed-out `register()` is a *skip*, not a failure to retry. Second,
`DevicePushCoordinator.swift:131` holds `lastRegisteredSignature`, an in-memory
guard that skips a POST whose body is unchanged. Sign out and back in on the
same phone with the same boards and the body is byte-identical, so the POST is
skipped. Today that is harmless: the row survives at the root and login rewrites
its uid. Under the nested shape the row was **deleted** at sign-out, login
recreates it without tokens (only `/device/register` writes those), and the
device would then sit in its account's audience holding no address at all —
silently, because a zero-token device is not an error. `release()` must clear
the signature, and `register(uid)` must not trust it across a session change.

### 8.1 The scatter this replaces

Kept because the component table above reads like a preference until you see
what it is replacing. Every one of these was found by audit, not by guessing,
and every bug in the four session handovers is a seam between two of them.

**"Who is signed in" has seven answers on iOS alone**, and the string
`firebase_user_uid` appears as a raw literal or a separately-declared constant
in **twelve places across four modules and two languages**:

| Answer | Reads | Module |
|---|---|---|
| `AuthBridge.hasSession` | `Auth.currentUser`, falling back to the token under locked protected data | `iosApp` (Swift) |
| `IosPlatformAuthProvider.isLoggedIn()` | `firebase_auth_token` non-blank | `composeApp/iosMain` |
| `UserSyncBridge.currentUid()` | `firebase_user_uid` in standard defaults | `composeApp/iosMain` |
| `ProfileViewModel` (×2), `SummaryViewModel` | the same literal | `composeApp/commonMain` |
| `ActivityLog.UID_KEY`, `UserSettings.UID_KEY`, `UserStateRepository.UID_KEY` | three separate constants, same string | `core/commonMain` |
| `IosAuthTokenAuthority` | `firebase_auth_token` | `core/iosMain` |
| `DevicePushCoordinator` | `firebase_auth_token`, directly, for its bearer | `iosApp` (Swift) |

They do not agree. `isLoggedIn()` answers from the token and `currentUid()`
from the uid, which is exactly the torn state `discardTornIdentity` exists to
clean up afterwards. `hasSession` is the only one that asks FirebaseAuth. When
the August 15 race wiped eight identity keys and left the token, four of these
said signed in and three said signed out, in one process. `SessionStore` makes
that state unrepresentable instead of merely repairable.

`AppGroupKeys.FIREBASE_USER_UID` is the fifth declaration and it lies about
where the value lives: the object is called `AppGroupKeys` and half its
contents are in `UserDefaults.standard`. Reading the uid from the group suite
silently disabled cross-device sync entirely, once. `SessionStore` names the
domain per key rather than inheriting it from an object name.

**Sign-out has four implementations and they do measurably different things.**
Read the "no" column downwards; every one is a decision nobody took, and is
simply the difference between two files written eight months apart:

| Step | iOS `signOut` | iOS `deleteAccount` | Android `logout` | forced (auth expiry) |
|---|---|---|---|---|
| flush pending boards | yes, 3 s cap | **no** | **no** | no |
| activity row | yes | yes | **no** | yes |
| `POST /user/logout` | yes, 4 s cap | via delete | yes, 4 s cap | **no** |
| release push registration | **no-op on iOS** | **no-op on iOS** | yes, 3 s cap | no |
| `UserSettings.reset()` / dream reset | yes | `forgetAccount` | **no** | no |
| retry if the network failed | **no** | **no** | **no** | n/a |

The forced path is the worst of them: it ends a session through
`Platform.signOutFromAuthExpiry` without ever telling the backend, so the
subscription hold and the device binding survive a logout the server itself
triggered. §4.2's teardown and §8's `PendingOps` between them close every row.

**Device identity has two implementations, neither in `core`.**
`android/…/DeviceIdProvider` keeps a UUID in a dedicated `StationlyDevice`
prefs file and is correct and shipped; `composeApp/…/platform/DeviceIdentity`
is an `expect object` whose **Android actual is a process-lifetime UUID that
changes on every launch**. So the shipping app and the shared code disagree
about what a device id is, while every reader of it (`ActivityLog`,
`UserStateRepository`, the API layer) sits in `core`.

**And one file is dead code that reads as live wiring.**
`composeApp/…/util/FcmTokenRegistrar` is called from four places, but on iOS
`IosNotificationManager.registerDevice()` returns `""` by design, so
`ensureRegistered` returns on its second line, every time. It has never
registered anything on iOS and cannot. It is also not the registrar Android
uses — `android/…/service/FcmTokenRegistrar` is a different object with the
same name. The audit finding "iOS never unregisters its push tokens at logout"
therefore really means "iOS calls a function that was never capable of doing
anything", and the guard that hides it (`if (token.isNotBlank())`) reads like a
safety check. It is deleted, not fixed.

**Web specifics:** the thinnest client, deliberately — boards live server-side,
so web needs no SQLite (sqldelight has no wasmJs artifact anyway): fetch on
load, socket while open, rev check on focus, localStorage for
deviceId/localRev/identity. If the thin client works well, it validates that
nothing in the sync design secretly depends on heavy local state.

**Auth invariants carried forward** (all shipped, listed so they are not
undone): never sign out on a bare 401 — only `account_gone`; classify-then-
retry-once lives in `AuthExpiryGuard` (11 tests); the forced foreground refresh
is the account-existence probe, not the request-path token source; every forced
logout leaves a tripwire row stamped from the rejected token's `sub`.

---

## 9. The two crons (backend, nightly, one job)

The design's safety net — they turn every remaining "best-effort" into
"eventually exact".

1. **Sweep:** `collectionGroup('devices').where('lastSeen','<', now − 90d)` →
   group by parent uid → run the §4.2 teardown per uid, transition-aware.
   There is no "uid present" clause to add any more: a row exists only while a
   session does, so every row returned is by definition a live session.
   Reclaims holds from devices that never came back.
2. **Drift reconciliation:** read all device rows (one collection-group scan,
   small fleet) and all users; heal any `loggedIn` that disagrees with the rows;
   recompute `stationCounts` = Σ `effectiveStationIds` over active users;
   write only the keys that differ, with **field-path updates and
   `FieldValue.delete()` at zero** (the merge/map-delete trap). Log every
   correction loudly — drift is a bug signal, not just dirt to sweep.

The registry post-transaction `setImmediate` updates stay as they are (their
failure direction is over-counting, the safe one); the cron is what makes that
direction *eventually correct* instead of monotonically wrong. It also repairs
the historical drift already sitting on staging. Linear cost, fine at this
scale; shard by `updatedAt` if the fleet ever makes it matter.

**How they are scheduled, since nothing schedules anything today.** There is no
cron in this backend: no `node-cron` dependency, no scheduler config, no
`setInterval` outside the prediction cache and the stream server. The pattern
that already exists is the right one — `internalRoutes.ts`, mounted before
`/api/v1` so it skips the API key and the public rate limiters, loopback-only
via `req.socket.remoteAddress` (never `req.ip`, which honours a spoofable
`X-Forwarded-For`), shared secret compared in constant time, and unreachable
from the internet because nginx has no catch-all `location /`. Add
`POST /internal/maintenance/sweep` and `POST /internal/maintenance/reconcile`
behind that same guard and fire them from the host's crontab, which is where the
Syncer already runs. Both jobs must be **idempotent and safe to run twice** —
the reconcile writes only differing keys, and the sweep is the §4.2 release,
which is conditional by construction — so a double fire, a manual run during an
incident and a retry after a `pm2 restart` are all harmless. In-process
`node-cron` is the alternative and is rejected: the backend happens to run as a
single pm2 process today, but nothing guarantees it stays one, and two instances
each firing a nightly recompute is precisely the contention the transactions
exist to avoid.

---

## 10. Security invariants

- uid is always taken from the verified bearer (`validateUserToken` +
  `checkRevoked`), never from a body field. Under the nested shape the uid is
  not a field at all but the parent path, so an unverified caller cannot even
  name a row to write: there is no uid to spoof, only one you must prove.
- `/device/unregister` needs no ownership rule any more, which closes the open
  decision from the backend handover §5c by removing it: the row it would
  delete is `users/{uid}/devices/{deviceId}`, and the bearer is what supplies
  the uid. An unowned row cannot exist, so the case the rule was for is gone.
- `excludeDeviceId` and the socket auth frame's `deviceId` stay advisory —
  lying denies yourself an echo skip, nothing more.
- Per-uid rate limits unchanged. Web ships the same client API key (accepted;
  it authorises nothing user-scoped by itself) behind CORS + the per-uid
  limiter.
- Push payloads carry uid + rev + reason, no PII; clients verify uid before
  acting (unchanged).

---

## 11. Wire compatibility — the frozen contract

Byte-identical, forever (or until Android-next replaces the APK):
`POST /user/sync/profile`, `GET /user/sync/profile?uid=`,
`POST /user/sync/stations`, `POST /user/logout {uid,deviceId}`,
`POST /user/fcm/register {token,platform,appVersion}`,
`POST /user/fcm/unregister`, `POST /user/delete-account`, FCM `user_sync`
`{type,reason,uid,ts}` (+`rev` additive), the `Station_*` /`LineStatus_*` FCM
topics for board data. The APK never sees Firestore, so §3's storage changes
are invisible to it — with one exception that the verification turned up rather
than cleared.

**`GET /user/sync/profile` returns the whole document.** `getUserProfile` ends
`return { ...data, stations, boards }`, a spread of the raw Firestore document,
so `sessions` **is** in the response today and always has been, and every field
added to the user document ships to every client on every fetch, `stateRev`
included. That is a standing argument for an explicit response whitelist; it is
not a compatibility problem, and the checking has been done rather than left as
a gate.

### 11.1 What the released APK actually parses — verified, not assumed

Android went to the Play Store on **2026-07-08** (the launch email template is
`688c257` in the backend, 2026-07-08). The closest client commit is `1a6c846`,
same day. At that commit:

```
data class UserProfileResponse(
    val uid: String,                        // REQUIRED
    val email: String,                      // REQUIRED
    val displayName: String,                // REQUIRED
    val photoURL: String? = null,
    val address: String? = null,
    val stations: List<SubscribedStation>   // REQUIRED — no default
)
```

and `NetworkModule`'s Ktor JSON is `ignoreUnknownKeys = true`, `isLenient`,
`coerceInputValues` — added `36e80c5` on 2026-03-16, the only commit that has
ever touched that block, and therefore in place nearly four months before
launch.

Three conclusions, each load-bearing:

1. **`sessions` was never in the client model, in any commit.** The `-S`
   search across the file's whole history finds only a comment. So the map has
   been arriving and being silently discarded on every Android login since
   launch. Deleting it changes nothing the APK looks at.
2. **Adding fields is already proven safe in production, not merely believed
   to be.** `boards` and `boardsUpdatedAt` were added to the user document
   *after* the release and are in the response today; the live APK models
   neither and has been discarding both without incident. `stateRev` is the
   same shape of change.
3. **The compatibility surface is exactly four fields.** `uid`, `email`,
   `displayName`, `stations`. Omit any one, or send `null` for it, and the
   released APK throws `MissingFieldException` on every login — a hard failure,
   not a degraded one. `coerceInputValues` does not save `stations`, because
   coercion falls back to a *default* and `stations` has none.

`stations` is the sharp one: it is the **legacy v1 board list**, which is
exactly the kind of field a later cleanup deletes. `getUserProfile` emits
`data.stations ?? []` today, so it is always an array. That line is a
compatibility guarantee and must be treated as one. §14.

**The admin console does read the map, and P2 must carry it.** That was the
grep this section asked for; it was run on 2026-08-24 and it came back positive.
`src/admin/adminDataService.ts` reads `x.sessions` off the user document into
`DeviceSession[]` (`:170`), carries it on `AdminUser` (`:53`) and mirrors it as
a JSON column in the admin's own SQLite (`:218`, `:237`), and
`adminRoutes.ts:71` serves it as "profile, sessions/devices" on
`GET /admin/users/:uid`. This is not a wire problem and not a blocker — the
admin is a backend-side reader, so it moves inside the same commit as the
storage change: rebuild `AdminUser.sessions` by reading `users/{uid}/devices`
instead of the map, and keep the field name so the view above it does not
move. It matters only because `refreshUsers` reads full user documents, which
means an admin left behind would not error; it would quietly report every
account as having no devices at all.

Additive only: `GET /user/state/rev`; `baseRev`/`updatedAt`/`deviceId` on
`syncBoards`; `deviceId` on `syncStations`; `fcmToken`/`platform` on
`/device/register`; the socket `user.sync` frame; `rev` in push payloads.

**One change that is not additive, and it is the only one:**
`/device/register` starts requiring a Firebase bearer. It is mounted at
`apiRoutes.ts:70`, before the `/user` prefix picks up its middleware at `:74`,
so the change is `validateUserToken` inlined on that one route rather than a
path move. Nothing in the frozen APK calls it — `deviceRegistryService` says as
much in its own comment, and Android registers push through
`/user/fcm/register` — so the frozen contract is still intact. The client half
is in §8.

---

## 12. The implementation plan, end to end

**Client scope: iOS only.** Android is live at versionCode 2 and frozen — no
APK is being built this cycle, so no phase below touches `android/`. It must
keep working untouched throughout, and §11.1 establishes that it does. Web and
Android-next are parked in §13: the design accommodates both, and no phase here
builds either.

That scoping removes real work, and it is worth naming exactly what, so nobody
implements it by reflex:

- **`fcmToken` stays defined on the device row but nothing writes or reads it.**
  It is reserved for Android-next and web. Defining it costs nothing; writing a
  code path with no producer costs a maintainer.
- **The FCM/APNs union in §5 is not needed yet.** The only writer of
  `users/{uid}/fcm_tokens` is the frozen APK, and the only writer of device rows
  is iOS. So the notifier reads two subcollections on known paths and
  deduplicates nothing. The union arrives with Android-next, or never.

Each phase lands, verifies and is reversible alone. P0 and P1 are independent of
everything and of each other; P2 depends on nothing but should not run before
P0 is proven; P3 depends on P2 only for its final guarantee, not to ship; P4
depends on P1.

---

### P0 — the safety net (backend only) — ✅ **DONE on staging, 2026-08-24**

The net goes up before anyone walks the wire, and it repairs drift already on
staging. Implementation and the production runbook are in
`stationly-backend/docs/HANDOVER_SESSION_SYNC.md`; this is the shape.

> **Status.** Built, deployed to staging, both jobs run through the deployed
> routes with both probes reporting PASS, and the crontab installed and proven
> to fire by canary. The registry drift this was written for is gone: 104 keys
> → 13 on the first run, and it has since tracked an organic change down to 12
> on its own. **Production is untouched by instruction** — the carry-over is a
> runbook in `stationly-backend/docs/HANDOVER_SESSION_SYNC.md` §6, which opens
> with a hazard that would sign out every Android user if its ordering is
> ignored.

| Layer | Work |
|---|---|
| Firestore | none |
| Backend | `SessionMaintenanceService` with `sweep()` and `reconcile()`; three enabling changes — `UserService.effectiveStationIds` loses `private`, new `UserService.isSessionLive` so the TTL predicate stops existing twice, new `SubscriptionService.reconcileCounts`; two routes on `internalRoutes.ts` behind its existing loopback + constant-time-secret guard; crontab entries on the host |
| core / iosApp | none |

**Verify:** predict with the read-only probes, hand-verify, then run — the
procedure in `stationly-backend/docs/HANDOVER_SESSION_SYNC.md` §6. Reconcile is the
dangerous one and is not symmetric with sweep: it compares a stale snapshot
against a live document, and a naive diff can undo a concurrent increment. Its
race guard and its "empty target vs non-empty registry" sanity check are not
optional.

---

### P1 — `stateRev` and the read budget (backend + core) — ✅ **DONE, 2026-08-24**

Self-contained, and the largest single win in the document.

> **Status.** Built, deployed to staging, and verified on the iPhone 11. The
> headline criterion passed as measured: one foreground with nothing changed
> costs **+1 rev check and +0 profile reads**, and the rev check is answered
> from SQLite, so it costs no Firestore read either. Full spec and measured
> numbers in `HANDOVER_SESSION_SYNC.md`.
>
> **Two corrections to what is written below**, both found by building it:
> the bump list is missing `addStation` and `removeStation` (five sites, not
> three), and §6.1's "fetch iff observed > localRev" is wrong at zero — every
> pre-existing account reads `stateRev = 0`, so a literal gate switches off
> reconcile for all of them. An observed 0 must mean "cannot tell, go and look".
> `HANDOVER_SESSION_SYNC.md` §3 and §5.

| Layer | Work |
|---|---|
| Firestore | `users.stateRev`, an additive number field. No index. |
| Backend | bump `stateRev` in every **content** write (profile fields where changed, `syncStations`, `syncBoards`) and nowhere else; `user_revs(uid, rev)` in `localDbService`, maintained by those same write paths; `GET /user/state/rev` answered from SQLite, reseeding from Firestore on a miss; `rev` added to push payloads; accept `deviceId` on the mutating syncs and pass it as `excludeDeviceId` |
| core | persist `localRev` per account; one rev gate at the reconcile entry point (fetch iff observed rev > localRev; set localRev to the rev fetched); send `deviceId` on `syncBoards`/`syncStations` |
| iosApp | nothing — the foreground path already exists |

**Verify:** an app open with nothing changed costs **zero** Firestore reads
(grep the backend log for ledger hit vs miss); a board edit on device A reaches
B exactly once; the editing device does not refetch its own echo.

_Verified 2026-08-24, except the two-device half: only one iPhone is available,
so A→B convergence is evidenced by the push audience dropping from 2 devices to
1 rather than by watching B apply it._

---

### P2 — the storage move (Firestore + backend + one iOS change)

Three sub-phases, in this order, because an index must exist before the query
that needs it and rows must exist before anything judges their absence.

**P2a — indexes.** There is no `firestore.indexes.json` in this repo and no
step that deploys one; every index in production is either automatic or was
clicked into a console. Create the file, define the two collection-group
indexes on `devices.deviceId` and `devices.lastSeen`, add the deploy step, and
prove each with a read-only probe. Confirm rather than assume that an
unfiltered `collectionGroup('devices').get()` needs no index of its own.

**P2b — backfill.** Create `users/{uid}/devices/{deviceId}` from the two stores
being merged: the `users.sessions` map supplies `platform`, `model`,
`osVersion`, `appVersion`, `firstSeen`, `lastSeen` (converted from ISO to epoch
ms), and the root `devices` row supplies `environment`, `appToken`,
`widgetToken`. Idempotent and re-runnable. **Verify per user that the row count
matches the union of map entries and root rows for that uid**, with a read-only
probe, before anything downstream trusts it.

**P2c — the transactions and the cutover.**

| Layer | Work |
|---|---|
| Firestore | root `devices` collection retired; `users.sessions` stops being written, then is deleted from documents; `UserProfile.address` and `.phoneNumber` deleted |
| Backend | §4.1 steal-aware login, §4.2 delete-on-logout, §4.3 deletion (the "device purge precedes session teardown" ordering comment is deleted, not reordered); the §3.1 field audit applied to what is written; `deviceRegistryService`'s reads move to the subcollection and the collection group; `/device/register` writes the subcollection and requires a bearer (`validateUserToken` inlined at `apiRoutes.ts:70`, before the `/user` prefix's middleware at `:74`); `AdminUser.sessions` rebuilt from the subcollection |
| core | nothing |
| iosApp | `DevicePushCoordinator` gates registration on having a session, treats signed-out as *skip* rather than a failure to retry, and clears `lastRegisteredSignature` across a session change (§8) |

**Verify, in this order:** sign in, sign out, account switch and delete each
assert the exact expected `users/{uid}/devices` rows, `loggedIn`, and registry
counts. Prove the steal explicitly — sign B into A's device, then assert A's
row is gone, A's `loggedIn` is false and A's counts dropped. Assert push
audiences are **non-empty** (a zero audience is silent by design; verify the
audience, never the send). **Only then** re-enable the reconcile's
`true → false` heal, which stayed off from P2b precisely because it ratifies a
backfill miss rather than repairing it.

---

### P3 — client consolidation (core + iosApp)

Kills the scatter in §8.1. No backend change, no wire change, so nothing here
can reach an Android user or break one.

| Layer | Work |
|---|---|
| core | new `core/session/`: `Session` + `SessionStore` (the sole reader/writer of every identity key, domain explicit per key), `DeviceIdentity` moved in, `SessionLifecycle` with one `signOut` and one `deleteAccount` in the canonical order, `PendingOps` with the durable logout queue, `SyncEngine` absorbing the `UserSyncBridge`/`UserSyncCoordinator` duplication, `PushRegistrar` with the `release()` iOS has never had; `TokenAuthority` already exists |
| core | delete `composeApp/…/util/FcmTokenRegistrar` — dead on iOS from its second line, and not the registrar Android uses |
| iosApp | `DevicePushCoordinator` takes its bearer from the token resolver instead of the stored key |
| both | dream-settings keys namespaced by uid; `app_theme` moved to durable storage so it survives a sign-out |

**Verify:** `firebase_user_uid` appears in exactly one file. A logout with the
network down replays on next launch and releases the subscription hold. The
forced auth-expiry path and the ordinary sign-out reach the same code.

---

### P4 — the instant tier (backend + core) — **unblocked** (P1 is done)

| Layer | Work |
|---|---|
| Backend | `UserSyncNotifier.notify` additionally hands the frame to `StationStreamHub` for that uid's live sockets — the hub already knows each socket's uid |
| core | socket `user.sync` frame routed into `SyncEngine`, through the same rev gate as push and foreground |

**Verify:** a board edit on device A appears on a foregrounded device B with no
push involved and no poll, and B's rev gate suppresses the duplicate when the
push arrives too.

---

### Closing out

After P4, the 7-day soak with the August-23 tripwires: pass is zero
`auth.forced_logout` and zero unexpected login screens. Staging probes
throughout follow the house `check_*.js` read-only script pattern.

## 13. Open decisions, parked with slots

- **Appearance sync** — declined 2026-08-15, unchanged. The slot if reversed:
  `prefs` blob + `prefsRev` on the user doc, debounced through the
  `BoardPushGate` pattern, applied in `restoreBoards`; nothing else in this
  design moves.
- **Finer board conflict policy** — per-board `updatedAt` is already on the
  wire after P1; revisit only on evidence of real merge annoyance.
- **Android-next** — adopts the KMP client wholesale (`boards` v2,
  `/device/register` with `fcmToken`, socket tier), and brings the two pieces
  §12 deliberately leaves unbuilt: a producer for `fcmToken` on the device row,
  and the deduplicating union in §5. Nothing here blocks or presumes its date.
- **Web** — parked, not designed away. §8's client module is where it lands
  (localStorage for deviceId/localRev/identity, no SQLite, fetch on load,
  socket while open, rev check on focus), and §6.2's socket tier is what makes
  it a first-class sync citizen without ever being granted push permission.
  It is the thinnest possible client, which is why building it later is also
  the best test that nothing here secretly depends on heavy local state.

## 14. Traps for the implementer

- **Reset transaction flags at the top of every attempt.** Firestore retries;
  a stale flag double-counts the registry. Already bitten once.
- **`stateRev` bumps on content only.** Bumping it on session churn makes
  every login wake every device into a pointless fetch.
- **Only `/device/register` writes TOKEN fields.** Login creates and merges the
  row — that is what a session is now — but a login that wrote token fields, or
  wrote `undefined` into one, puts a token-less phantom into the broadcast
  audience. The old shape was bitten by exactly this in `bind`; the rule
  survives the move, only its subject narrowed from the row to the tokens.
- **Delete the row on sign-out, and on account deletion.** This REVERSES the
  old "release, don't delete" rule, which existed because the row outlived the
  session. It does not any more: the row *is* the session, a signed-out device
  receives nothing, and leaving its tokens addressable is the bug rather than
  the safeguard. `purgeUserSubtree`'s `listCollections()` walk reaches the
  subcollection for free, so `deleteAccount`'s "device purge must precede the
  session teardown" ordering can be deleted outright, not reordered.
- **Clear the registration signature cache across a session change.**
  `DevicePushCoordinator.swift:131` skips a POST whose body is unchanged, and
  after sign-out-then-sign-in the body is identical while the row is gone. §8.
- **A missing collection-group index fails the query, it does not degrade.**
  And this repo has never managed one, so verify on staging rather than
  assuming what Firestore indexes automatically. §3, §12 P2a.
- **`tx.get()` on a collection group query is untested here.** §4.1 puts one on
  the login path. Prove it serialises like a plain query before it ships.
- **`users.stations` is a REQUIRED field in the released Android APK.** So are
  `uid`, `email` and `displayName`. Omit or null any of the four and every
  production Android login throws `MissingFieldException` — and `stations` is
  the legacy v1 board list, i.e. precisely what a future cleanup would reach
  for. `getUserProfile`'s `data.stations ?? []` is a compatibility guarantee,
  not a defensive habit. §11.1.
- **Adding fields to the user document is safe; removing them is not.** The
  released APK parses with `ignoreUnknownKeys`, which is why `boards`,
  `boardsUpdatedAt` and now `stateRev` ride along harmlessly. The asymmetry is
  the whole compatibility story.
- **The legacy FCM purge stays gated on last-device-out.** Keyed by token;
  cannot be per-device for the frozen APK.
- **Registry writes: field paths + `FieldValue.delete()` at zero.** `set(merge)`
  deep-merges maps and ignores absent keys — deletes vanish. Already bitten.
- **Firestore rejects `undefined` anywhere in a write** (`ignoreUndefined` is
  off) — strip before writing, especially `DeviceInfo` fields.
- **A zero-device audience is silent.** Verify audiences, not send outcomes.
- **`allowEmpty` and the login-restore guards are load-bearing** — the empty
  push permission is granted by a user action with the live list, never a
  literal.
- **Never sign out on a bare 401** — only `account_gone`. The asymmetry is the
  safety.
- **The rev ledger is a cache, Firestore is the truth.** On any doubt
  (restart, missing row), read the doc once and reseed — never guess, never
  serve a rev you cannot back.
- **Do not add client-side Firestore access** — §6.4's rejected alternative,
  and the reason this whole design needed no client migration.
