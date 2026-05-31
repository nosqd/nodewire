package dev.nitka.nodewire.script

/**
 * The script-facing pin type tag.
 *
 * This is intentionally a **self-contained** enum with zero Minecraft / Mojang
 * references. The graph's [dev.nitka.nodewire.graph.PinType] carries a Mojang
 * `Codec` in its companion; loading `PinType` therefore runs a `<clinit>` that
 * touches `com.mojang.serialization.*`. Inside the script sandbox (whose
 * guarding ClassLoader denies Mojang) that `<clinit>` would throw
 * `ExceptionInInitializerError`. So the script body — which links the type its
 * `input<T>` / `output<T>` declarations resolve to — must reference *this*
 * enum, never `PinType`. The host bridges `ScriptType ↔ PinType` (see
 * `PinBridge`), running entirely outside the sandbox.
 *
 * Mirrors the eventual `nodewire-script-api` module split (spec §10.4) where
 * the slim API artifact owns `ScriptType` and the main module owns the bridge.
 *
 * Entries MUST stay 1:1 with the supported subset of `PinType`.
 */
enum class ScriptType {
    BOOL,
    INT,
    FLOAT,
    REDSTONE,
    STRING,
    VEC2,
    VEC3,
    QUAT,
    VIDEO,
}
