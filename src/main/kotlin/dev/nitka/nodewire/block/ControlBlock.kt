package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.DirectionProperty

/**
 * Control Block — a configurable cockpit. Right-click starts a piloting session
 * (Phase 2) in which the player's keyboard (and, on a toggle keybind, the mouse)
 * is captured and exposed as [ControlBlockEntity]'s configurable output pins.
 * The binding layout is authored in its config menu (Phase 3).
 */
class ControlBlock(props: Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ControlBlockEntity(pos, state)

    /** Right-click toggles the client-side piloting session for this block. */
    override fun useWithoutItem(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.InteractionResult {
        if (level.isClientSide) {
            if (player.isShiftKeyDown) {
                // Sneak + RMB → open the binding editor.
                val be = level.getBlockEntity(pos) as? ControlBlockEntity
                if (be != null) {
                    dev.nitka.nodewire.client.screen.ControlConfigScreen.open(pos, be.bindings())
                }
            } else {
                dev.nitka.nodewire.client.control.ControlSession.toggle(pos)
                val on = dev.nitka.nodewire.client.control.ControlSession.isActive()
                val exitKey = dev.nitka.nodewire.client.control.ControlHud.exitKeyName
                player.displayClientMessage(
                    net.minecraft.network.chat.Component
                        .literal(if (on) "Controlling — press $exitKey to exit" else "Exited control")
                        .withStyle(net.minecraft.ChatFormatting.AQUA),
                    true,
                )
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING
    }
}
