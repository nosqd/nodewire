package dev.nitka.nodewire.script.host

import dev.nitka.nodewire.script.ScriptModule
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.updateClasspath

/**
 * The script definition for a Nodewire inline script (`*.nw.kts`).
 *
 * The compile config is an **ergonomics + IDE-parity** layer, NOT the security
 * boundary (the [dev.nitka.nodewire.script.sandbox.SandboxClassLoader] is).
 *  - `defaultImports` makes the `import dev.nitka.nodewire.script.*` line
 *    optional in-world.
 *  - `implicitReceivers(ScriptModule)` makes `this` = [ScriptModule] in the
 *    body, so `input(...)` / `output(...)` / `tick { }` resolve unqualified.
 *  - `updateClasspath(...)` supplies an explicit, narrow classpath. We must
 *    NEVER use `dependenciesFromCurrentContext` — under NeoForge's
 *    SecureModuleClassLoader it yields an empty/garbage classpath.
 */
@KotlinScript(
    fileExtension = "nw.kts",
    compilationConfiguration = NwScriptCompilationConfig::class,
)
abstract class NwScript

object NwScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports("dev.nitka.nodewire.script.*")
    implicitReceivers(ScriptModule::class)
    // The script API's `input<T>` / `output<T>` are `inline fun`s compiled at
    // JVM target 21; the scripting compiler defaults to 1.8 and refuses to
    // inline target-21 bytecode ("Cannot inline bytecode built with JVM target
    // 21 …"). Pin both the structured key and the raw flag to 21.
    compilerOptions("-jvm-target", "21")
    jvm {
        jvmTarget("21")
        // {script-api jar/classes, kotlin-stdlib jar}. NEVER
        // dependenciesFromCurrentContext (empty/garbage under SecureModuleClassLoader).
        updateClasspath(ScriptHost.scriptClasspath())
    }
})
