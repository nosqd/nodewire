package dev.nitka.nodewire.block

import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * [TelemetryMath] feeds DOUBLE-precision QUAT/VEC3 pins, so its quaternion
 * decomposition and pose-delta differentiation are verified directly here
 * (no Minecraft / no Level needed).
 */
class TelemetryMathTest {

    private val EPS = 1e-6

    @Test
    fun `pure yaw decomposes to yaw only`() {
        // 30° about world up (Y).
        val q = Quaterniond().rotateY(Math.toRadians(30.0))
        val (yaw, pitch, roll) = TelemetryMath.eulerDeg(q)
        assertEquals(30.0, yaw, 1e-4)
        assertEquals(0.0, pitch, 1e-4)
        assertEquals(0.0, roll, 1e-4)
    }

    @Test
    fun `pure pitch decomposes to pitch only`() {
        val q = Quaterniond().rotateX(Math.toRadians(-15.0))
        val (yaw, pitch, roll) = TelemetryMath.eulerDeg(q)
        assertEquals(0.0, yaw, 1e-4)
        assertEquals(-15.0, pitch, 1e-4)
        assertEquals(0.0, roll, 1e-4)
    }

    @Test
    fun `identity orientation is all zero angles`() {
        val (yaw, pitch, roll) = TelemetryMath.eulerDeg(Quaterniond())
        assertEquals(0.0, yaw, EPS)
        assertEquals(0.0, pitch, EPS)
        assertEquals(0.0, roll, EPS)
    }

    @Test
    fun `no rotation between ticks yields zero angular velocity`() {
        val q = Quaterniond().rotateY(Math.toRadians(42.0))
        val w = TelemetryMath.angularVelocity(q, Quaterniond(q))
        assertEquals(0.0, w.length(), EPS)
    }

    @Test
    fun `yaw rate of one tick scales to radians per second about Y`() {
        val last = Quaterniond() // identity
        val step = 0.05 // rad this tick
        val cur = Quaterniond().rotateY(step)
        val w = TelemetryMath.angularVelocity(last, cur, tps = 20.0)
        // World-frame angular velocity points along +Y at step * tps rad/s.
        assertEquals(0.0, w.x, 1e-9)
        assertEquals(step * 20.0, w.y, 1e-9)
        assertEquals(0.0, w.z, 1e-9)
    }

    @Test
    fun `angular velocity axis follows the rotation axis`() {
        // A rotation about world X should produce angular velocity along X.
        val last = Quaterniond()
        val cur = Quaterniond().rotateX(0.1)
        val w = TelemetryMath.angularVelocity(last, cur, tps = 20.0)
        assertEquals(0.1 * 20.0, w.x, 1e-9)
        assertEquals(true, abs(w.y) < 1e-9 && abs(w.z) < 1e-9)
    }
}
