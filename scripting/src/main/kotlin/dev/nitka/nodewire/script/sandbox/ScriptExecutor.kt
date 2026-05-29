package dev.nitka.nodewire.script.sandbox

import dev.nitka.nodewire.graph.PinValue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Per-tick time guard + circuit breaker for script evaluation.
 *
 * Each `invoke()` runs on a bounded daemon pool, bounded by [Future.get] with
 * a budget. **No safe forced abort exists on JDK 21** (`Thread.stop` is
 * removed), so on overrun we `cancel(true)` (interrupt — honoured by blocking
 * waits, ignored by a pure-CPU `while(true)`) and count a strike. After
 * [maxStrikes] strikes the node is permanently disabled this session.
 *
 * Accepted residual: a pure-CPU infinite loop leaks one worker thread until
 * server restart; the node is disabled so it never re-runs and the main tick
 * is never blocked beyond the budget. Size the pool above the expected number
 * of concurrent scripts so one leak can't starve the rest.
 *
 * Exceptions never propagate to the caller — any failure yields [defaults].
 */
class ScriptExecutor(threads: Int = 4, val maxStrikes: Int = 3) {

    private val pool = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "nw-script-exec").apply { isDaemon = true }
    }

    /**
     * Run [invoke] under the time budget. On timeout: interrupt, strike, and
     * disable past [maxStrikes]. On any other failure: [defaults].
     *
     * @param strike   increments and returns the node's consecutive-overrun count.
     * @param disable  marks the node permanently disabled this session.
     */
    fun runTick(
        invoke: () -> Map<String, PinValue>,
        defaults: Map<String, PinValue>,
        budgetMs: Long,
        strike: () -> Int,
        disable: () -> Unit,
    ): Map<String, PinValue> {
        val f: Future<Map<String, PinValue>> = pool.submit<Map<String, PinValue>> { invoke() }
        return try {
            f.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            f.cancel(true)
            if (strike() >= maxStrikes) disable()
            defaults
        } catch (e: Throwable) {
            defaults
        }
    }

    fun shutdown() {
        pool.shutdownNow()
    }
}
