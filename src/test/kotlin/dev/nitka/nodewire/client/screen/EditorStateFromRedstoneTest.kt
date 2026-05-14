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

class EditorStateFromRedstoneTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun registerTypes() {
            StockNodeTypes.registerAll()
        }
    }

    @Test
    fun changeFromRedstoneOutputRebuildsPinAndDisconnects() {
        val graph = NodeGraph()
        val from = StockNodeTypes.FROM_REDSTONE.newInstance(CanvasPos.Zero)
        val intConsumer = StockNodeTypes.CONVERT_TO_REDSTONE.newInstance(CanvasPos.Zero)
        graph.add(from); graph.add(intConsumer)
        graph.addEdge(Edge(PinRef(from.id, "out"), PinRef(intConsumer.id, "in")))

        val editor = EditorState(graph, BlockPos.ZERO)
        editor.changeFromRedstoneOutput(from.id, PinType.BOOL)

        val updated = editor.nodeFlow(from.id)!!.value
        assertEquals(PinType.BOOL, updated.outputs.first().type)
        assertTrue(editor.edges.value.none { it.from.node == from.id })
    }
}
