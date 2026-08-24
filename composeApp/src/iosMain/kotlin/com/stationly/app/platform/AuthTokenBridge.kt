package com.stationly.app

import com.stationly.core.platform.IosAuthTokenAuthority

/**
 * Lets Swift answer the one question every outbound request asks: what bearer
 * token should this carry?
 *
 * ## Why the direction is unusual, and why it has to be
 * Every other bridge in this app runs Swift → Kotlin: `UserSyncBridge`,
 * `ActivityBridge`, `RefreshScheduleBridge`. This one runs the other way,
 * because the fact it needs lives on the Swift side and cannot be moved.
 * FirebaseAuth is a Swift-only SDK; Kotlin cannot call `Auth.auth()` at all. So
 * the shared network layer — which is where the token is actually needed, on
 * every request under `/user/` — has to be able to reach into Swift for it.
 *
 * The alternative was the existing `auth_pending_command` NSUserDefaults
 * protocol, and it is the wrong instrument here: `IosPlatformAuthProvider` polls
 * it every 250 ms, which is fine for a login button and unacceptable in front of
 * every HTTP call. See [IosAuthTokenAuthority] for the failure that made this
 * necessary in the first place.
 *
 * ## Why the seam is in composeApp and the authority is in core
 * Only `composeApp` is compiled into the framework Swift imports; `core` is an
 * `implementation` dependency, so none of its declarations appear in the
 * generated header — which is also why `AppGroupKeys` has a hand-kept Swift
 * twin. The authority therefore lives in `core`, next to `Platform`, and this
 * object exists solely to be visible from Swift and hand the resolver across.
 */
object AuthTokenBridge {

    /**
     * Install the Swift resolver. Called once, from
     * `AppDelegate.didFinishLaunchingWithOptions`.
     *
     * Must run before anything can issue a request under `/user/`. Registering late
     * is not fatal — [IosAuthTokenAuthority] falls back to the stored token, the
     * old behaviour — but it is exactly the window in which the stale-token
     * logout used to happen, so the registration is placed with the other
     * must-happen-at-launch wiring rather than lazily on first use.
     *
     * Idempotent: re-registering replaces the resolver, which is what a second
     * launch path (a background task following a foreground) should do.
     */
    fun register(resolver: IosAuthTokenResolver) {
        IosAuthTokenAuthority.resolver = { forceRefresh, completion ->
            resolver.resolveToken(forceRefresh = forceRefresh, completion = completion)
        }
    }

    /**
     * Forget the cached token.
     *
     * Called from Swift's `AuthBridge.clearUserInfo()`, i.e. on every teardown.
     * Without it the authority would keep serving the ended session's token from
     * memory until it aged out — including to the NEXT account, since nothing
     * else in this process clears that cache. `Platform.signOutFromAuthExpiry`
     * calls the same thing for the 401-driven path.
     */
    fun invalidate() {
        IosAuthTokenAuthority.invalidate()
    }

    /**
     * Crossings into Swift since launch — the fast path's only observable.
     *
     * Exposed so a device test can assert the shape that matters: a burst of
     * requests on a healthy token must not move this. See
     * [IosAuthTokenAuthority.crossings].
     */
    fun crossings(): Int = IosAuthTokenAuthority.crossings
}

/**
 * The Swift half of [AuthTokenBridge], implemented in `AuthBridge.swift`.
 *
 * An interface rather than a bare Kotlin function type on purpose: Kotlin/Native
 * boxes the primitives of an exported *function type*, so the Swift signature
 * would arrive as `(KotlinBoolean, @escaping (String?) -> Void) -> Void`. As an
 * interface method it maps to plain `Bool`, and the Swift implementation reads
 * like Swift.
 */
interface IosAuthTokenResolver {
    /**
     * Produce an ID token for the signed-in user, or null.
     *
     * Null covers three different situations and the caller must not try to tell
     * them apart: nobody is signed in, the Keychain is unreadable (a locked
     * device, or a `devicectl`-launched process), or the refresh failed. None of
     * them is a sign-out — see `AuthBridge.settleAuthState` for why an absent
     * credential is not evidence of an absent account.
     *
     * @param forceRefresh true only on the 401-retry path. False means "the SDK
     *   decides", which is `getIDToken()` with no forcing — it answers from its
     *   own cache while the token has comfortable life left, and goes to Google
     *   when it does not.
     * @param completion called exactly once, on any thread. The Kotlin side
     *   suspends on it with a timeout, so a resolver that never calls back
     *   degrades to the stored token rather than hanging the request.
     */
    fun resolveToken(forceRefresh: Boolean, completion: (String?) -> Unit)
}
