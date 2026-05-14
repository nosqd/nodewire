package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.block.Block
import net.minecraftforge.network.NetworkEvent
import org.slf4j.Logger
import java.util.function.Supplier

/**
 * Client → server: commit a drive-by-wire side-binding. The source is a
 * LogicBlockEntity at [sourcePos] with channel [sourceChannelName]; the
 * target is the block at [targetPos] driven on face [targetSide]. Server
 * validates: adjacency, channel exists, type is redstone-coercible.
 */
class BindSideChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetSide: Direction,
) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(sourcePos)
        buf.writeUtf(sourceChannelName)
        buf.writeBlockPos(targetPos)
        buf.writeEnum(targetSide)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val center = sourcePos.center
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_REACH_SQ) {
                LOG.warn("SideBind rejected: source too far from {}", player.gameProfile.name)
                return@enqueueWork
            }
            val srcBe = level.getBlockEntity(sourcePos) as? LogicBlockEntity ?: return@enqueueWork
            val ok = srcBe.addSideBinding(sourceChannelName, targetPos, targetSide)
            if (ok) {
                level.sendBlockUpdated(
                    sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                )
                // Force a redstone update so the target picks up the new
                // signal level even if the source's faceOutputs flip in
                // the same tick as the bind.
                level.updateNeighborsAt(sourcePos, srcBe.blockState.block)
            }
        }
        c.packetHandled = true
        return true
    }

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 16.0 * 16.0

        fun decode(buf: FriendlyByteBuf): BindSideChannelPacket = BindSideChannelPacket(
            sourcePos = buf.readBlockPos(),
            sourceChannelName = buf.readUtf(),
            targetPos = buf.readBlockPos(),
            targetSide = buf.readEnum(Direction::class.java),
        )
    }
}
