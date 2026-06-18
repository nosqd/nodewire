package dev.nitka.nodewire.block.control

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.graph.PinType

/**
 * How a [Binding] turns raw input into a pin value.
 *
 *  * [BUTTON]     — 1 key/mouse-button → BOOL.
 *  * [AXIS]       — 2 keys (negative, positive) → FLOAT in {-1, 0, +1}.
 *  * [VECTOR]     — 4 keys (forward, back, left, right) → VEC2 (x = right−left,
 *                  y = forward−back).
 *  * [MOUSE_LOOK]  — captured mouse → VEC2 absolute aim (yaw, pitch degrees).
 *  * [MOUSE_DELTA] — captured mouse → VEC2 movement since the last tick.
 *  * [SCROLL]      — the wheel → FLOAT.
 */
enum class BindKind {
    BUTTON, AXIS, VECTOR, MOUSE_LOOK, MOUSE_DELTA, SCROLL;

    /** The pin type this kind produces. */
    val pinType: PinType
        get() = when (this) {
            BUTTON -> PinType.BOOL
            AXIS -> PinType.FLOAT
            VECTOR -> PinType.VEC2
            MOUSE_LOOK -> PinType.VEC2
            MOUSE_DELTA -> PinType.VEC2
            SCROLL -> PinType.FLOAT
        }

    /** Number of keys a [Binding] of this kind references. */
    val keyCount: Int
        get() = when (this) {
            BUTTON -> 1
            AXIS -> 2
            VECTOR -> 4
            MOUSE_LOOK, MOUSE_DELTA, SCROLL -> 0
        }

    companion object {
        fun fromName(s: String): BindKind = entries.firstOrNull { it.name == s } ?: BUTTON
    }
}

/**
 * One configurable input → pin mapping on a Control Block.
 *
 * [pin] is the output pin name shown in the Link Tool. [keys] are GLFW key
 * codes (mouse buttons encoded as [MOUSE_BUTTON_BASE] + glfw button), as many
 * as [BindKind.keyCount]. The whole binding list is authored in the block's
 * config menu and persisted in the BE; the client computes each binding's live
 * value from these during a control session.
 */
data class Binding(
    val pin: String,
    val kind: BindKind,
    val keys: List<Int> = emptyList(),
) {
    val type: PinType get() = kind.pinType

    companion object {
        /** Mouse buttons are stored as key codes offset past the GLFW keyboard range. */
        const val MOUSE_BUTTON_BASE = 10_000

        val CODEC: Codec<Binding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("pin").forGetter(Binding::pin),
                Codec.STRING.fieldOf("kind").forGetter { it.kind.name },
                Codec.INT.listOf().optionalFieldOf("keys", emptyList()).forGetter(Binding::keys),
            ).apply(i) { pin, kind, keys -> Binding(pin, BindKind.fromName(kind), keys) }
        }
    }
}
