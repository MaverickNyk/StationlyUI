package platform

import android.content.SharedPreferences
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.platform.textOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy

/** Values v1 stored as numbers and booleans, read by v2 as text. */
class StoredTextTest {

    /** Throws on a type mismatch, exactly as Android's own `getString` does. */
    private fun prefs(vararg stored: Pair<String, Any>): SharedPreferences {
        val values = mapOf(*stored)
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> when (val v = values[args[0]]) {
                    null -> args[1]
                    is String -> v
                    else -> throw ClassCastException("${v.javaClass.name} cannot be cast to java.lang.String")
                }
                "getAll" -> values
                else -> error("unexpected ${method.name}")
            }
        } as SharedPreferences
    }

    @Test
    fun `a plain getString throws on what v1 wrote which is the bug`() {
        assertThrows(ClassCastException::class.java) {
            prefs("cached_dropdown_/modes_ts" to 1795510241211L).getString("cached_dropdown_/modes_ts", null)
        }
    }

    @Test
    fun `v1s dropdown timestamp reads as the same number`() {
        val text = prefs("cached_dropdown_/modes_ts" to 1795510241211L).textOrNull("cached_dropdown_/modes_ts")
        assertEquals(1795510241211L, text?.toLongOrNull())
    }

    @Test
    fun `v1s dismissed announcement is still dismissed`() {
        assertEquals("true", prefs("dismissed_announcement_x" to true).textOrNull("dismissed_announcement_x"))
    }

    @Test
    fun `the storage the whole app reads through uses it in both files`() = runBlocking {
        val storage = AndroidStorageManager { prefs("ts" to 7L) }
        assertEquals("7", storage.loadString("ts"))
        assertEquals("7", storage.loadDurable("ts"))
    }

    @Test
    fun `text is untouched and a missing key is null`() {
        assertEquals("hello", prefs("k" to "hello").textOrNull("k"))
        assertNull(prefs().textOrNull("k"))
    }

    @Test
    fun `a set is not text`() {
        assertNull(prefs("fcm_topics" to setOf("a")).textOrNull("fcm_topics"))
    }
}
