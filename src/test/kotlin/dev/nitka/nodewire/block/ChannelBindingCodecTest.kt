package dev.nitka.nodewire.block

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChannelBindingCodecTest {
    @BeforeEach fun reset() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(WorldBackend)
    }

    @Test fun `round-trip with EndpointRef target`() {
        val ref = EndpointRef(WorldBackend.id, WorldPayload(BlockPos(4, 5, 6)))
        val cb = ChannelBinding("out", ref, "in")
        val tag = ChannelBinding.CODEC.encodeStart(NbtOps.INSTANCE, cb).result().orElseThrow()
        val decoded = ChannelBinding.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
        assertEquals(cb, decoded)
    }

    @Test fun `legacy BlockPos pos field migrates to World EndpointRef`() {
        // Legacy NBT shape: { src: "out", pos: [I; 4,5,6], dst: "in" }
        val legacy = JsonParser.parseString(
            """{"src":"out","pos":[4,5,6],"dst":"in"}"""
        )
        val decoded = ChannelBinding.CODEC.parse(JsonOps.INSTANCE, legacy).result().orElseThrow()
        assertEquals("out", decoded.sourceChannelName)
        assertEquals("in", decoded.targetChannelName)
        assertEquals(WorldBackend.id, decoded.target.backendId)
        assertEquals(BlockPos(4, 5, 6), decoded.target.payload.blockPos)
    }
}
