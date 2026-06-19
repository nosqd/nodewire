package dev.nitka.nodewire.radio

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.items.ItemStackHandler

/**
 * The single antenna slot shared by both radio block entities (composition, not
 * inheritance — the two BEs differ otherwise). Holds one [RadioAntennaItem];
 * with none, the radio still works at a short built-in range.
 */
/** A BlockEntity that exposes an [AntennaSlot] (TX + RX). */
interface AntennaHost {
    fun radioAntenna(): AntennaSlot
}

class AntennaSlot {

    private val handler = ItemStackHandler(1)

    fun stack(): ItemStack = handler.getStackInSlot(0)

    fun set(stack: ItemStack) = handler.setStackInSlot(0, stack)

    private fun antenna(): RadioAntennaItem? = stack().item as? RadioAntennaItem

    /** Effective broadcast/receive reach (blocks) — the antenna's, or a stub. */
    fun range(): Double = antenna()?.range ?: BASE_RANGE

    /** Strength bias for the "strongest wins" contest. */
    fun gain(): Double = antenna()?.gain ?: BASE_GAIN

    /** Whether the installed antenna can bridge across dimensions. */
    fun crossWorld(): Boolean = antenna()?.crossWorld ?: false

    fun save(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.put(KEY, handler.serializeNBT(registries))
    }

    fun load(tag: CompoundTag, registries: HolderLookup.Provider) {
        if (tag.contains(KEY)) handler.deserializeNBT(registries, tag.getCompound(KEY))
    }

    companion object {
        private const val KEY = "antenna"

        /** Reach with no antenna installed — short, so antennas always matter. */
        const val BASE_RANGE = 32.0
        const val BASE_GAIN = 0.25
    }
}
