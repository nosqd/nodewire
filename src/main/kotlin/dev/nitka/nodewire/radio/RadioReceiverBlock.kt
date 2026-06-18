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
 * Radio Receiver block. Server ticker only pulls its wired input (the tuned
 * `frequency`) via [PinLinkEngine]; the outputs are sampled lazily by whatever
 * downstream wires read them ([RadioReceiverBlockEntity.readPin]).
 */
class RadioReceiverBlock(props: Properties) : Block(props), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        RadioReceiverBlockEntity(pos, state)

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
        if (type != Registry.RADIO_RECEIVER_BE.get()) return null
        val ticker = BlockEntityTicker<RadioReceiverBlockEntity> { lvl, _, _, be ->
            PinLinkEngine.tick(lvl, be)
        }
        return ticker as BlockEntityTicker<T>
    }
}
