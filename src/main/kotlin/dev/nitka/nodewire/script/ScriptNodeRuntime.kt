package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Server-side driver for `script` nodes: compiles a script's source (lazily,
 * off the tick thread), caches the compiled [ScriptModule] by source, and drives
 * one tick of it — push inputs → run the body → pull outputs — with per-node
 * state carried in the node's `state` [CompoundTag].
 *
 * Why a source-keyed cache of ONE module (not one per node): the compiled module
 * holds its state in [ScriptModule.stateCells], but we round-trip that through
 * the per-node `state` tag every tick ([loadState]/[saveState]), so a single
 * module instance can serve every node running the same script. Evaluation is
 * sequential (server thread) so the shared instance is safe; we guard anyway.
 *
 * Compilation goes through the [ScriptCompilerRegistry] SPI — the optional
 * `:scripting` addon supplies the real compiler. Absent addon (or a compile
 * error) → the node outputs type-defaults and exposes the diagnostics via
 * [statusOf] for the node card.
 */
object ScriptNodeRuntime {

    sealed interface Status {
        /** No source yet (empty script). */
        data object Empty : Status
        /** Compiling off-thread; outputs are defaults until ready. */
        data object Compiling : Status
        /** Ready to evaluate. */
        data object Ok : Status
        /** Compile failed (or no addon); [diagnostics] is shown on the node. */
        data class Error(val diagnostics: List<String>) : Status
    }

    private sealed interface Entry {
        data object Compiling : Entry
        data class Ready(val module: ScriptModule) : Entry
        data class Failed(val diagnostics: List<String>) : Entry
    }

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Off-thread compile pool — daemon so it never blocks shutdown. Overridable for tests. */
    @Volatile
    var compileExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nodewire-script-compile").apply { isDaemon = true }
    }

    /** UI status for a node's current [src] (no side effects, safe on any thread). */
    fun statusOf(src: String): Status {
        if (src.isBlank()) return Status.Empty
        return when (val e = cache[src]) {
            null, Entry.Compiling -> Status.Compiling
            is Entry.Ready -> Status.Ok
            is Entry.Failed -> Status.Error(e.diagnostics)
        }
    }

    /**
     * Drive one tick of the script [src] with [inputs], reading/writing per-node
     * [state]. Returns the output values (type-defaults for [outputPins] when the
     * script isn't ready). Any `log`/`chat` the script emitted this tick is pushed
     * into [ScriptMessageSink] for the host to dispatch. Server-thread only.
     */
    fun evalTick(
        src: String,
        state: CompoundTag,
        inputs: Map<String, PinValue>,
        outputPins: List<Pin>,
    ): Map<String, PinValue> {
        if (src.isBlank()) return defaults(outputPins)

        val entry = cache[src]
        if (entry == null) {
            // Claim the slot atomically, then kick the compile off-thread. (Not
            // inside computeIfAbsent — compile() writes the same key, which a
            // remapping function may not do.)
            if (cache.putIfAbsent(src, Entry.Compiling) == null) {
                compileExecutor.execute { compile(src) }
            }
            return defaults(outputPins)
        }

        val module = (entry as? Entry.Ready)?.module ?: return defaults(outputPins)

        return synchronized(module) {
            module.loadState(state)
            module.pushInputs(inputs)
            runCatching { module.tickBlock?.invoke() }
                .onFailure { ScriptMessageSink.add(ScriptMessage("script threw: ${it.message}", MessageKind.LOG)) }
            val out = module.pullOutputs()
            module.saveState(state)
            module.drainMessages().let { if (it.isNotEmpty()) ScriptMessageSink.add(it) }
            out
        }
    }

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    private fun compile(src: String) {
        val compiler = ScriptCompilerRegistry.compiler
        val entry = if (compiler == null) {
            Entry.Failed(listOf("Nodewire Scripting addon not installed"))
        } else {
            runCatching {
                when (val r = compiler.compileToModule(src)) {
                    is ScriptCompileResult.Success -> Entry.Ready(r.module)
                    is ScriptCompileResult.Failure -> Entry.Failed(r.diagnostics)
                }
            }.getOrElse { Entry.Failed(listOf("compiler threw: ${it::class.simpleName}: ${it.message}")) }
        }
        cache[src] = entry
        when (entry) {
            is Entry.Failed -> LOG.warn("NW-SCRIPT compile FAILED: {}", entry.diagnostics.joinToString(" | "))
            is Entry.Ready -> LOG.info("NW-SCRIPT compiled OK")
            else -> {}
        }
    }

    /** Drop a cached compile (e.g. when a node's source changes). */
    fun invalidate(src: String) {
        cache.remove(src)
    }

    private fun defaults(outputPins: List<Pin>): Map<String, PinValue> =
        outputPins.associate { it.id to PinValue.default(it.type) }
}

/**
 * Per-tick collector for the `log`/`chat` messages scripts emit. The graph
 * evaluator (running pure, world-free) drops messages here; the host
 * ([dev.nitka.nodewire.block.LogicBlockEntity]) reads them after the tick and
 * dispatches — LOG to the mod logger, CHAT to nearby players. ThreadLocal so it
 * stays on the server thread without locking, mirroring AeroStatePipeline.
 */
object ScriptMessageSink {
    private val sink = ThreadLocal.withInitial { ArrayList<ScriptMessage>() }

    fun add(messages: List<ScriptMessage>) {
        if (messages.isNotEmpty()) sink.get().addAll(messages)
    }

    fun add(message: ScriptMessage) {
        sink.get().add(message)
    }

    /** Take and clear everything collected since the last drain. */
    fun drain(): List<ScriptMessage> {
        val s = sink.get()
        if (s.isEmpty()) return emptyList()
        val copy = ArrayList(s)
        s.clear()
        return copy
    }
}
