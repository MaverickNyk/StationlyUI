# Stationly Support & Contributions — Master Full-Stack Reference & Session Record

> **Master Reference:** This document unifies and consolidates the full-stack design, backend architecture, mobile client implementation, code review findings, Stripe configuration, and deployment guide for the **Support / Contributions ("Keep Stationly Free")** feature across both `stationly-backend` and `StationlyUI`.

---

## 1. Executive Summary & Philosophy

Every planned premium feature across Stationly is now **free forever** (no paywalls, no ads, no locked stations). Ongoing operational costs (TfL live data feeds, Firestore, push notifications, hosting) are funded through **voluntary developer contributions**:

> **Current routes (2026-11-22).** Three payment sources, one ledger. Each
> store app takes tips only through its own store, as both stores require; the
> website keeps Stripe. Every route writes to the same `users/{uid}.supportMoney`
> array, and the server derives the Supporter badge from it.

| Surface | Route | Ledger id | Store cut | Status |
|---|---|---|---|---|
| **iOS app** | StoreKit 2 consumables (`uk.co.stationly.support.t4/t8/t12/t25`) | `apple_<transactionId>` | 15% | Live |
| **Android app** | Google Play Billing one-time products (same ids) | `google_<orderId>` | 15% | Built, OFF until Play setup (§8) |
| **Website** `/support/` | Stripe Payment Links | Stripe Checkout Session id | 0% | Live |

A contribution grants an account-level **Supporter badge** for 30 days. Nothing
functional is gated, on any route. (Sections 2 to 7 describe the Stripe era in
which this document began; where they disagree with the table above, the table
is current.)

### Invariant Rules
1. **Never a Paywall:** Nothing already free ever moves behind a paywall.
2. **Support, Not Donations:** Wording strictly avoids "donation" / "donate" (Stationly is not a registered charity).
3. **No Metaphors / No Novelty Drinks:** The "buy me a coffee" framing was completely retired in favor of plain, honest sentences explaining server running costs.
4. **No Public Transaction Ledger:** The user sees their supporter status and lifetime count (`count`), but there is no public transaction list or history screen.
5. **Structural Monotonicity:** Supporter duration is measured from the latest transaction; an additional small contribution can never shorten an existing active badge.

---

## 2. End-to-End System Architecture

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. USER INITIATION                                                                     │
 │    Profile Screen Support Card  OR  Home Contextual Banner (after adding a board)     │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │ User taps tier (£4, £8, £12, custom)
                                             ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 2. SAFARI CHECKOUT (SFSafariViewController / Custom Tabs)                             │
 │    Opens: https://buy.stripe.com/live_…?client_reference_id=<firebase_uid>            │
 │    User pays via Apple Pay / Card (1-tap native wallet sheet)                          │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │ Stripe redirects to success URL
                                             ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 3. BROWSER BOUNCE PAGE                                                                 │
 │    GET https://api.stationly.co.uk/api/v1/support-money/return?session_id=cs_…        │
 │    Immediate redirect: window.location = "stationly://support-money/thanks?…"          │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │ App catches deep link
                                             ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 4. CLIENT GRATIFICATION (OPTIMISTIC)                                                   │
 │    • dismissCheckout() closes Safari sheet                                             │
 │    • SupportStore.recordContribution() (10-min local optimistic window)                │
 │    • SupportThanksOverlay triggers celebratory multi-colored confetti & haptics        │
 │    • Supporter avatar badge lights up immediately                                      │
 └────────────────────────────────────────────────────────────────────────────────────────┘

 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 5. ASYNC AUTHORITATIVE WEBHOOK (INDEPENDENT SERVER PATH)                               │
 │    Stripe POST https://api.stationly.co.uk/api/v1/webhooks/stripe                      │
 │    ├── 1. Rate limiter (300/5min per IP)                                               │
 │    ├── 2. Raw body extraction (express.raw before express.json)                        │
 │    ├── 3. Constant-time HMAC-SHA256 signature verification (±300s replay window)       │
 │    ├── 4. Environment livemode verification                                            │
 │    ├── 5. SQLite idempotency ledger check (stripe_events table)                        │
 │    └── 6. Firestore atomic transaction:                                               │
 │           • Appends SupportTxn { txnId, atMs, amountMinor, currency } (capped at 50)  │
 │           • Increments stateRev = FieldValue.increment(1)                              │
 │           • Triggers afterContentWrite(uid, 'profile') WebSocket fan-out               │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │ Cross-device sync push / socket
                                             ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 6. CROSS-DEVICE RECONCILIATION                                                         │
 │    Other signed-in devices fetch GET /user/sync/profile                                │
 │    Server projects supportMoney { isActiveSupporter: true, count: N, entries: [...] }   │
 │    Supporter badge appears on all devices without needing IAP receipt restore          │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Backend Implementation Details (`stationly-backend`)

