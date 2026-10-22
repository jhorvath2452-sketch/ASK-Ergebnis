package at.askmannersdorf.ergebnis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android nutzt eine andere Regex-Engine (ICU) als die JVM, auf der die Tests laufen.
 * Manche Java-Flags gibt es dort nicht – z. B. (?U) / UNICODE_CHARACTER_CLASS – und sie
 * führen erst auf dem Handy zum Absturz. Dieser Test verhindert, dass so etwas wieder hineinrutscht.
 */
class AndroidRegexCompatTest {
    @Test fun noRegexFlagsUnsupportedOnAndroid() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val bad = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (line.contains("(?U)") || line.contains("UNICODE_CHARACTER_CLASS") || line.contains("(?<name>"))
                    "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertTrue("Auf Android nicht unterstützte Regex-Flags:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
