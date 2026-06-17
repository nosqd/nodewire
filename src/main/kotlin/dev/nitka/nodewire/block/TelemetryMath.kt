package dev.nitka.nodewire.block

import org.joml.AxisAngle4d
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d

/**
 * Pure orientation math for the [TelemetryBlock] probe — extracted so it can be
 * unit-tested without a [net.minecraft.world.level.Level] (the codebase verifies
 * its vector math directly, see CbcBallistics).
 *
 * Everything is double precision: a structure's orientation quaternion and its
 * derived angles flow out over DOUBLE-precision QUAT/VEC3 pins.
 */
object TelemetryMath {

    /**
     * Decompose an orientation quaternion into intrinsic **Y-X-Z** Tait–Bryan
     * angles (yaw about world up, then pitch, then roll), in **degrees**.
     *
     * @return `(yaw, pitch, roll)` — yaw ∈ (-180,180], pitch ∈ [-90,90],
     *         roll ∈ (-180,180].
     */
    fun eulerDeg(q: Quaterniondc): Triple<Double, Double, Double> {
        val e = Vector3d()
        // getEulerAnglesYXZ stores (x = pitch about X, y = yaw about Y,
        // z = roll about Z) for the rotation rotateY(y)·rotateX(x)·rotateZ(z).
        Quaterniond(q.x(), q.y(), q.z(), q.w()).getEulerAnglesYXZ(e)
        return Triple(Math.toDegrees(e.y), Math.toDegrees(e.x), Math.toDegrees(e.z))
    }

    /**
     * World-frame angular velocity [rad/s] from the orientation delta between
     * the previous tick ([last]) and the current tick ([cur]).
     *
     * The fixed-frame delta is `dq = cur · last⁻¹`; its axis-angle gives the
     * rotation applied this tick, scaled by [tps] (ticks per second) into a
     * per-second rate. Identity delta → zero vector (axis-angle reports angle 0).
     */
    fun angularVelocity(last: Quaterniondc, cur: Quaterniondc, tps: Double = 20.0): Vector3d {
        val q0 = Quaterniond(last.x(), last.y(), last.z(), last.w())
        val q1 = Quaterniond(cur.x(), cur.y(), cur.z(), cur.w())
        // q1.mul(q0.conjugate()) = cur · last⁻¹ (both unit quaternions).
        val dq = q1.mul(q0.conjugate())
        val aa = AxisAngle4d().set(dq)
        val w = aa.angle * tps
        return Vector3d(aa.x * w, aa.y * w, aa.z * w)
    }
}
