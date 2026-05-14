package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsConvertTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    @Test fun intToFloat() = assertEquals(
        PinValue.Float(5f),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "INT"); putString("targetType", "FLOAT") },
            mapOf("in" to PinValue.Int(5)),
        )["out"],
    )

    @Test fun floatToInt() = assertEquals(
        PinValue.Int(3),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "FLOAT"); putString("targetType", "INT") },
            mapOf("in" to PinValue.Float(3.7f)),
        )["out"],
    )

    @Test fun boolToInt() = assertEquals(
        PinValue.Int(1),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "BOOL"); putString("targetType", "INT") },
            mapOf("in" to PinValue.Bool(true)),
        )["out"],
    )

    @Test fun intToBool() = assertEquals(
        PinValue.Bool(true),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "INT"); putString("targetType", "BOOL") },
            mapOf("in" to PinValue.Int(7)),
        )["out"],
    )

    @Test fun intToBoolZeroIsFalse() = assertEquals(
        PinValue.Bool(false),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "INT"); putString("targetType", "BOOL") },
            mapOf("in" to PinValue.Int(0)),
        )["out"],
    )
}
