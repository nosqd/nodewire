package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.UUID

typealias CommentId = UUID

/**
 * A floating plain-text annotation on the canvas. Like [Group], it is
 * pure visual metadata; the evaluator never reads it.
 */
data class Comment(
    val id: CommentId,
    val pos: CanvasPos,
    val width: Int,
    val height: Int,
    val text: String,
) {
    companion object {
        fun newId(): CommentId = UUID.randomUUID()

        val CODEC: Codec<Comment> = RecordCodecBuilder.create { i ->
            i.group(
                GraphCodecs.UUID_CODEC.fieldOf("id").forGetter(Comment::id),
                CanvasPos.CODEC.fieldOf("pos").forGetter(Comment::pos),
                Codec.INT.fieldOf("w").forGetter(Comment::width),
                Codec.INT.fieldOf("h").forGetter(Comment::height),
                Codec.STRING.fieldOf("text").forGetter(Comment::text),
            ).apply(i, ::Comment)
        }
    }
}
