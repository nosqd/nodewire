package dev.nitka.nodewire.radio

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.link.PinLinkEngine
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Radio Transmitter block. Omnidirectional; range comes from the antenna in its
 * slot. Server ticker pulls wired inputs ([PinLinkEngine]) then publishes to the
 * [RadioRegistry].
 */
class RadioTransmitterBlock(props: Properties) : Block(props), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        RadioTransmitterBlockEntity(pos, state)

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult =
        RadioBlockSupport.useItemOn(stack, level, pos, player, level.getBlockEntity(pos))

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult =
        RadioBlockSupport.useWithoutItem(level, pos, player, level.getBlockEntity(pos))

    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != Registry.RADIO_TRANSMITTER_BE.get()) return null
        val ticker = BlockEntityTicker<RadioTransmitterBlockEntity> { lvl, _, _, be ->
            PinLinkEngine.tick(lvl, be)
            be.publish(lvl)
        }
        return ticker as BlockEntityTicker<T>
    }
}
