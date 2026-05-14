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
 * Client → server: delete one binding from a source [LogicBlockEntity].
 * Supports both flavours via [kind]: CHANNEL = drop a [dev.nitka.nodewire.block.ChannelBinding],
 * SIDE = drop a [dev.nitka.nodewire.block.SideBinding]. [extra] is the
 * target channel name (CHANNEL) or the target side enum name (SIDE).
 *
 * Sent by the [dev.nitka.nodewire.client.screen.BindingsManagerScreen]
 * delete buttons. Server resolves the BE, calls the matching remove*,
 * and pushes a chunk update so other clients drop the wire from view.
 */
class RemoveBindingPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val kind: Kind,
    val extra: String,
) {
    enum class Kind { CHANNEL, SIDE }

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(sourcePos)
        buf.writeUtf(sourceChannelName)
        buf.writeBlockPos(targetPos)
        buf.writeEnum(kind)
        buf.writeUtf(extra)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val center = sourcePos.center
            if (player.distanceToSqr(center.x, center.y, center.z) > MAX_REACH_SQ) {
                LOG.warn("Remove rejected: source too far from {}", player.gameProfile.name)
                return@enqueueWork
            }
            val srcBe = level.getBlockEntity(sourcePos) as? LogicBlockEntity ?: return@enqueueWork
            val ok = when (kind) {
                Kind.CHANNEL -> srcBe.removeBinding(sourceChannelName, targetPos, extra)
                Kind.SIDE -> {
                    val side = runCatching { Direction.valueOf(extra) }.getOrNull()
                        ?: return@enqueueWork
                    srcBe.removeSideBinding(sourceChannelName, targetPos, side)
                }
            }
            if (ok) {
                level.sendBlockUpdated(
                    sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                )
            }
        }
        c.packetHandled = true
        return true
    }

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        private const val MAX_REACH_SQ = 32.0 * 32.0

        fun decode(buf: FriendlyByteBuf): RemoveBindingPacket = RemoveBindingPacket(
            sourcePos = buf.readBlockPos(),
            sourceChannelName = buf.readUtf(),
            targetPos = buf.readBlockPos(),
            kind = buf.readEnum(Kind::class.java),
            extra = buf.readUtf(),
        )
    }
}
