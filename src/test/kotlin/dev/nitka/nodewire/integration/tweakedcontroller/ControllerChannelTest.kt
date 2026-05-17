package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ControllerChannelTest {

    @Test fun stickVec2Raw() {
        val s = ControllerState(leftStickX = 0.5f, leftStickY = -0.25f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.VEC2_RAW, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Vec2(0.5f, -0.25f), out["xy"])
    }

    @Test fun stickXyRedstoneCenteredAt7() {
        val s = ControllerState(leftStickX = 0f, leftStickY = 0f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.XY_REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(7), out["x"])
        assertEquals(PinValue.Redstone(7), out["y"])
    }

    @Test fun stickXyRedstoneFullRange() {
        val s = ControllerState(leftStickX = 1f, leftStickY = -1f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.XY_REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(15), out["x"])
        assertEquals(PinValue.Redstone(0), out["y"])
    }

    @Test fun stickMagnitudeBoolBelowDeadzone() {
        val s = ControllerState(leftStickX = 0.1f, leftStickY = 0.05f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.MAGNITUDE_BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(false), out["pressed"])
    }

    @Test fun stickMagnitudeBoolAboveDeadzone() {
        val s = ControllerState(leftStickX = 0.6f, leftStickY = 0.8f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_STICK, ControllerOutputMode.MAGNITUDE_BOOL, deadzone = 0.15f, invert = false)
        // length = 1.0 > 0.15
        assertEquals(PinValue.Bool(true), out["pressed"])
    }

    @Test fun triggerRedstone() {
        val s = ControllerState(rightTrigger = 1f)
        val out = applyOutputMode(s, ControllerChannel.RIGHT_TRIGGER, ControllerOutputMode.REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(15), out["value"])
    }

    @Test fun triggerRedstoneHalf() {
        val s = ControllerState(leftTrigger = 0.5f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.REDSTONE, deadzone = 0.15f, invert = false)
        // 0.5 * 15 = 7
        assertEquals(PinValue.Redstone(7), out["value"])
    }

    @Test fun triggerBoolBelowDeadzone() {
        val s = ControllerState(leftTrigger = 0.1f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(false), out["pressed"])
    }

    @Test fun triggerBoolAboveDeadzone() {
        val s = ControllerState(leftTrigger = 0.3f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(true), out["pressed"])
    }

    @Test fun buttonBoolPressed() {
        val s = ControllerState(buttonA = true)
        val out = applyOutputMode(s, ControllerChannel.BUTTON_A, ControllerOutputMode.BOOL, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Bool(true), out["pressed"])
    }

    @Test fun buttonRedstone() {
        val s = ControllerState(buttonB = true)
        val out = applyOutputMode(s, ControllerChannel.BUTTON_B, ControllerOutputMode.REDSTONE, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Redstone(15), out["value"])
    }

    @Test fun dpadCompositeBothPressedDiag() {
        val s = ControllerState(dpadUp = true, dpadRight = true)
        val out = applyOutputMode(s, ControllerChannel.DPAD, ControllerOutputMode.VEC2_RAW, deadzone = 0.15f, invert = false)
        assertEquals(PinValue.Vec2(1f, 1f), out["xy"])
    }

    @Test fun triggerInvert() {
        val s = ControllerState(leftTrigger = 0.5f)
        val out = applyOutputMode(s, ControllerChannel.LEFT_TRIGGER, ControllerOutputMode.RAW, deadzone = 0.15f, invert = true)
        assertEquals(PinValue.Float(-0.5f), out["value"])
    }
}
