package com.stationly.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The two claims this app reads out of a Firebase ID token.
 *
 * ## Why read the token at all, rather than the state beside it
 * Because a JWT is the one description of a session that cannot go stale or be
 * erased underneath you. Both callers exist because the state they would
 * otherwise have consulted was wrong at exactly the moment they needed it:
 *
 *  - [expiry] replaces "when did we last fetch a token", which is not the same
 *    fact — several writers store tokens this app did not fetch, and a
 *    fetched-at stamp is silently wrong about all of them.
 *  - [subject] replaces reading `firebase_user_uid` out of storage, which the
 *    sign-out being recorded has usually already deleted. Measured on device,
 *    twice: an `auth.forced_logout` row written with an empty uid because
 *    `AuthBridge.clearUserInfo()` won by milliseconds.
 *
 * ## Signatures are NOT verified, and must not be relied on as if they were
 * Nothing here authenticates anything. Both callers use these claims for
 * bookkeeping — when to refresh, whose row this is — and the only party whose
 * opinion of a token matters is the backend, which verifies properly
 * (`AuthMiddleware.validateUserToken`, with `checkRevoked: true`). Reading a
 * claim to decide *who to file an event against* is safe; reading one to decide
 * *what someone is allowed to do* would not be.
 */
object JwtClaims {

    /**
     * The `exp` claim in Unix seconds, or 0 if it cannot be read.
     *
     * 0 rather than null so every caller's comparison against "now" fails in the
     * same direction: a token this cannot understand is always treated as due
     * for refresh, which costs one refresh rather than an hour of dead requests.
     */
    fun expiry(jwt: String): Long =
        payload(jwt)?.get("exp")?.jsonPrimitive?.longOrNull ?: 0

    /**
     * The `sub` claim — the Firebase uid the token was minted for.
     *
     * Falls back to `user_id`, which Firebase also sets and which is the one
     * that survives in some older token shapes. Null when neither is readable;
     * callers treat that as "unattributed" rather than substituting a guess.
     */
    fun subject(jwt: String): String? {
        val payload = payload(jwt) ?: return null
        return (payload["sub"] ?: payload["user_id"])
            ?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun payload(jwt: String): JsonObject? {
        val parts = jwt.split('.')
        if (parts.size != 3) return null
        val decoded = base64UrlDecodeToString(parts[1]) ?: return null
        return runCatching { Json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
    }

    /**
     * Base64url → UTF-8, hand-rolled.
     *
     * Platform decoders would each need the same `-`/`_` substitution and
     * padding fix-up before they would accept a JWT segment, and there is no
     * multiplatform one to share. Twenty lines of pure Kotlin has no interop
     * surface, runs identically on every target, and is directly testable —
     * which matters, because a decoder that silently returns nonsense would show
     * up as tokens refreshing too often and events filed against nobody, neither
     * of which looks like a decoding bug.
     */
    private fun base64UrlDecodeToString(segment: String): String? {
        val normalised = segment.replace('-', '+').replace('_', '/')
        val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
        val out = ArrayList<Byte>(padded.length / 4 * 3)
        var buffer = 0
        var bits = 0
        for (c in padded) {
            if (c == '=') break
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return runCatching { out.toByteArray().decodeToString() }.getOrNull()
    }

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
