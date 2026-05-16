package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos

/**
 * One cross-block channel link. Target identity is an [EndpointRef], so a
 * binding can point at a logic block in the world, on a VS ship, or in a
 * Create contraption (sub-project #2) without code change.
 *
 * Legacy NBT (pre-2026-05-16) stored `pos: BlockPos`; [LEGACY_CODEC]
 * reads that shape and wraps it as `EndpointRef.World(pos)`.
 */
data class ChannelBinding(
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetChannelName: String,
) {
    companion object {
        private val NEW_CODEC: Codec<ChannelBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(ChannelBinding::sourceChannelName),
                EndpointRef.CODEC.fieldOf("target").forGetter(ChannelBinding::target),
                Codec.STRING.fieldOf("dst").forGetter(ChannelBinding::targetChannelName),
            ).apply(i, ::ChannelBinding)
        }

        private val LEGACY_CODEC: Codec<ChannelBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(ChannelBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter { it.target.payload.blockPos },
                Codec.STRING.fieldOf("dst").forGetter(ChannelBinding::targetChannelName),
            ).apply(i) { src, pos, dst ->
                ChannelBinding(src, EndpointRef(WorldBackend.id, WorldPayload(pos)), dst)
            }
        }

        // either(new, legacy): try the new format first; fall back to legacy.
        // Writes always use NEW_CODEC (the first arm).
        val CODEC: Codec<ChannelBinding> = Codec.either(NEW_CODEC, LEGACY_CODEC)
            .xmap(
                { e -> e.map({ it }, { it }) },
                { com.mojang.datafixers.util.Either.left(it) },
            )
    }
}
