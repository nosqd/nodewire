package dev.nitka.nodewire.graph

import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class CommentCodecTest {
    private fun <T> roundTrip(codec: com.mojang.serialization.Codec<T>, v: T): T {
        val tag = codec.encodeStart(NbtOps.INSTANCE, v).result().orElseThrow()
        return codec.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
    }

    @Test fun commentRoundTrip() {
        val c = Comment(
            id = UUID.randomUUID(),
            pos = CanvasPos(10f, 20f),
            width = 180,
            height = 60,
            text = "Hello\nworld!",
        )
        assertEquals(c, roundTrip(Comment.CODEC, c))
    }

    @Test fun graphWithComments() {
        val c = Comment(
            id = UUID.randomUUID(),
            pos = CanvasPos.Zero,
            width = 100, height = 40,
            text = "note",
        )
        val g = NodeGraph().apply { comments.add(c) }
        val decoded = roundTrip(NodeGraph.CODEC, g)
        assertEquals(1, decoded.comments.size)
        assertEquals(c, decoded.comments[0])
    }
}
