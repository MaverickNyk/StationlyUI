package com.stationly.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AV2-9.6. The owner's rule, stated on 2026-09-12: **no em dashes in any text a
 * user reads.** This test is the half of that rule that survives the session it
 * was written in.
 *
 * ## Why this is a lexer and not a grep
 * A plain `grep` for the character fails on roughly every file in the repo,
 * because the house comment style is built on the em dash and is staying that
 * way. A guard that cannot tell a comment from a caption is a guard that gets
 * deleted the first week. So this walks the source the way the compiler
 * does: line comments, block comments (which nest in Kotlin), KDoc, char
 * literals and raw strings are all recognised and skipped, and only the text
 * inside a string literal is examined.
 *
 * ## Why some string literals are exempt
 * A line such as `UserSyncCoordinator`'s "Reconcile skipped, account unchanged"
 * is a string literal, and it is not copy. Nine log lines carried an em dash
 * when this test was written and all nine are right as they are. The exempt set is deliberately narrow
 * and listed in [isNonUserFacingSink]: logging, developer assertions, and the
 * message on a thrown exception. Everything else is assumed to be something a
 * person can read, which is the safe assumption to get wrong.
 *
 * The exemption is scoped to the call's PARENTHESES, so the multi-line form
 * ```
 * Log.d(
 *     "Widget",
 *     "Widget $id left as-is, board list is mid-rewrite",
 * )
 * ```
 * is covered too. It is NOT scoped to a trailing lambda, so a dash inside
 * `require(x) { "..." }` fails this test. That is the over-strict direction on
 * purpose: a message worth an em dash is worth a line of its own.
 *
 * ## The trap this file sets for itself
 * A test that hunts for a character must not contain that character, or the day
 * somebody adds `src/test` to [SOURCE_ROOTS] it fails on itself and looks like a
 * real regression. So the two characters are spelled as escapes here and appear
 * nowhere in this file literally. Keep it that way.
 */
class UserFacingCopyTest {

    @Test
    fun `no em or en dash in a user-facing string literal`() {
        val root = repoRoot()
        val offences = mutableListOf<String>()

        SOURCE_ROOTS.forEach { rel ->
            val dir = File(root, rel)
            assertTrue("source root has moved: $rel", dir.isDirectory)
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .forEach { f ->
                    userFacingLiterals(f.readText()).forEach { (line, text) ->
                        if (text.hasBannedDash()) {
                            offences += "${f.relativeTo(root)}:$line  \"$text\""
                        }
                    }
                }
        }

        val xml = File(root, STRINGS_XML)
        assertTrue("strings.xml has moved: $STRINGS_XML", xml.isFile)
        xmlStringBodies(xml.readText()).forEach { (line, text) ->
            if (text.hasBannedDash()) {
                offences += "${xml.relativeTo(root)}:$line  \"$text\""
            }
        }

        assertTrue(
            buildString {
                append("An em dash (U+2014) or en dash (U+2013) reached copy a user reads. ")
                append("Rewrite the sentence so it does not need one: usually a full stop and ")
                append("a new sentence, sometimes a colon. Do not reach for a hyphen.\n\n")
                offences.forEach { append("  ").append(it).append('\n') }
            },
            offences.isEmpty(),
        )
    }
}

// ── What is scanned ─────────────────────────────────────────────────────────

/**
 * Every tree that holds Android copy. `:composeApp`'s `ui` package is here
 * rather than in a `:composeApp` test because that module's `commonTest` has no
 * JVM file access, and because one list is easier to keep honest than two.
 */
private val SOURCE_ROOTS = listOf(
    "android/app/src/main/java/com/stationly/mobile",
    "composeApp/src/commonMain/kotlin/com/stationly/app/ui",
)

private const val STRINGS_XML = "android/app/src/main/res/values/strings.xml"

private const val EM_DASH = '\u2014'
private const val EN_DASH = '\u2013'

private fun String.hasBannedDash() = any { it == EM_DASH || it == EN_DASH }

/**
 * The Gradle test task runs in the module directory, not the repo root, and
 * that is an implementation detail of whichever task invoked us. Walk up to the
 * one file that only the root has.
 */
private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("repo root not found above ${System.getProperty("user.dir")}")
}

// ── The Kotlin scanner ──────────────────────────────────────────────────────

private data class Literal(val line: Int, val text: String)

/** `Log.d`, `Log.e`, and anything else that ends in `Log`. */
private val LOG_LEVELS = setOf("v", "d", "i", "w", "e", "wtf")

