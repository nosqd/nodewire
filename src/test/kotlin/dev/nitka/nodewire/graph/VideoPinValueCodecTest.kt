package dev.nitka.nodewire.graph

import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class VideoPinValueCodecTest {

    @Test fun `Video roundtrips through PinValue CODEC`() {
        val handle = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val original: PinValue = PinValue.Video(handle)

        val encoded = PinValue.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow()
        val decoded = PinValue.CODEC.parse(NbtOps.INSTANCE, encoded).result().orElseThrow()

        assertEquals(original, decoded)
        assertEquals(handle, (decoded as PinValue.Video).handle)
    }

    @Test fun `default VIDEO is the nil UUID`() {
        assertEquals(PinValue.Video(UUID(0L, 0L)), PinValue.default(PinType.VIDEO))
    }
}
