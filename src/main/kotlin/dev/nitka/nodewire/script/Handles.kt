package dev.nitka.nodewire.script

/**
 * Typed pin accessor handed back from [ScriptModule.input]. Read-only in the
 * script body — the value is whatever the host pushed for this tick.
 */
interface Input<out T> {
    val value: T
}

/**
 * Typed pin accessor handed back from [ScriptModule.output]. Settable inside
 * a `tick {}` / `eval {}` block; the host collects it after the block runs.
 *
 * Invariant in `T` — a `var` exposes `T` in both in (setter) and out (getter)
 * positions, so it cannot be `in`-projected.
 */
interface Output<T> {
    var value: T
}

/**
 * Raised when a script declares something the host can't honour — an
 * unsupported pin type, or a `state(...)` of a non-persistable type. Surfaced
 * on the node (never crashes the server).
 */
class ScriptDeclException(message: String) : RuntimeException(message)
