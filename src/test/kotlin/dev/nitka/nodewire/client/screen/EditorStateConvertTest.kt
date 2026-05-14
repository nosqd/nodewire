package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.StockNodeTypes
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class EditorStateConvertTest {
    companion object {
        @BeforeAll @JvmStatic fun reg() { StockNodeTypes.registerAll() }
    }

    @Test fun changeConvertTypesRebuildsInputOutputAndDisconnects() {
        val graph = NodeGraph()
        // Default CONVERT: in=INT, out=FLOAT
        val conv = StockNodeTypes.CONVERT.newInstance(CanvasPos.Zero)
        val source = StockNodeTypes.CONSTANT.newInstance(CanvasPos.Zero).also {
            it.config.putString("type", "INT")
        }
        graph.add(conv); graph.add(source)
        graph.addEdge(Edge(PinRef(source.id, "out"), PinRef(conv.id, "in")))
        val editor = EditorState(graph, BlockPos.ZERO)

        editor.changeConvertTypes(conv.id, PinType.BOOL, PinType.INT)

        val updated = editor.nodeFlow(conv.id)!!.value
        assertEquals(PinType.BOOL, updated.inputs.first().type)
        assertEquals(PinType.INT, updated.outputs.first().type)
        assertEquals("BOOL", updated.config.getString("sourceType"))
        assertEquals("INT", updated.config.getString("targetType"))
        // the edge must have been disconnected
        assertTrue(editor.edges.value.isEmpty())
    }
}
