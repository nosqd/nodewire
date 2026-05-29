package dev.nitka.nodewire.script.sandbox

/**
 * The ONLY real sandbox boundary for compiled scripts.
 *
 * **Allowlist-first, delegate-to-NULL.** Constructed with a `null` parent so
 * it does not implicitly delegate to the application loader — the JVM
 * bootstrap still supplies `java.lang.Object/String/Throwable` for class
 * verification (those never route through `loadClass` here). Compiled-script
 * classes get this as their parent (via
 * `ScriptEvaluationConfiguration.jvm.baseClassLoader`), so **every symbol the
 * script links against is allowlist-checked at link time**.
 *
 * The import-allowlist alone is bypassable (`java.lang.Runtime.getRuntime()`
 * needs no import), which is why the boundary is here, at class-load time, and
 * not in the compile config.
 *
 * Reflection escape is closed by the loader, not a SecurityManager (removed on
 * JDK 21): `Class.forName(name, …)` resolves via the caller's loader = this
 * guard = denied; `obj::class.java` yields a bootstrap `java.lang.Class` but
 * with `java.lang.reflect.*` / `java.lang.invoke.*` denied it can't *do*
 * anything; the script API surface exposes no method returning a live
 * `ClassLoader` / `Class`.
 */
class SandboxClassLoader(private val modLoader: ClassLoader) : ClassLoader(null) {

    private val allowedPrefixes = listOf(
        "dev.nitka.nodewire.script.",
    )

    /** Exact graph types the script is allowed to touch (identity must match the host's). */
    private val allowedExact = setOf(
        "dev.nitka.nodewire.graph.PinValue",
    )

    /**
     * kotlin.* sub-packages that are NEVER allowed even though kotlin.* is
     * broadly permitted.
     *
     * NOTE (empirically derived, spec §6.2 / §13.2): `kotlin.reflect.*` is NOT
     * denied — the reified `scriptPinType<T>()` (`T::class`) and the
     * `var x by state(…)` delegated-property references compile to
     * `kotlin.reflect.jvm.internal.*` / `KProperty` machinery, so denying it
     * yields `ArrayStoreException` / `ExceptionInInitializerError` at link time.
     * It loads VIA_MOD (host identity). Residual to tighten later:
     * `kotlin.reflect.full.*` is the *calling* surface and could be denied
     * separately once a finer split is wired.
     */
    private val deniedKotlin = listOf(
        "kotlin.io.",
        "kotlin.concurrent.",
        "kotlin.system.",
    )

    private val deniedJava = listOf(
        "java.io.",
        "java.nio.",
        "java.net.",
        "java.lang.reflect.",
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.Process",
        "java.lang.Thread",
        "sun.",
        "jdk.internal.",
        "net.minecraft.",
        "net.neoforged.",
    )

    /**
     * `java.lang.invoke.*` is NOT in [deniedJava]: lambda bodies (`tick {}` /
     * `eval {}`) compile to `invokedynamic` and REQUIRE `LambdaMetafactory` +
     * `MethodHandle(s)` / `MethodType` to bootstrap. Denying it
     * `NoClassDefFound`s every script. These load via the platform loader.
     * Residual risk: `MethodHandles.Lookup.defineClass` is a theoretical escape,
     * but the script cannot obtain a privileged Lookup for arbitrary packages
     * and `java.lang.reflect.*` stays denied. Acceptable for the spike.
     */

    /**
     * Platform (extension) loader — supplies the **benign** JDK platform
     * classes (`java.lang.Object/Throwable`, boxed numbers, `java.util.*`
     * collections the compiled body links) that bytecode verification and
     * codegen need. It is NOT the application loader, so it cannot see the mod
     * / Minecraft classpath. The dangerous JDK surface ([deniedJava]) is
     * refused before we ever reach here.
     *
     * The spike's mental model — "the bootstrap supplies java.lang.* and those
     * never route through us" — does not hold for a null-parent loader whose
     * `loadClass` is overridden: every name routes through here, so we must
     * explicitly delegate the safe platform classes instead of denying them.
     */
    private val platformLoader: ClassLoader = getPlatformClassLoader()

    private enum class Verdict { DENY, VIA_MOD, VIA_PLATFORM }

    private fun classify(name: String): Verdict {
        // Deny list wins first.
        if (deniedJava.any { name == it || name.startsWith(it) }) return Verdict.DENY
        if (name in allowedExact) return Verdict.VIA_MOD
        if (name.startsWith("kotlin.")) {
            return if (deniedKotlin.none { name.startsWith(it) }) Verdict.VIA_MOD else Verdict.DENY
        }
        if (allowedPrefixes.any { name.startsWith(it) }) return Verdict.VIA_MOD
        // Benign JDK platform classes needed for verification/codegen. The
        // dangerous ones were already rejected by deniedJava above.
        if (name.startsWith("java.") || name.startsWith("javax.")) return Verdict.VIA_PLATFORM
        return Verdict.DENY
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val c = when (classify(name)) {
                Verdict.DENY ->
                    throw ClassNotFoundException("$name is not permitted in a Nodewire script")
                // Borrow the SAME Class the host uses — identity matters for the
                // value types (Redstone, PinValue) crossing the boundary.
                Verdict.VIA_MOD -> modLoader.loadClass(name)
                Verdict.VIA_PLATFORM -> platformLoader.loadClass(name)
            }
            if (resolve) resolveClass(c)
            c
        }

    override fun getResource(name: String?): java.net.URL? = null

    override fun getResources(name: String?): java.util.Enumeration<java.net.URL> =
        java.util.Collections.emptyEnumeration()
}
