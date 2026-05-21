package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlgoNodeEvaluatorsTest {

    private val empty = CompoundTag()

    @Test fun `if then else picks then on true`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(true),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(1f), out["out"])
    }

    @Test fun `if then else picks else on false`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(false),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(2f), out["out"])
    }
}