### 3.1 Key Files and Routes
- `src/utils/stripeSignature.ts`: Hand-rolled constant-time HMAC-SHA256 signature verifier with clock-skew replay tolerance.
- `src/services/supportMoneyConfigService.ts`: Server-driven UI configuration generator (both structured object and flat string map for `home-config`).
- `src/services/supportMoneyService.ts`: Webhook event processor, idempotency manager, and transaction handler.
- `src/controllers/stripeWebhookController.ts`: 7-layer guarded webhook receiver mounted at `POST /api/v1/webhooks/stripe`.
- `src/controllers/supportMoneyReturnController.ts`: Branded post-checkout bounce page mounted at `GET /api/v1/support-money/return`.
- `src/services/userService.ts`: Document ledger manager (`recordSupportMoney`), client response projection (`projectSupportMoneyForClient`), and forge protection (`PROTECTED_PROFILE_FIELDS`).

### 3.2 Data Models & Firestore Schema

#### Stored Document (`users/{uid}.supportMoney`)
```ts
export interface SupportMoneyEntry {
    txnId: string;        // Stripe Checkout Session ID (e.g. cs_live_...)
    atMs: number;         // Epoch timestamp in milliseconds
    amountMinor: number;  // Amount in pence/cents (e.g., 800 for £8.00)
    currency: string;     // Currency ISO code (e.g., 'GBP')
}
// Array stored directly on users/{uid}, capped at MAX_SUPPORT_MONEY_ENTRIES (50)
```

#### Wire Contract (Projected on `getUserProfile` and `createOrUpdateUser`)
```ts
export interface SupportMoneyStatusView {
    isActiveSupporter: boolean; // nowMs - latest.atMs < badgeDurationMs
    count: number;             // Lifetime contribution count
    entries: SupportMoneyEntry[]; // Contains only the single latest entry
}
```

### 3.3 Security & Idempotency Guarantees
1. **Fail-Closed Verification:** If `STRIPE_WEBHOOK_SECRET` is unset, the webhook returns HTTP `503`. Invalid signatures or timestamps outside ±300s return HTTP `400` with no diagnostic error leaked.
2. **Dual-Layer Idempotency:**
   - Transport Layer: Checked against SQLite `stripe_events` table.
   - Document Layer: `recordSupportMoney` checks if `txnId` already exists anywhere in the user's `supportMoney` array.
3. **Delayed Settlement Safety:** Both `checkout.session.completed` and `checkout.session.async_payment_succeeded` map to the same session `txnId`, preventing double-crediting on delayed payment methods.
4. **Client Anti-Spoofing:** `'supportMoney'` is in `PROTECTED_PROFILE_FIELDS`; client POST requests to `/user/sync/profile` cannot mutate or forge supporter status.

---

## 4. Mobile Client Implementation Details (`StationlyUI`)

### 4.1 Architecture & Components
- **`SupportMoneyConfig.kt`**: SDUI parser decoding `support_money.card.json` from `homeConfig` with a safe, offline-ready fallback.
- **`SupportStore.kt`**: Durable per-account KV store tracking `quietUntilMs`, `activeDays`, and local optimistic contributions (`contributedOptimistically`).
- **`SupportViewModel.kt`**: Root-level coordinator managing UI state, debounced server syncs (`syncMutex`), nag policy evaluation, and celebration triggers.
- **`SupportSheet.kt`**: Compose bottom sheet displaying the £4 / £8 / £12 / custom amount ladder with real-time story hints.
- **`SupportThanksOverlay.kt`**: Full-screen celebration overlay rendering physics-driven multi-colored confetti cannons and synchronized haptics.
- **`SupportProfileCard.kt` & `SupporterAvatarBadge`**: Profile and top-bar avatar badges with background-matched cut-out rings.
- **`SupportCheckout.kt`**: the per-platform payment seam. `startNativePurchase` (StoreKit on iOS, Play Billing on Android), `nativeProductId`, `resumeNativePurchases`, and the dormant web-checkout path the store apps no longer use (see §8).

