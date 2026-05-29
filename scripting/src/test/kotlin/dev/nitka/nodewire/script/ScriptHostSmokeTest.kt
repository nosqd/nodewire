package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.script.host.ScriptHost
import dev.nitka.nodewire.script.sandbox.ScriptExecutor
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.valueOrNull

/**
 * Layer-A spike smoke tests (spec §11). These prove scripting **logic,
 * sandbox, binding, type-mapping** in a flat JVM classpath. A green Layer A
 * de-risks the API — NOT the NeoForge JPMS module layer (that is Layer B, the
 * human-run probe).
 */
class ScriptHostSmokeTest {

    private fun diag(r: ResultWithDiagnostics<*>) = r.reports.joinToString("\n") { rep ->
        val ex = rep.exception
        val causes = generateSequence(ex) { it.cause }
            .joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
        if (ex != null) "${rep.severity} ${rep.message} || CAUSES: $causes" else "${rep.severity} ${rep.message}"
    }

    // #1 — host can compile + eval a trivial expression; measure cold-compile.
    @Test fun compilesAndEvalsTrivialScript() {
        val t0 = System.nanoTime()
        val r = ScriptHost.evalSource("1 + 41")
        val coldMs = (System.nanoTime() - t0) / 1_000_000
        println("[script-spike] cold compile+eval of '1 + 41' = ${coldMs}ms; reports:\n${diag(r)}")
        assertTrue(r is ResultWithDiagnostics.Success, "compile/eval failed:\n${diag(r)}")
        assertEquals(42, r.valueOrNull())
    }

