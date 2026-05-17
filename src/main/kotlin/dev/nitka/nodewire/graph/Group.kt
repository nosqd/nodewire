package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.UUID

typealias GroupId = UUID
typealias TemplateNodeId = UUID

/**
 * One member of a [Group]. A group can contain raw nodes (referenced by
 * [Node]) or other groups (referenced by [Sub]) — the latter is how
 * nesting is encoded.
 */
sealed interface MemberRef {
    data class Node(val id: NodeId) : MemberRef
    data class Sub(val id: GroupId) : MemberRef

    companion object {
        val CODEC: Codec<MemberRef> = Codec.STRING.flatComapMap(
            { raw ->
                val (kind, idStr) = raw.split(':', limit = 2)
                when (kind) {
                    "n" -> Node(UUID.fromString(idStr))
                    "g" -> Sub(UUID.fromString(idStr))
                    else -> error("Unknown MemberRef kind: $kind")
                }
            },
            { m ->
                when (m) {
                    is Node -> com.mojang.serialization.DataResult.success("n:${m.id}")
                    is Sub  -> com.mojang.serialization.DataResult.success("g:${m.id}")
                }
            },
        )
    }
}

/**
 * Visual container metadata over a flat [NodeGraph]. Evaluator does not
 * consult [Group]s — they only drive editor rendering and the live-sync
 * template store.
 */
data class Group(
    val id: GroupId,
    val name: String,
    val members: List<MemberRef>,
    val templateFile: String?,
    val templateIdMap: Map<TemplateNodeId, NodeId>?,
    val collapsed: Boolean,
    val pos: CanvasPos,
    val collapsedSize: Pair<Int, Int>?,
    val pinLabelOverrides: Map<String, String> = emptyMap(),
) {
    companion object {
        fun newId(): GroupId = UUID.randomUUID()

        private val SIZE_CODEC: Codec<Pair<Int, Int>> =
            RecordCodecBuilder.create { i ->
                i.group(
                    Codec.INT.fieldOf("w").forGetter(Pair<Int, Int>::first),
                    Codec.INT.fieldOf("h").forGetter(Pair<Int, Int>::second),
                ).apply(i) { w, h -> w to h }
            }

        private val ID_MAP_CODEC: Codec<Map<TemplateNodeId, NodeId>> =
            Codec.unboundedMap(GraphCodecs.UUID_CODEC, GraphCodecs.UUID_CODEC)

        val CODEC: Codec<Group> = RecordCodecBuilder.create { i ->
            i.group(
                GraphCodecs.UUID_CODEC.fieldOf("id").forGetter(Group::id),
                Codec.STRING.fieldOf("name").forGetter(Group::name),
                MemberRef.CODEC.listOf().fieldOf("members").forGetter(Group::members),
                Codec.STRING.optionalFieldOf("templateFile").forGetter { java.util.Optional.ofNullable(it.templateFile) },
                ID_MAP_CODEC.optionalFieldOf("templateIdMap").forGetter { java.util.Optional.ofNullable(it.templateIdMap) },
                Codec.BOOL.fieldOf("collapsed").forGetter(Group::collapsed),
                CanvasPos.CODEC.fieldOf("pos").forGetter(Group::pos),
                SIZE_CODEC.optionalFieldOf("collapsedSize").forGetter { java.util.Optional.ofNullable(it.collapsedSize) },
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("pinLabelOverrides").forGetter(Group::pinLabelOverrides),
            ).apply(i) { id, name, members, file, idMap, coll, pos, size, labels ->
                Group(
                    id = id,
                    name = name,
                    members = members,
                    templateFile = file.orElse(null),
                    templateIdMap = idMap.orElse(null),
                    collapsed = coll,
                    pos = pos,
                    collapsedSize = size.orElse(null),
                    pinLabelOverrides = labels,
                )
            }
        }
    }
}
