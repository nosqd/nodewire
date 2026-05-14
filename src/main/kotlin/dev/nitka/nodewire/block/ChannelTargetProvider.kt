package dev.nitka.nodewire.block

import dev.nitka.nodewire.graph.PinType
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Extensibility hook for the Channel Link Tool: given a block at a face,
 * returns the named channel inputs that block accepts.
 *
 * Two flavours of target exist today:
 *   * [TargetSlot.Channel] — a named channel input on the target's own
 *     graph. Currently provided by [LogicBlockEntity]: each `channel_input`
 *     node is one slot. Bind commits as a [ChannelBinding].
 *   * [TargetSlot.Side] — a redstone-driven side on an arbitrary block.
 *     Provided by [FallbackRedstoneSideProvider] for any non-Logic block;
 *     bind commits as a [SideBinding] on the source LogicBlock.
 *
 * Future work: custom blocks (e.g. a Display block reading a STRING channel,
 * a Speaker reading a FLOAT) can register their own [ChannelTargetProvider]
 * via [ChannelTargetRegistry.register] to declare typed channel inputs.
 */
interface ChannelTargetProvider {
    fun slotsFor(level: Level, pos: net.minecraft.core.BlockPos, state: BlockState, clickedFace: Direction): List<TargetSlot>
}

/**
 * One bindable input on a target block. The picker shows the [name] in
 * its row; on commit the link tool dispatches by [kind].
 */
sealed interface TargetSlot {
    val name: String
    val type: PinType

    /** Named-channel input on a LogicBlock — commits via BindChannelPacket. */
    data class Channel(override val name: String, override val type: PinType) : TargetSlot

    /** Redstone side on any block — commits via BindSideChannelPacket. */
    data class Side(
        override val name: String,
        override val type: PinType,
        val face: Direction,
    ) : TargetSlot
}

/**
 * Per-Block-class registry. Lookup falls back to
 * [FallbackRedstoneSideProvider] for any block without an explicit entry.
 */
object ChannelTargetRegistry {
    private val byBlock = HashMap<Block, ChannelTargetProvider>()

    fun register(block: Block, provider: ChannelTargetProvider) {
        byBlock[block] = provider
    }

    fun lookup(state: BlockState): ChannelTargetProvider =
        byBlock[state.block] ?: FallbackRedstoneSideProvider
}

/**
 * Default provider for unknown blocks: offers a single REDSTONE slot on
 * the face the player clicked. Drive-by-wire — the LogicBlock will emit
 * redstone on its own face pointing at this slot.
 */
object FallbackRedstoneSideProvider : ChannelTargetProvider {
    override fun slotsFor(
        level: Level,
        pos: net.minecraft.core.BlockPos,
        state: BlockState,
        clickedFace: Direction,
    ): List<TargetSlot> = listOf(
        TargetSlot.Side(
            name = "redstone ${clickedFace.name.lowercase()}",
            type = PinType.REDSTONE,
            face = clickedFace,
        ),
    )
}
