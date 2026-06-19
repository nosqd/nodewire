package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.state.BlockState

class ArHubBlock(props: Properties) : Block(props), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ArHubBlockEntity(pos, state)

    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: net.minecraft.world.level.Level,
        state: BlockState,
        type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != dev.nitka.nodewire.Registry.AR_HUB_BE.get()) return null
        val ticker = BlockEntityTicker<ArHubBlockEntity> { lvl, _, _, be ->
            be.tickServer(lvl)
        }
        return ticker as BlockEntityTicker<T>
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRemove(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        newState: BlockState,
        isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block) && !level.isClientSide) {
            (level.getBlockEntity(pos) as? ArHubBlockEntity)?.let { be ->
                be.clearClientState()
            }
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }
}