### 4.2 Nag Policy & Display Logic
- **Contextual Home Banner:** Appears only after a new board is added, provided `boards_count >= min_boards` (default 2) and `active_days >= min_days` (default 3).
- **Suppression Windows:**
  - "Maybe later" dismissal silences the banner for **3 days** (`SKIP_DAYS = 3`).
  - Making a contribution silences the banner for **30 days** (`QUIET_AFTER_CONTRIBUTION_DAYS = 30`).
- **Optimistic Grace Window:** The device retains supporter status locally for **10 minutes** (`OPTIMISTIC_WINDOW_MS = 600_000L`) immediately after checkout to prevent UI flicker while the Stripe webhook is in flight.

---

## 5. Stripe Console Configuration & Production Inventory

### 5.1 Payment Links & IDs

| Tier | Amount | Link ID | Product Description |
|---|---|---|---|
| **Tier 1 (`t4`)** | **£4.00** | `SUPPORT_MONEY_PAYMENT_URL_T4` | 1 day live · Covers live departures across the network |
| **Tier 2 (`t8`)** | **£8.00** (Default) | `SUPPORT_MONEY_PAYMENT_URL_T8` | 3 days live · Supports stops without physical departure boards |
| **Tier 3 (`t12`)** | **£12.00** | `SUPPORT_MONEY_PAYMENT_URL_T12` | 1 week live · Covers full servers and live data feeds |
| **Custom** | **£1.00 – £500.00** | `SUPPORT_MONEY_PAYMENT_URL_ONEOFF` | Choose your own amount (Preset: £8.00) |

### 5.2 Redirect & Webhook URLs
- **Stripe Success Redirect URL:**
  ```text
  https://api.stationly.co.uk/api/v1/support-money/return?session_id={CHECKOUT_SESSION_ID}
  ```
- **Stripe Webhook URL:**
  ```text
  https://api.stationly.co.uk/api/v1/webhooks/stripe
  ```
- **Webhook Events Subscribed:**
  - `checkout.session.completed`
  - `checkout.session.async_payment_succeeded`

---

## 6. Full-Stack Production Deployment Runbook

### Step 1: Stripe Live Setup
1. Create the 4 Payment Links in Stripe Live Mode using the amounts above.
2. In each link's settings, set the redirect URL to `https://api.stationly.co.uk/api/v1/support-money/return?session_id={CHECKOUT_SESSION_ID}`.
3. Configure the webhook endpoint for `https://api.stationly.co.uk/api/v1/webhooks/stripe` and copy the signing secret (`whsec_...`).
4. Ensure Apple Pay and Cards are enabled under Payment Methods.

### Step 2: Backend Configuration & Deployment
1. Set the following environment variables in production (`.env` / PM2):
   ```bash
   SUPPORT_MONEY_ENABLED=true
   STRIPE_WEBHOOK_SECRET=whsec_...
   SUPPORT_MONEY_PAYMENT_URL_T4=https://buy.stripe.com/...
   SUPPORT_MONEY_PAYMENT_URL_T8=https://buy.stripe.com/...
   SUPPORT_MONEY_PAYMENT_URL_T12=https://buy.stripe.com/...
   SUPPORT_MONEY_PAYMENT_URL_ONEOFF=https://buy.stripe.com/...
   SUPPORT_MONEY_MIN_BOARDS=2
   SUPPORT_MONEY_MIN_DAYS=3
   SUPPORT_MONEY_BADGE_DURATION_DAYS=30
   ```
2. Deploy the backend branch and reload PM2:
   ```bash
   pm2 reload stationly-backend
   ```
3. Run verification test:
   ```bash
   curl -sS https://api.stationly.co.uk/api/v1/sdui/app/support-money-config | grep '"enabled":true'
   ```

### Step 3: Client Release (StationlyUI)
1. Merge the `ios-parity` working tree.
2. Build and assemble iOS framework:
   ```bash
   ./gradlew :composeApp:assembleComposeAppReleaseXCFramework
   ```
