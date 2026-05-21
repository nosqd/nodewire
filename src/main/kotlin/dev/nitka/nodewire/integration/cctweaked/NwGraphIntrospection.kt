package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin

/**
 * Shape `nodes` and `edges` of a [NodeGraph] into Lua-safe (UUID-as-string,
 * primitive-typed) maps. CC: Tweaked converts a `Map<String, Object>` into
 * a Lua table on return, but UUIDs aren't recognised — caller must
 * stringify identifiers.
 */
object NwGraphIntrospection {

    fun nodesLua(graph: NodeGraph): List<Map<String, Any?>> =
        graph.nodes.values.map { n ->
            mapOf(
                "id" to n.id.toString(),
                "type" to n.typeKey.toString(),
                "label" to n.label?.takeIf { it.isNotBlank() },
                "inputs" to n.inputs.map(::pinLua),
                "outputs" to n.outputs.map(::pinLua),
            )
        }

    fun edgesLua(graph: NodeGraph): List<Map<String, Any?>> =
        graph.edges.map { e ->
            mapOf(
                "from" to mapOf(
                    "node" to e.from.node.toString(),
                    "pin" to e.from.pin,
                ),
                "to" to mapOf(
                    "node" to e.to.node.toString(),
                    "pin" to e.to.pin,
                ),
                "label" to e.label?.takeIf { it.isNotBlank() },
            )
        }

    private fun pinLua(pin: Pin): Map<String, Any?> = mapOf(
        "id" to pin.id,
        "name" to pin.name,
        "type" to pin.type.name.lowercase(),
    )
}
