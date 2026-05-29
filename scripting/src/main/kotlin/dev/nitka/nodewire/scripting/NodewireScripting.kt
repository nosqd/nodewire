package dev.nitka.nodewire.scripting

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.script.ScriptCompilerRegistry
import dev.nitka.nodewire.script.host.ScriptHost
import net.neoforged.fml.common.Mod
import org.slf4j.Logger

/**
 * Entrypoint for the optional `nodewire_scripting` addon mod.
 *
 * Its sole job is to plug the Kotlin-compiler-backed [ScriptHost] into core's
 * [ScriptCompilerRegistry] at mod-construction time. Once registered, core's
 * Script Node can compile in-world scripts. When this addon is absent, the
 * registry stays empty and the Script Node degrades to read-only — core never
 * references the compiler itself.
 */
@Mod(NodewireScripting.ID)
object NodewireScripting {
    const val ID = "nodewire_scripting"
    private val LOG: Logger = LogUtils.getLogger()

    init {
        ScriptCompilerRegistry.compiler = ScriptHost
        LOG.info("Nodewire Scripting loaded — script compiler registered")
    }
}
