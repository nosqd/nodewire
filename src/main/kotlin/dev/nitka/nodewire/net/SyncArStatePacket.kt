package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.client.video.ArClientState
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

data class SyncArStatePacket(val videoHandle: UUID) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SyncArStatePacket> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SyncArStatePacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "sync_ar_state")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SyncArStatePacket> =
            object : StreamCodec<RegistryFriendlyByteBuf, SyncArStatePacket> {
                override fun encode(buf: RegistryFriendlyByteBuf, pkt: SyncArStatePacket) {
                    buf.writeUUID(pkt.videoHandle)
                }

                override fun decode(buf: RegistryFriendlyByteBuf): SyncArStatePacket {
                    return SyncArStatePacket(buf.readUUID())
                }
            }

        fun handle(packet: SyncArStatePacket, ctx: IPayloadContext) {
            ctx.enqueueWork {
                ArClientState.activeHandle = packet.videoHandle
            }
        }
    }
}
