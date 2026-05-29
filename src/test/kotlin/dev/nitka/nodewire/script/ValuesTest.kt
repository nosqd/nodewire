package dev.nitka.nodewire.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValuesTest {

    @Test fun redstoneClampsHigh() = assertEquals(15, Redstone.of(99).power)

    @Test fun redstoneClampsLow() = assertEquals(0, Redstone.of(-5).power)

    @Test fun redstoneOffIsNotOn() = assertFalse(Redstone.OFF.isOn)

    @Test fun redstoneMaxIsOn() = assertTrue(Redstone.MAX.isOn)

    @Test fun redstoneInvokeKeepsInRangeValue() = assertEquals(7, Redstone(7).power)

    @Test fun quatIdentity() = assertEquals(Quat(0f, 0f, 0f, 1f), Quat.IDENTITY)
}
