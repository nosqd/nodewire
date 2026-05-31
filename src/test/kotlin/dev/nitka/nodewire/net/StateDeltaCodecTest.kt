package dev.nitka.nodewire.net

import dev.nitka.nodewire.script.StateKind
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class StateDeltaCodecTest {

    @Test fun `packet roundtrips through CODEC`() {
        val tagA = CompoundTag().apply { putInt("ammo", 12) }
        val tagB = CompoundTag().apply { putInt("power", 15) } // REDSTONE-as-int
        val original = StateDeltaPacket(
            blockPos = BlockPos(4, 64, -9),
            nodeId = UUID.fromString("00000000-0000-0000-0000-0000000000ab"),
            deltas = listOf(
                CellDelta("ammo", StateKind.INT, tagA),
                CellDelta("power", StateKind.REDSTONE, tagB),
            ),
        )

        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeCodec(StateDeltaPacket.CODEC, original)
        val decoded = buf.readCodec(StateDeltaPacket.CODEC)

        assertEquals(original.blockPos, decoded.blockPos)
        assertEquals(original.nodeId, decoded.nodeId)
        assertEquals(2, decoded.deltas.size)
        assertEquals("ammo", decoded.deltas[0].key)
        assertEquals(StateKind.INT, decoded.deltas[0].kind)
        assertEquals(12, decoded.deltas[0].value.getInt("ammo"))
        assertEquals(StateKind.REDSTONE, decoded.deltas[1].kind)
        assertEquals(15, decoded.deltas[1].value.getInt("power"))
    }
}
