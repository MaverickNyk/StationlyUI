# iOS Infra/Code-Quality Audit — 2026-07-20 (first pass, session-budget-limited)

First-pass audit from direct observation while working in this code this
session — NOT an exhaustive line-by-line sweep (ran out of session budget
before a full parallel audit could complete). Treat as a prioritized starting
list, verify file:line specifics before acting.

## 🔴 Security — top priority

1. **Firebase auth token stored in plain NSUserDefaults, not Keychain.**
   `iosApp/iosApp/AuthBridge.swift` `persistUserIdentity`/`storeUserInfo`
   write `firebase_auth_token` + user identity into the App-Group
   NSUserDefaults suite. NSUserDefaults is NOT encrypted at rest — an
   unencrypted device backup or jailbreak exposes it in plaintext. Keychain
   (with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` +
   `kSecAttrAccessGroup` for the app-group) is the correct store for this.
   This is the single highest-value fix in this list.

2. **`print(...)` debug logging ships into Release** — e.g.
   `AuthBridge.swift` `[AuthBridge] Token refresh failed: ...`. Low
   sensitivity (error descriptions, not raw tokens) but should be gated
   behind `#if DEBUG` or migrated to `os.Logger` with a non-public privacy
   level, since `print` output can be captured by unsigned Console.app
   access.

## 🟡 Observability

3. **No crash reporting / structured logging on iOS.** Firebase is
   integrated for Auth/Messaging but I did not see a Crashlytics (or
   equivalent) hookup anywhere in `iosApp/`. A production crash today
   produces zero telemetry — you'd only hear about it if the user reports
   it. Recommend `FirebaseCrashlytics` (already in the Firebase family
   you're using) or `os.Logger` subsystems at minimum.

## 🟡 Testing

4. **Zero test coverage found** — no `Tests/` target, no `XCTest` files, no
   Kotlin `test` source set referenced anywhere in `composeApp/` or
   `iosApp/` encountered this session. (Android's parity is unverified —
   didn't check `android/app` test coverage before running out of budget.)

## 🟡 Accessibility

5. **Widget text uses fixed point sizes** (`iosApp/StationlyWidget/WidgetViews.swift`
   — `LiveAgo`, `LiveClock`, `DotMatrixRow`, etc. all use
   `.font(.system(size: fixedNumber))`). This does NOT respond to the
   user's Dynamic Type accessibility setting — a low-vision user gets no
   relief on the widget regardless of their system text-size preference.
   WidgetKit widgets are commonly like this industry-wide, but worth a
   deliberate decision rather than an accident.
6. Compose-side `contentDescription`/semantics coverage on icon-only
   buttons was not fully audited — flag for a follow-up pass.

## 🟢 Architecture / duplication (lower urgency)

7. **`composeApp/src/androidMain` is a verification-only target that ships
   nothing** (confirmed this session — it was actually broken/non-compiling
   until I fixed it). Worth a deliberate decision: keep it as a
   compile-verification tripwire (current value) or delete it entirely to
   stop it silently drifting again.
8. **Hand-rolled service locator** (`Platform.storageManager`/`.sqlStorage`/
   `.notificationManager`/`.widgetManager`, `NetworkModule.sduiApi`/`.tflApi`)
   — global singletons, not a DI framework. Works, but low testability and
   hidden init-order dependencies. Not urgent given no test suite exists yet
   anyway (#4).
9. **Error-swallowing pattern is widespread**: many `catch (_: Exception) {}`
   blocks across ViewModels. Several are legitimately "best-effort, must not
   block the critical path" by design (e.g. FCM token registration during
   login — has a comment explaining why). Others may be silently eating
   real failures the user should see. Needs a per-call-site pass, not a
   blanket fix — flagged as a category, not individually verified this
   session.
10. **AuthBridge command-protocol race window**: `handleUserDefaultsChange`
    clears `auth_pending_command` at the very top, before the async Firebase
    call runs — a second command written before `auth_command_done` fires
    could interleave. Mitigated today by `AuthenticatingOverlay` blocking all
    touch input during `isAuthenticating`, so likely low real-world risk, but
    worth a defensive guard (ignore new commands while one is in flight) if
    it's ever reachable another way.

## Not yet audited (ran out of budget)
Full concurrency/retain-cycle sweep, complete dead-code sweep, `.gitignore`/
secrets-in-git-history check, ATS/entitlement hygiene in `project.yml`,
environment base-URL config safety. Resume with a fresh, narrower-scoped
pass per item above rather than a repeat full sweep.
