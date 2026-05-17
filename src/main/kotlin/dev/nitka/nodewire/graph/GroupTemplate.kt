package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * On-disk template: a `NodeGraph`-shaped slice keyed by [TemplateNodeId]s
 * instead of runtime [NodeId]s. Loaded by `GroupTemplateStore` and
 * instantiated into a host graph via `GroupTemplateResolver`.
 *
 * Internal edges reference template ids on both endpoints. External wires
 * never appear here — they live in the host graph that contains an
 * instance of this template.
 *
 * Nested groups: a template may itself contain [Group] entries whose
 * [Group.members] reference [MemberRef.Node] ids that are also keys in
 * [nodes]. Sub-groups can be inline or further linked to other template
 * files — those files are resolved recursively at instantiation.
 */
data class GroupTemplate(
    val nodes: Map<TemplateNodeId, Node>,
    val edges: List<Edge>,
    val groups: List<Group>,
) {
    companion object {
        val CODEC: Codec<GroupTemplate> = RecordCodecBuilder.create { i ->
            i.group(
                Node.CODEC.listOf().fieldOf("nodes").forGetter { it.nodes.values.toList() },
                Edge.CODEC.listOf().fieldOf("edges").forGetter(GroupTemplate::edges),
                Group.CODEC.listOf().fieldOf("groups").forGetter(GroupTemplate::groups),
            ).apply(i) { nodeList, edgeList, groupList ->
                GroupTemplate(
                    nodes = nodeList.associateBy { it.id },
                    edges = edgeList,
                    groups = groupList,
                )
            }
        }
    }
}
