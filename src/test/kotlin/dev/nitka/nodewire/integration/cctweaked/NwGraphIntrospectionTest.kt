package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NwGraphIntrospectionTest {

    private fun bareNode(id: java.util.UUID = Node.newId()): Node = Node(
        id = id,
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "channel_input"),
        pos = CanvasPos(0f, 0f),
        inputs = emptyList(),
        outputs = listOf(Pin("out", "Value", PinType.BOOL)),
        config = CompoundTag(),
    )

    @Test fun `nodesLua emits one map per node`() {
        val g = NodeGraph()
        val n = bareNode()
        g.add(n)
        val list = NwGraphIntrospection.nodesLua(g)
        assertEquals(1, list.size)
        val m = list[0]
        assertEquals(n.id.toString(), m["id"])
        assertEquals("nodewire:channel_input", m["type"])
        val outputs = m["outputs"] as List<*>
        assertEquals(1, outputs.size)
        val outPin = outputs[0] as Map<*, *>
        assertEquals("out", outPin["id"])
        assertEquals("bool", outPin["type"])
    }

    @Test fun `edgesLua emits from to label`() {
        val g = NodeGraph()
        val a = bareNode()
        val b = bareNode()
        g.add(a); g.add(b)
        g.addEdge(Edge(
            from = PinRef(a.id, "out"),
            to = PinRef(b.id, "out"),
            label = "wire-A",
        ))
        val list = NwGraphIntrospection.edgesLua(g)
        assertEquals(1, list.size)
        val e = list[0]
        val from = e["from"] as Map<*, *>
        assertEquals(a.id.toString(), from["node"])
        assertEquals("out", from["pin"])
        assertEquals("wire-A", e["label"])
    }
}
