package dev.nitka.nodewire.radio

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * Shared antenna mount/unmount interaction for the two radio blocks
 * (composition, not a common base — the blocks differ in their BE + ticker).
 *  * RMB with a [RadioAntennaItem] → install it (swap, returning any old one).
 *  * RMB empty hand → pop the installed antenna back to the player.
 */
object RadioBlockSupport {

    fun useItemOn(
        stack: ItemStack,
        level: Level,
        pos: BlockPos,
        player: Player,
        be: net.minecraft.world.level.block.entity.BlockEntity?,
    ): ItemInteractionResult {
        if (stack.item !is RadioAntennaItem) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val slot = (be as? AntennaHost)?.radioAntenna() ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (level.isClientSide) return ItemInteractionResult.SUCCESS

        val old = slot.stack()
        slot.set(stack.copy().also { it.count = 1 })
        stack.shrink(1)
        if (!old.isEmpty && !player.addItem(old)) player.drop(old, false)
        markChanged(level, pos, be!!)
        return ItemInteractionResult.CONSUME
    }

    fun useWithoutItem(
        level: Level,
        pos: BlockPos,
        player: Player,
        be: net.minecraft.world.level.block.entity.BlockEntity?,
    ): InteractionResult {
        val slot = (be as? AntennaHost)?.radioAntenna() ?: return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val old = slot.stack()
        if (old.isEmpty) return InteractionResult.PASS
        slot.set(ItemStack.EMPTY)
        if (!player.addItem(old)) player.drop(old, false)
        markChanged(level, pos, be!!)
        return InteractionResult.SUCCESS
    }

    private fun markChanged(level: Level, pos: BlockPos, be: net.minecraft.world.level.block.entity.BlockEntity) {
        be.setChanged()
        level.sendBlockUpdated(pos, be.blockState, be.blockState, Block.UPDATE_CLIENTS)
    }
}
