package dev.nitka.nodewire.script

import dev.nitka.nodewire.script.host.ScriptHost
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The compile-diagnostics surface must show the script author the *cause* of a
 * failure, not the Kotlin CLI's environment chatter. [ScriptBackend] filters
 * `reports` to ERROR/FATAL and renders each `Error (line:col): message`; this
 * guards against the regression where "Using JDK home…", "Loading modules:
 * [java.se, …]" and "Using JVM IR backend" buried the one line that mattered.
 */
class DiagnosticsNoiseTest {

    private fun diagnostics(src: String): List<String> =
        (ScriptHost.compileToModule(src) as? ScriptCompileResult.Failure)?.diagnostics ?: emptyList()

    @Test
    fun `keeps the real error and drops compiler chatter`() {
        // The exact mistake a user hit: lowercase `float` is not a Kotlin type.
        val d = diagnostics("""val x = input<float>("x")""")
        val joined = d.joinToString("\n")

        assertTrue(d.isNotEmpty(), "expected a compile failure")
        assertTrue(
            joined.contains("Unresolved reference", ignoreCase = true) ||
                joined.contains("float", ignoreCase = true),
            "expected the real error to survive, got:\n$joined",
        )
        for (noise in listOf("Using JDK home", "Loading modules", "JVM IR backend")) {
            assertFalse(joined.contains(noise, ignoreCase = true), "compiler noise leaked: '$noise'\n$joined")
        }
    }

    @Test
    fun `each diagnostic is severity-prefixed`() {
        val d = diagnostics("""val x: Int = "nope"""")
        assertTrue(d.isNotEmpty(), "expected a compile failure")
        assertTrue(
            d.all { it.startsWith("Error") || it.startsWith("Fatal") },
            "expected 'Error/Fatal (...)' prefixes, got:\n${d.joinToString("\n")}",
        )
    }
}