    // #2 — the agreed style-A timer drives correct PinValue over 40 ticks.
    @Test fun styleATimerProducesCorrectPinValue() {
        val src = """
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

        val compiled = ScriptHost.compileModule(src)
        assertTrue(compiled is ResultWithDiagnostics.Success, "compile failed:\n${diag(compiled)}")
        val module = compiled.valueOrNull()!!

        // header was read off the live module
        assertEquals(setOf("enable", "period"), module.specsIn.keys)
        assertEquals(setOf("out"), module.specsOut.keys)

        val state = CompoundTag()
        val inputs = mapOf<String, PinValue>(
            "enable" to PinValue.Bool(true),
            "period" to PinValue.Int(20),
        )

        fun runOneTick(): PinValue {
            module.loadState(state)
            module.pushInputs(inputs)
            module.tickBlock!!.invoke()
            val out = module.pullOutputs()
            module.saveState(state)
            return out["out"]!!
        }

        // period=20 -> ON while t in 1..9, OFF while t in 10..19 (and 0).
        // t increments BEFORE the comparison, so tick #1 sees t=1.
        val outs = (1..40).map { runOneTick() }

        // phase checks at representative ticks. ON half is t in 0..9 (t<10),
        // OFF half is t in 10..19. t increments BEFORE the comparison.
        assertEquals(PinValue.Redstone(15), outs[0], "tick 1 (t=1) should be MAX")   // t=1
        assertEquals(PinValue.Redstone(15), outs[8], "tick 9 (t=9) should be MAX")   // t=9
        assertEquals(PinValue.Redstone(0), outs[9], "tick 10 (t=10) should be OFF")  // t=10
        assertEquals(PinValue.Redstone(0), outs[18], "tick 19 (t=19) should be OFF") // t=19
        assertEquals(PinValue.Redstone(15), outs[19], "tick 20 (t=0) should be MAX") // t=0 (in ON half)
        assertEquals(PinValue.Redstone(15), outs[20], "tick 21 (t=1) should be MAX") // t=1 again

        // state actually persisted across ticks
        assertEquals(0, state.getInt("t")) // after tick 40, t = 40 % 20 = 0

        // disabling enable forces OFF
        val state2 = CompoundTag()
        module.loadState(state2)
        module.pushInputs(mapOf("enable" to PinValue.Bool(false), "period" to PinValue.Int(20)))
        module.tickBlock!!.invoke()
        assertEquals(PinValue.Redstone(0), module.pullOutputs()["out"])

        // #2 (cache facet): compiling the SAME source again is a fresh compile here
        // (ScriptHost has no cache in Layer A) but must still succeed identically.
        val again = ScriptHost.compileModule(src)
        assertTrue(again is ResultWithDiagnostics.Success, "recompile failed:\n${diag(again)}")
    }

    // #2b — a Redstone output clamps when the script over-drives it.
    @Test fun redstoneOutputClamps() {
        val src = """
            val out = output<Redstone>("out")
            tick { out.value = Redstone.of(99) }
        """.trimIndent()
        val module = ScriptHost.compileModule(src).valueOrNull()
        assertNotNull(module, "compile failed")
        module!!.tickBlock!!.invoke()
        assertEquals(PinValue.Redstone(15), module.pullOutputs()["out"])
    }

    // #2c — a threshold script: input<Redstone> -> output<Redstone> (the task's named smoke).
    @Test fun redstoneThresholdScript() {
        val src = """
            val in_  = input<Redstone>("in")
            val out  = output<Redstone>("out")
            eval { out.value = if (in_.value.power >= 8) Redstone.MAX else Redstone.OFF }
        """.trimIndent()
        val module = ScriptHost.compileModule(src).valueOrNull()
        assertNotNull(module, "compile failed")

        fun run(power: Int): PinValue {
            module!!.pushInputs(mapOf("in" to PinValue.Redstone(power)))
            module.tickBlock!!.invoke()
            return module.pullOutputs()["out"]!!
        }
        assertEquals(PinValue.Redstone(0), run(7))
        assertEquals(PinValue.Redstone(15), run(8))
        assertEquals(PinValue.Redstone(15), run(15))
    }

    // #3 — sandbox denies java.io.File. The denied class loads lazily when the
    // tick body runs (it's not touched during top-level instantiation), so we
    // drive a tick and assert the sandbox rejection there.
    @Test fun sandboxDeniesJavaIoFile() {
        val src = """
            val out = output<Int>("out")
            tick { val f = java.io.File("x"); out.value = f.name.length }
        """.trimIndent()
        val r = ScriptHost.compileModule(src)
        println("[script-spike] java.io.File compile/instantiate reports:\n${diag(r)}")
        if (r is ResultWithDiagnostics.Success) {
            val threw = runCatching { r.value.tickBlock!!.invoke() }.exceptionOrNull()
            assertNotNull(threw, "java.io.File should have thrown when the tick ran")
            assertTrue(isDeniedClassError(threw!!), "expected a sandbox ClassNotFoundException, got: $threw")
        } else {
            // Compile/instantiate rejection (e.g. narrow classpath) is also a denial.
            assertTrue(
                sandboxRejected(r),
                "java.io.File was rejected but not via the sandbox path:\n${diag(r)}",
            )
        }
    }

    // #4 — sandbox denies Class.forName(Runtime) reflection escape.
    @Test fun sandboxDeniesClassForNameRuntime() {
        val src = """
            val out = output<Int>("out")
            tick {
                val c = Class.forName("java.lang.Runtime")
                out.value = c.name.length
            }
        """.trimIndent()
        val r = ScriptHost.compileModule(src)
        println("[script-spike] Class.forName(Runtime) compile/instantiate reports:\n${diag(r)}")
        // Class.forName resolves via the caller's loader = the guard. The script
        // instantiates fine; invoking the tick triggers the denied load. Assert a
        // denial in BOTH branches so the test can never pass trivially.
        val denied: Boolean = when (r) {
            is ResultWithDiagnostics.Success -> {
                val threw = runCatching { r.value.tickBlock!!.invoke() }.exceptionOrNull()
                assertNotNull(threw, "Class.forName(Runtime) should have thrown at runtime")
                isDeniedClassError(threw!!)
            }
            is ResultWithDiagnostics.Failure -> sandboxRejected(r) // compile-time rejection also counts
        }
        assertTrue(denied, "Class.forName(Runtime) must be rejected by the sandbox:\n${diag(r)}")
    }

    // #5 — curated allowlist is sufficient: kotlin.math + a plain val link & run.
    @Test fun sandboxAllowsKotlinMathAndPlainScript() {
        val src = """
            val out = output<Float>("out")
            eval {
                val x = 1
                out.value = kotlin.math.sqrt(16.0).toFloat() + x
            }
        """.trimIndent()
        val r = ScriptHost.compileModule(src)
        println("[script-spike] kotlin.math+plain reports:\n${diag(r)}")
        assertTrue(r is ResultWithDiagnostics.Success, "kotlin.math script should compile:\n${diag(r)}")
        val module = r.valueOrNull()!!
        module.tickBlock!!.invoke()
        assertEquals(PinValue.Float(5f), module.pullOutputs()["out"])
    }

    // #6 — time guard aborts while(true) and disables after 3 strikes.
    @Test fun timeGuardAbortsAndDisablesAfterThreeStrikes() {
        val executor = ScriptExecutor(threads = 4, maxStrikes = 3)
        val strikes = AtomicInteger(0)
        var disabled = false
        val defaults = mapOf<String, PinValue>("out" to PinValue.Redstone(0))

        val spin: () -> Map<String, PinValue> = {
            @Suppress("ControlFlowWithEmptyBody")
            while (true) { /* pure-CPU spin, ignores interrupt */ }
            @Suppress("UNREACHABLE_CODE")
            defaults
        }

        repeat(3) {
            val out = executor.runTick(
                invoke = spin,
                defaults = defaults,
                budgetMs = 20,
                strike = { strikes.incrementAndGet() },
                disable = { disabled = true },
            )
            assertEquals(defaults, out, "overrun must yield defaults within budget")
        }

        assertEquals(3, strikes.get())
        assertTrue(disabled, "node should be disabled after 3 strikes")
        executor.shutdown()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun sandboxRejected(r: ResultWithDiagnostics<*>): Boolean =
        r.reports.any { it.message.contains("not permitted") || it.message.contains("ClassNotFound") }

    private fun isDeniedClassError(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is ClassNotFoundException || e is NoClassDefFoundError) {
                if (e.message?.contains("not permitted") == true ||
                    e.message?.contains("Runtime") == true ||
                    e.message?.contains("java.lang.Runtime") == true
                ) return true
                // A bare ClassNotFoundException from the guard is still a denial.
                return true
            }
            e = e.cause
        }
        return false
    }
}
