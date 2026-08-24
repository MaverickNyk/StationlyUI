package com.stationly.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The claim reader is load-bearing in two places that fail SILENTLY when it is
 * wrong: tokens would refresh on every request (a decoder that always returns 0)
 * and forced-logout rows would be filed against nobody. Neither looks like a
 * decoding bug from the outside, which is what these are for.
 */
class JwtClaimsTest {

    /** A real Firebase-shaped payload, base64url with the padding stripped —
     *  which is the encoding a JWT actually uses and the one a naive decoder
     *  rejects. */
    private fun token(payload: String): String {
        val header = b64url("""{"alg":"RS256","typ":"JWT"}""")
        return "$header.${b64url(payload)}.c2ln"
    }

    private fun b64url(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = s.encodeToByteArray()
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) out.append(alphabet[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) out.append(alphabet[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }

    @Test
    fun readsExpiryAndSubject() {
        val jwt = token("""{"sub":"HdNrDVNLO1fD7hkolI5xFYOWz2H3","exp":1787523309,"iat":1787519709}""")
        assertEquals(1787523309L, JwtClaims.expiry(jwt))
        assertEquals("HdNrDVNLO1fD7hkolI5xFYOWz2H3", JwtClaims.subject(jwt))
    }

    /** Firebase sets both; older shapes carried only `user_id`. */
    @Test
    fun fallsBackToUserIdWhenSubIsAbsent() {
        val jwt = token("""{"user_id":"abc123","exp":1}""")
        assertEquals("abc123", JwtClaims.subject(jwt))
    }

    /**
     * Every malformed shape must read as "refresh now" and "unattributed" —
     * never as a plausible-looking expiry far in the future, which would keep a
     * dead token in service for as long as it claimed.
     */
    @Test
    fun unreadableTokensAreZeroAndNull() {
        for (bad in listOf("", "not-a-jwt", "a.b", "a.b.c.d", "a.!!!.c", token("not json"))) {
            assertEquals(0L, JwtClaims.expiry(bad), "expiry of <$bad>")
            assertNull(JwtClaims.subject(bad), "subject of <$bad>")
        }
    }

    /** A payload with no `exp` at all is as unusable as one that will not
     *  decode, and must not read as 'never expires'. */
    @Test
    fun missingExpIsZero() {
        assertEquals(0L, JwtClaims.expiry(token("""{"sub":"x"}""")))
    }

    /** Padding is the classic base64url trap: segment lengths of 2 and 3 mod 4
     *  both occur, and a decoder that only handles one silently loses claims. */
    @Test
    fun handlesEveryPaddingLength() {
        for (pad in 0..3) {
            val uid = "u".repeat(10 + pad)
            val jwt = token("""{"sub":"$uid","exp":42}""")
            assertEquals(uid, JwtClaims.subject(jwt), "pad=$pad")
            assertEquals(42L, JwtClaims.expiry(jwt), "pad=$pad")
        }
    }
}