/** Text written for whoever reads a stack trace, not for whoever holds the phone. */
private val DEVELOPER_CALLS = setOf(
    "error", "require", "check", "requireNotNull", "checkNotNull",
    "TODO", "println", "print", "assert",
)

/**
 * Does this callee's argument list hold text no user will ever see?
 *
 * [name] is the dotted callee immediately left of an open paren, so
 * `android.util.Log.d`, `Log.w`, `error` and `IllegalStateException` all arrive
 * in the shape they were written in.
 */
private fun isNonUserFacingSink(name: String): Boolean {
    if (name.isEmpty()) return false
    val last = name.substringAfterLast('.')
    val owner = name.substringBeforeLast('.', "")
    if (owner.endsWith("Log") && last in LOG_LEVELS) return true
    if (last in DEVELOPER_CALLS) return true
    // `throw IllegalStateException("...")`, `SerializationException("...")`.
    if (last.endsWith("Exception") || last.endsWith("Error")) return true
    return false
}

private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

/**
 * Every string literal in [src] that is not inside a [isNonUserFacingSink]
 * call, with the 1-based line its opening quote sat on.
 *
 * Deliberately not a full Kotlin parser. The one shape it approximates rather
 * than models is `"a ${f("b")} c"`: the inner quotes re-sync the scan, so the
 * text is reported in two pieces instead of one. Both pieces are still
 * scanned, which is all this test needs, and the quote count inside an
 * interpolation is always even so the scan cannot slide off into a comment.
 */
private fun userFacingLiterals(src: String): List<Literal> {
    val out = mutableListOf<Literal>()
    val suppressed = mutableSetOf<Int>()
    var parens = 0
    var line = 1
    var i = 0
    val n = src.length

    while (i < n) {
        val c = src[i]
        when {
            c == '\n' -> { line++; i++ }

            // Line comment.
            c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                while (i < n && src[i] != '\n') i++
            }

            // Block comment and KDoc. Kotlin nests these, so count depth.
            c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                var depth = 1
                i += 2
                while (i < n && depth > 0) {
                    when {
                        src[i] == '\n' -> line++
                        src[i] == '/' && i + 1 < n && src[i + 1] == '*' -> { depth++; i++ }
                        src[i] == '*' && i + 1 < n && src[i + 1] == '/' -> { depth--; i++ }
                    }
                    i++
                }
            }

            c == '(' -> {
                var j = i
                while (j > 0 && isIdentChar(src[j - 1])) j--
                parens++
                if (isNonUserFacingSink(src.substring(j, i))) suppressed += parens
                i++
            }

            c == ')' -> {
                suppressed -= parens
                if (parens > 0) parens--
                i++
            }

            // Raw string.
            src.startsWith("\"\"\"", i) -> {
                val start = line
                i += 3
                val sb = StringBuilder()
                while (i < n && !src.startsWith("\"\"\"", i)) {
                    if (src[i] == '\n') line++
                    sb.append(src[i]); i++
                }
                i += 3
                if (suppressed.isEmpty()) out += Literal(start, sb.toString())
            }

            c == '"' -> {
                val start = line
                i++
                val sb = StringBuilder()
                while (i < n && src[i] != '"') {
                    if (src[i] == '\\' && i + 1 < n) { sb.append(src[i]).append(src[i + 1]); i += 2; continue }
                    if (src[i] == '\n') { line++; break }
                    sb.append(src[i]); i++
                }
                i++
                if (suppressed.isEmpty()) out += Literal(start, sb.toString())
            }

            // Char literal. Present only so that `'"'` does not open a string
            // and slide the whole rest of the file one quote out of phase.
            c == '\'' -> {
                i++
                while (i < n && src[i] != '\'') {
                    if (src[i] == '\\' && i + 1 < n) { i += 2; continue }
                    if (src[i] == '\n') { line++; break }
                    i++
                }
                i++
            }

            else -> i++
        }
    }
    return out
}

// ── The XML scanner ─────────────────────────────────────────────────────────

/** The body of every `<string>` in a values resource, comments skipped. */
private fun xmlStringBodies(src: String): List<Literal> {
    val withoutComments = src.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)) { m ->
        // Keep the newlines so reported line numbers still match the file.
        m.value.filter { it == '\n' }
    }
    val out = mutableListOf<Literal>()
    Regex("<string[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        .findAll(withoutComments)
        .forEach { m ->
            val line = withoutComments.take(m.range.first).count { it == '\n' } + 1
            out += Literal(line, m.groupValues[1])
        }
    return out
}
