package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.script.lexer.BodyKind
import dev.nitka.nodewire.script.lexer.HeaderLexer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeaderLexerTest {

    private val sample = """
        import dev.nitka.nodewire.script.*

        val enable = input<Boolean>("enable")
        val period = input<Int>("period", default = 20)
        val out    = output<Redstone>("out")
        var t by state(0)

        tick {
            if (!enable.value) { out.value = Redstone.OFF; return@tick }
            t = (t + 1) % period.value
            out.value = if (t < period.value / 2) Redstone.MAX else Redstone.OFF
        }
    """.trimIndent()

    @Test fun agreedSampleHeader() {
        val r = HeaderLexer.parse(sample)
        assertEquals(listOf("enable" to PinType.BOOL, "period" to PinType.INT), r.inputs.map { it.id to it.type })
        assertEquals(listOf("out" to PinType.REDSTONE), r.outputs.map { it.id to it.type })
        assertEquals(BodyKind.TICK, r.body)
    }

    @Test fun inputInsideTickBodyIsNotAPin() {
        // The `input<Boolean>("x")` lives inside the tick{} block — must not be a pin.
        val src = """
            val a = input<Int>("a")
            tick {
                val sneaky = input<Boolean>("x")
            }
        """.trimIndent()
        val r = HeaderLexer.parse(src)
        assertEquals(listOf("a"), r.inputs.map { it.id })
    }

    @Test fun commentedOutDeclIsNotAPin() {
        val src = """
            val a = input<Int>("a")
            // val b = input<Int>("b")
            /* val c = output<Int>("c") */
        """.trimIndent()
        val r = HeaderLexer.parse(src)
        assertEquals(listOf("a"), r.inputs.map { it.id })
        assertTrue(r.outputs.isEmpty())
    }

    @Test fun unsupportedTypeSkippedWithWarning() {
        val src = """val z = input<Long>("z")"""
        val r = HeaderLexer.parse(src)
        assertTrue(r.inputs.isEmpty())
        assertTrue(r.warnings.any { it.contains("Long") }, "expected a warning mentioning Long, got ${r.warnings}")
    }

    @Test fun nonLiteralNameSkippedWithWarning() {
        val src = """
            val name = "dynamic"
            val v = input<Int>(name)
        """.trimIndent()
        val r = HeaderLexer.parse(src)
        assertTrue(r.inputs.isEmpty())
        assertTrue(r.warnings.any { it.contains("literal") }, "expected a literal-name warning, got ${r.warnings}")
    }

    @Test fun duplicateNamesLastWinsPlusWarning() {
        val src = """
            val a1 = input<Int>("dup")
            val a2 = input<Float>("dup")
        """.trimIndent()
        val r = HeaderLexer.parse(src)
        assertEquals(1, r.inputs.size)
        assertEquals(PinType.FLOAT, r.inputs.single().type) // last wins
        assertTrue(r.warnings.any { it.contains("duplicate") })
    }

    @Test fun nullableTypeSkipped() {
        val src = """val q = input<Int?>("q")"""
        val r = HeaderLexer.parse(src)
        assertTrue(r.inputs.isEmpty())
    }
}
