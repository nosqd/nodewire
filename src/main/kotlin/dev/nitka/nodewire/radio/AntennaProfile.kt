package dev.nitka.nodewire.radio

import net.minecraft.world.phys.Vec3

/**
 * The resolved radio characteristics of an antenna, however it's produced — an
 * item in a [AntennaSlot] (always omni) or, later, a multiblock structure
 * (directional). This is the single shape [RadioRegistry] reasons about, so the
 * transmitter/receiver block entities don't care whether the antenna is an item
 * or a dish.
 *
 * @param range   broadcast/receive reach in blocks.
 * @param gain    strength bias for the "strongest wins" contest.
 * @param crossWorld whether it can bridge across dimensions (both ends need it).
 * @param aim     WORLD-space **unit** beam direction; `null` = omnidirectional.
 * @param focus   lobe exponent `k` in `max(0,cosθ)^k`; `0` = omni (the [aim] is
 *                then irrelevant), higher = tighter & stronger on-axis beam.
 */
data class AntennaProfile(
    val range: Double,
    val gain: Double,
    val crossWorld: Boolean = false,
    val aim: Vec3? = null,
    val focus: Double = 0.0,
)