3. Archive and submit the iOS app via Xcode / App Store Connect.
4. *App Store Review note:* Conforms with Apple App Store Guideline 3.2.1 (vii) for voluntary developer contributions outside IAP.

---

## 7. Verification & Test Audit Record

- **Backend Test Suite:** `npm test` $\rightarrow$ **180 / 180 tests passing** (covering signature validation, replay attacks, duplicate transaction prevention, deleted account attribution, and login contract projections).
- **Mobile Client Test Suite:** `./gradlew check` / `:composeApp:testDebugUnitTest` $\rightarrow$ **All tests passing** (covering optimistic expiry, nag quiet policies, attribution encoding, fallback voice rules, and currency formatting).
- **On-Device Live Verification:** Completed end-to-end checkout flow on iPhone test device:
  - Safari opens with Apple Pay ready.
  - Bounce page redirects seamlessly back to `stationly://support-money/thanks`.
  - Safari sheet auto-dismisses.
  - Confetti burst & haptics fire.
  - Firestore updates transaction ledger.
  - Supporter avatar badge renders on Home and Profile screens.

---

## 8. Android: Google Play Billing (AV2-7.4, decision D11)

### 8.1 Why Play Billing
Google Play treats a tip to the developer as a digital purchase, which must use
Play Billing; the external-checkout exemption covers registered charities only.
So Android mirrors iOS's StoreKit route, not the website's Stripe one. The
backend enforces it: store apps are served only their own store's product ids
and no Stripe links (`SupportMoneyConfigService.getSupportMoneyConfig`,
`isStoreApp`).

### 8.2 The life of one purchase
```text
 Sheet "Pay £8"
   └─ SupportViewModel.payTier ─ startNativePurchase(google_product_id)
        └─ PlayBilling.purchase ─ Play sheet, bound to sha256(uid)
             ├─ PURCHASED ─ thank-you now (SupportReturn, local, cosmetic)
             │     └─ POST /api/v1/support-money/verify-play  (Firebase bearer)
             │          └─ GooglePlayIAPService
             │               1. Google: purchases.products.get (real? completed?)
             │               2. obfuscatedExternalAccountId == sha256(bearer uid)
             │               3. ledger += google_<orderId> at OUR tier price
             │               4. Google: consume (server-side, after recording)
             ├─ PENDING / failure ─ StoreNotice shown under the Pay button
             └─ app dies before step 4 ─ purchase stays unconsumed
                   └─ next Home/Profile entry (5 min apart) or launch: PlayBilling.resume sends it again (idempotent)
```

### 8.3 Files
| Repo | File | Role |
|---|---|---|
| StationlyUI | `composeApp/src/androidMain/.../support/PlayBilling.kt` | The purchase flow: launch, results, resume |
| StationlyUI | `.../support/PlayBillingConnection.kt` | One BillingClient, reconnected on demand |
| StationlyUI | `.../support/PlayAccount.kt` | Account binding `sha256(uid)` and the device-side session id |
| StationlyUI | `.../support/SupportCheckout.android.kt` | `checkoutSupported = true`, the actuals |
| StationlyUI | `commonMain/.../support/SupportSignals.kt` | `StoreNotice` / `StoreNotices`: the store's answers, shown in the sheet |
| StationlyUI | `core/.../service/SduiApiService.kt` | `verifyPlayPurchase`, `PlayVerifyOutcome.from` |
| backend | `src/services/googlePlayIAPService.ts` | Verify, bind, record, consume |
| backend | `src/controllers/supportMoneyController.ts` | `verifyPlay` route handler |
| backend | `src/routes/apiRoutes.ts` | `POST /support-money/verify-play`, bearer + strict rate limit |

### 8.4 Guarantees
- **Never consumed before it is recorded.** Only the server consumes. A crash,
  timeout or server error leaves the purchase for a retry (at most every 5 minutes) or the next launch.
- **Never credited to the wrong account.** The account binding is checked
  server-side, and the client skips purchases bound to a different account.
- **Never credited at a client-chosen price.** The amount is the served tier's
  `amount_minor` for that product id.
