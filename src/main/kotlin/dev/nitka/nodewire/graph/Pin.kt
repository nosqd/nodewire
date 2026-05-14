package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A single typed slot on a node. [id] is unique within its parent node's
 * input list (or output list) and is what [PinRef] uses to address the
 * pin — display [name] is for the UI and can be changed without breaking
 * saved graphs.
 *
 * Input vs output is determined by which list of [Node] the pin lives in,
 * not by a field here.
 */
data class Pin(
    val id: String,
    val name: String,
    val type: PinType,
) {
    companion object {
        val CODEC: Codec<Pin> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("id").forGetter(Pin::id),
                Codec.STRING.fieldOf("name").forGetter(Pin::name),
                PinType.CODEC.fieldOf("type").forGetter(Pin::type),
            ).apply(i, ::Pin)
        }
    }
}
