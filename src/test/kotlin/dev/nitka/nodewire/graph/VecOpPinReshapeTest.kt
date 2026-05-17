package dev.nitka.nodewire.graph

import dev.nitka.nodewire.client.screen.EditorState
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VecOpPinReshapeTest {

    @BeforeAll
    fun setUp() = StockNodeTypes.registerAll()

    private fun newEditor(): Pair<EditorState, NodeGraph> {
        val g = NodeGraph()
        val es = EditorState(g, BlockPos.ZERO)
        return es to g
    }

    @Test fun defaultOpAddVec2HasTwoVec2InputsAndOneVec2Output() {
        val (es, g) = newEditor()
        val n = VectorNodeTypes.VEC_OP.newInstance()
        es.addNode(n)
        assertEquals(2, n.inputs.size)
        assertEquals(PinType.VEC2, n.inputs[0].type)
        assertEquals(PinType.VEC2, n.outputs[0].type)
    }

    @Test fun changeOpToLengthCollapsesToOneInputFloatOutput() {
        val (es, g) = newEditor()
        val n = VectorNodeTypes.VEC_OP.newInstance()
        es.addNode(n)
        es.changeVecOp(n.id, "LENGTH", "VEC2")
        val refreshed = g.nodes[n.id]!!
        assertEquals(1, refreshed.inputs.size)
        assertEquals("v", refreshed.inputs[0].id)
        assertEquals(PinType.VEC2, refreshed.inputs[0].type)
        assertEquals(1, refreshed.outputs.size)
        assertEquals(PinType.FLOAT, refreshed.outputs[0].type)
    }

    @Test fun changeOpToCrossForcesVec3() {
        val (es, g) = newEditor()
        val n = VectorNodeTypes.VEC_OP.newInstance()
        es.addNode(n)
        es.changeVecOp(n.id, "CROSS", "VEC2")  // caller asked VEC2; CROSS overrides
        val refreshed = g.nodes[n.id]!!
        assertEquals("VEC3", refreshed.config.getString("dim"))
        assertEquals(PinType.VEC3, refreshed.inputs[0].type)
        assertEquals(PinType.VEC3, refreshed.outputs[0].type)
    }

    @Test fun changeOpDropsIncompatibleEdges() {
        val (es, g) = newEditor()
        val a = StockNodeTypes.CONSTANT.newInstance().also {
            it.config.putString("type", PinType.VEC2.name)
        }
        val op = VectorNodeTypes.VEC_OP.newInstance()  // ADD on VEC2 by default
        es.addNode(a)
        es.addNode(op)
        g.addEdge(Edge(PinRef(a.id, "out"), PinRef(op.id, "a")))
        assertEquals(1, g.edges.size)
        // Switch to LENGTH on VEC3 — "a" pin no longer exists (now "v"),
        // and types mismatch → edge dropped.
        es.changeVecOp(op.id, "LENGTH", "VEC3")
        assertTrue(g.edges.isEmpty())
    }
}