- **Never refused for our own fault.** A 401/403 from Google (our credentials)
  or any 5xx is "retry later", never "rejected"; a rejected purchase is
  refunded by Google after three days, so the distinction is a user's money.
- **One thank-you per purchase.** A retry of an already-credited purchase
  returns `recorded: false`, and the app does not celebrate it again.

### 8.5 Configuration
| Setting | Default | Notes |
|---|---|---|
| `SUPPORT_MONEY_ANDROID_ENABLED` | `false` | The switch. Listed in `.env.remote`, so a deploy can set it per environment. |
| `GOOGLE_PLAY_KEY_PATH` | Firebase Admin key | Service account with Play Console access. |
| `GOOGLE_PLAY_PACKAGE_NAME` | `com.stationly.mobile` | Staging and prod share it, so they share one product list. |
| `SUPPORT_MONEY_GOOGLE_PRODUCT_T4/_T8/_T12/_T25` | `uk.co.stationly.support.<tier>` | Per-tier override. |
| home-config `support_money.store.*` | served | The four `StoreNotice` wordings. |

### 8.6 Go-live checklist
1. Upload one build with the billing library (it adds the `BILLING` permission)
   to the Internal testing track. Play does not unlock product creation before.
2. Create the four one-time products at £4 / £8 / £12 / £25 and activate them.
   Prices must match the tiers: the server credits the tier price.
3. License testing: add the testers' Google accounts.
4. In BOTH Cloud projects (`mindthetimefcm` staging, `stationly-prod`), enable
   the **Google Play Android Developer API**, and invite each project's
   `firebase-adminsdk-fbsvc@…` account in Play Console with "View financial
   data" and "Manage orders and subscriptions".
5. Staging: `SUPPORT_MONEY_ANDROID_ENABLED=true`, buy as a tester, check the
   badge and the `google_` ledger row. Then production.

### 8.7 Verified so far (staging, 2026-11-22, Pixel 10 Pro Fold)
Config per platform correct; verify route refuses without a bearer; the
launch-time resume connects and finds nothing; sheet from Profile and home;
every tier reaches Play and shows the "unavailable" notice (no products yet);
no browser ever opens; no crash. Google's API answered 403 "API not enabled" for
the staging project, which is checklist step 4.

**Production, 2026-11-24 (Pixel 11 Pro, Play install): Play's side works, the
server's does NOT.** The test payment completed and the mark appeared, then went
after about ten minutes. The mark at that point is only the device's optimistic
window (`OPTIMISTIC_WINDOW_MS`), so it proves nothing about the server; an earlier
version of this note wrongly said it did. Cause: `StationlyAuth` adds the bearer
only to paths containing `/user/`, and `verifyPlayPurchase` posts to
`/support-money/verify-play`, which requires one. Every Android purchase gets a
401, is never recorded and never consumed, so Google refunds it (a license
tester's after 3 minutes, a real one after 3 days). The server's own Google access
is therefore still untested. Fixed in `StationlyAuth.needsUserToken`, pinned by
`UserTokenRoutesTest`; NOT yet verified on a device. The result of each check is
now logged (`PlayBilling`: `verify <product>: <outcome>`). To prove a purchase was
recorded, look at the mark AFTER fifteen minutes, or the ledger row.

Trap: an internal tester is charged real money unless the account is ALSO on
Play Console's License testing list. With several Google accounts on the phone,
Play uses the one that downloaded the app.

### 8.8 Tests
- backend `src/tests/run.ts`: per-platform config, account binding vector,
  completed / pending / cancelled / unknown / foreign / duplicate / deleted
  account, oversized token, notice copy served.
- `PlayBillingTest` (androidUnitTest): binding vector (same as the backend's),
  64-char limit, session id, Android payability.
- `PlayVerifyOutcomeTest` (core): every HTTP answer maps to the right outcome.
- `StoreNoticeTest` (commonTest): served copy wins, fallbacks follow the voice
  rules.

### 8.9 Known iOS issues (documented, deliberately not fixed)
See `docs/android-v2/epics/EPIC-07-release-surfaces.md`, AV2-7.4. The owner's
call: iOS works, and its payment code stays exactly as it is until revisited.


### 8.10 Impact on live clients (checked 2026-11-22)
Every response a live client reads (support card, home-config strings, About
layout) was generated from `main` and from this change for each client type and
diffed:

| Client | Difference | Effect |
|---|---|---|
| iOS 1.1 (`X-Stationly-Client: ios`) | each tier gains `"google_product_id": ""`; four new `support_money.store.*` strings | None: its parser ignores unknown fields (`ignoreUnknownKeys`) and keys. Apple ids, prices, links and flags identical. |
| Android v1 (no client header, so `unknown`) | each tier gains a Google id beside the Apple id; the four strings | None: v1 has no support code. Its Stripe links and flags are unchanged. |
| `android` (v2 builds only) | Stripe fields blank, custom amount off, Google ids instead of Apple | Off in production (`SUPPORT_MONEY_ANDROID_ENABLED` unset). |
| Everyone, About | "DLR, and Overground — all" becomes "DLR and Overground, all" | Text only (house copy rule: no dashes). |

The iOS `verify-iap` route and `appleIAPService.ts` are unchanged.

---

## 9. Reach and measurement (2026-11-26)

**Why.** iOS has taken no money since launch. Production already served
`min_boards=1` / `min_days=1`, so the old ask did fire once, after someone's
first board, and then almost never again: a commuter sets up one or two
stations on day one and never adds another. Nothing was logged, so nobody could
tell whether people were never asked, said no, or hit a broken payment.

### 9.1 Logging (the funnel)
Activity events, uploaded with the rest of the trail (`ActivityEvents`, core):

| Event | Props | Where |
|---|---|---|
| `support.asked` | `trigger` board_added / on_open | banner raised (`SupportViewModel.evaluateBanner`) |
| `support.ask_dismissed` | `trigger` | "Maybe later" |
| `support.sheet_opened` | `from` banner / profile / whats_new, `progress` | sheet opens |
| `support.sheet_closed` | | sheet closed without a thank-you |
| `support.tier_tapped` | `tier`, `started` | an amount tapped |
| `support.purchase` | `result`, `product`, `code` | Play's answer (Android only; iOS StoreKit is Swift and untouched) |
| `support.verified` | `outcome` credited / already_credited / rejected:x / retry:status | OUR server's answer to a Play purchase |
| `support.thanks_shown` | `tier` | thank-you played |
| `whats_new.shown` / `.dismissed` (`via`) / `.support_tapped` | | What's new screen |

Read them with `node src/scripts/support_funnel.cjs --key=<service account> [--days=N]`
in stationly-backend: per platform, how many times and how many people reached
each step, the step rates, and the payments the server recorded. Read-only.
Activity uploads overnight, so leave two days before trusting a week.

### 9.2 The ask on reaching home
Same banner, second trigger. Raised on reaching the home screen with at least
one board, once the person has used the app on `ask_min_days` separate days
(default 7), at most once every `ask_every_days` (default 14) whatever they
answered (`SupportState.lastAskedMs`). "Maybe later" and a contribution still
silence it as before. Served per platform: `home.promo.support_money.ask_on_open`
is `true` only for the platforms in `SUPPORT_MONEY_ASK_ON_OPEN_PLATFORMS`
(default `android`), so iOS 1.1 and any future iOS build stay off until the
operator adds iOS. The decision is one pure function, `askTriggerAt`, tested in
`SupportReachTest`. Days of use are now also counted on every home visit, not
only on an account change.

### 9.3 Running costs line
`SUPPORT_MONEY_MONTHLY_COST_MINOR` (pence; 60000 = £600, set in the backend's
`.env.defaults` with the other reach settings, not GitHub secrets) turns on
`support_money.progress.*`:
"Stationly costs about £X a month to run. Supporters have given £Y so far in
<month>." with a bar, in the amounts sheet and on the Profile card. Off when
unset. Until the month's REAL total reaches `SUPPORT_MONEY_SHOW_RAISED_FROM_MINOR`
(default 10000, £100), only "Stationly costs about £X a month to run. Every bit
of support goes straight into that." shows, with no figure and no bar: a £0 line
read as "nobody cares" (owner, 2026-11-26). The figure is never invented. Owner's
monthly cost: £600 (60000). £Y is this London month's GBP payments, gross (before store cuts, hence
"given", never "covered", even once the month is met), summed from
`support_money_txns/{txnId}`: one row per payment, written after the ledger
write by `UserService.recordSupportMoney` and `recordWebDonation`
(`SupportMoneyStatsService`). Keyed by transaction id, so retries cannot count
twice; cached 10 minutes per process; never on the payment's critical path.
Rows start from the deploy: payments before it are not in the total.

### 9.4 What's new (Android v1 → 2.0, once)
`V1Upgrade.mark` (the v1 → 2 database migration) writes the durable string
`whats_new_v2_pending` (`WhatsNewMarker`). The home screen, once boards have
loaded, shows `WhatsNewScreen` at the app root; it ends with one honest line
about support and two buttons, "Support Stationly" (filled, on top) and "Got it"
(outlined). Either button or back spends the marker. Copy is served
(`WhatsNewService`, `whats_new.v2.*`, `WHATS_NEW_V2_ENABLED`) with the same
words compiled in. Fresh installs, later updates and iOS never see it.

### 9.5 iOS safety
The live iOS 1.1 app is a compiled binary; nothing here changes its code. Its
only contact is home-config, and a before/after render for `ios` showed zero
changed or removed keys and 11 added string keys it has no code to read, with
`ask_on_open = false`. Nothing touches the widget.

### 9.6 Verified
Backend tests 283/283. App gate green incl. iOS compile. On the Pixel 10 Pro
Fold (staging, 2026-11-26): What's new shows with the marker, both buttons
work, the marker is spent, Support opens the sheet on top; every event above
except `asked`, `verified` and `thanks_shown` seen in the local queue; Play's
sheet opened and was backed out of (nothing bought), logged as `cancelled`.
Later the same day, backend on staging (with `ASK_MIN_DAYS=1` for the test,
then put back to 7): the on-open ask appeared on reaching home, below the board
without covering it; "Support Stationly" opened the sheet with the £600 cost
line (no figure, no bar, since nothing raised); the Profile card shows the same
line; the ask did not come back on returning home or reopening; "Maybe later"
closed it and set 3 quiet days. Logged: `asked` (on_open), `sheet_opened`
(banner, progress true), `sheet_closed`, `ask_dismissed`. The test account's
local support record was restored byte for byte afterwards.
**Not yet seen on a device:** the bar with a real figure (needs £100+ in a month),
and `support.verified` / `thanks_shown` (need a real test purchase).

### 9.7 Reviews and what the logging caught (2026-11-26)
Two xhigh code reviews (backend, app). Fixed before release:
- **Apple amounts were 1000x too high.** JWS `price` is in milliunits; the
  service multiplied it by 100. It now credits OUR tier price, as Play does.
  No real iOS payment existed, so no rows needed correcting.
- **Test money out of the public total.** `recordSupportMoney({ countInTotal })`:
  false for Play license testers (`purchaseType 0`), Apple non-Production, and
  any Apple receipt whose signature did not verify. The ledger row is still written.
- **A forged purchase date could crash a worker** (`londonMonth` RangeError in an
  un-awaited promise). `recordTxn` now cannot throw; its caller also catches.
- **"Covered" with gross money.** The met line is now "Supporters have given more
  than {cost} in {month}", and only when strictly more.
- Monthly figure: hidden until summed after a restart (no cached zero), marked
  stale on a payment instead of bumped (no double count), backs off after a
  failed read; threshold accepts 0 and is clamped to the cost.
- Purchase tokens no longer reach server logs (`describePlayError`).
- Tests no longer write `web_donations` rows or send thank-you emails.
- App: stale-snapshot state writes after a suspend, thanks logged once per
  payment, web-route taps logged, What's new and the board-added ask never in
  one visit, screen readers read the cost line once.

Caught by the new logging on the Fold, not by review: **17 taps on Pay in five
seconds** (Play's sheet takes a moment to appear) launched 17 purchases, all
refused with DEVELOPER_ERROR (code 5). `PlayBilling.purchase` now runs one
launch at a time; verified: 3 rapid taps, one sheet, no error. Also Android
never logged `app.opened`, so the funnel had no Android baseline; MainActivity
now logs it in onStart.

Deferred to the next release: AV2-11.52 (unverified Apple receipts still
credited to the account, refunds, backfill, gross figure). The iOS database
migration (Q5) must be fixed before the next iOS build from this branch.
