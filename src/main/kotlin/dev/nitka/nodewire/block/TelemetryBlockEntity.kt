package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinPort
import dev.nitka.nodewire.link.PinReading
import dev.ryanhcode.sable.companion.SableCompanion
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/**
 * BlockEntity for [TelemetryBlock] — a pure [PinPort] source over the Sable
 * physics of whatever sub-level the block sits in.
 *
 * It holds no persisted state and is not a [dev.nitka.nodewire.link.PinLinkSink]:
 * every output pin is sampled on demand from Sable's live pose/velocity in
 * [readPin], which the consuming block's [dev.nitka.nodewire.link.PinLinkEngine]
 * calls each **server** tick. Reads are server-authoritative (Sable's velocity
 * field is a server-side quantity); the client side returns null and the picker
 * still sees [pinOutputs].
 *
 * Resolution mirrors [dev.nitka.nodewire.integration.sable.SableSubLevelBackend]:
 * `SableCompanion.INSTANCE.getContaining(level, blockPos)` finds the sub-level
 * (the stored [blockPos] already lives in the parent level's plot coordinates),
 * its [dev.ryanhcode.sable.companion.math.Pose3dc] gives the world transform,
 * and `getVelocity` / `getVelocityRelativeToAir` give motion at the block. When
 * the companion has no sub-level there (Sable absent, or the block is on the
 * static world) every pin degrades to a sensible static value.
 */
class TelemetryBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.TELEMETRY_BLOCK_BE.get(), pos, state),
    PinPort {

    override fun pinOutputs(ctx: LinkContext): List<LinkPin> = PINS

    override fun readPin(id: String): PinReading? {
        val lvl = level ?: return null
        if (lvl.isClientSide) return null // server-authoritative physics
        return sample(lvl, blockPos, id)?.let { PinReading(it) }
    }

    companion object {
        const val POSITION = "position"
        const val VELOCITY = "velocity"
        const val SPEED = "speed"
        const val AIRSPEED = "airspeed"
        const val ORIENTATION = "orientation"
        const val YAW = "yaw"
        const val PITCH = "pitch"
        const val ROLL = "roll"
        const val ANGULAR_VELOCITY = "angular_velocity"

        private val PINS: List<LinkPin> = listOf(
            LinkPin(POSITION, PinType.VEC3, "Position"),
            LinkPin(VELOCITY, PinType.VEC3, "Velocity"),
            LinkPin(SPEED, PinType.FLOAT, "Speed"),
            LinkPin(AIRSPEED, PinType.FLOAT, "Airspeed"),
            LinkPin(ORIENTATION, PinType.QUAT, "Orientation"),
            LinkPin(YAW, PinType.FLOAT, "Yaw"),
            LinkPin(PITCH, PinType.FLOAT, "Pitch"),
            LinkPin(ROLL, PinType.FLOAT, "Roll"),
            LinkPin(ANGULAR_VELOCITY, PinType.VEC3, "Angular velocity"),
        )

        /**
         * One physics sample for output pin [id] at [pos] — **server thread**.
         * Returns null for an unknown pin id; never throws.
         *
         * Velocity is in blocks/second; angles in degrees; angular velocity in
         * rad/s about the world axes.
         */
        // getVelocityRelativeToAir is @Deprecated (ScheduledForRemoval 2.0.0)
        // across its whole overload family in Sable 1.6.0, with no replacement
        // shipped yet — keep using it for the airspeed pin until one exists.
        @Suppress("DEPRECATION")
        fun sample(level: Level, pos: BlockPos, id: String): PinValue? {
            val companion = SableCompanion.INSTANCE
            // Plot coordinate (= where the block is physically stored), the same
            // space SableSubLevelBackend resolves and getContaining expects.
            val plot = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            val sub = companion.getContaining(level, pos)

            if (sub == null) {
                // Static world / Sable absent: a probe with no motion.
                return when (id) {
                    POSITION -> PinValue.Vec3(plot.x, plot.y, plot.z)
                    VELOCITY, ANGULAR_VELOCITY -> PinValue.Vec3(0.0, 0.0, 0.0)
                    SPEED, AIRSPEED, YAW, PITCH, ROLL -> PinValue.Float(0f)
                    ORIENTATION -> PinValue.Quat(0.0, 0.0, 0.0, 1.0)
                    else -> null
                }
            }

            val pose = sub.logicalPose()
            // joml `Vector3d` overloads of getVelocity* are the non-deprecated
            // path; each call gets a fresh vector (the default impl writes the
            // result back into the argument it was handed).
            fun point() = Vector3d(plot.x, plot.y, plot.z)
            return when (id) {
                POSITION -> {
                    val w = pose.transformPosition(plot)
                    PinValue.Vec3(w.x, w.y, w.z)
                }
                VELOCITY -> {
                    val v = companion.getVelocity(level, point())
                    PinValue.Vec3(v.x, v.y, v.z)
                }
                SPEED -> PinValue.Float(companion.getVelocity(level, point()).length().toFloat())
                AIRSPEED -> {
                    // Canonical 3-arg form (the 2-arg convenience overloads are
                    // @Deprecated for this variant); dest receives the result.
                    val v = companion.getVelocityRelativeToAir(level, point(), Vector3d())
                    PinValue.Float(v.length().toFloat())
                }
                ORIENTATION -> {
                    val q = pose.orientation()
                    PinValue.Quat(q.x(), q.y(), q.z(), q.w())
                }
                YAW -> PinValue.Float(TelemetryMath.eulerDeg(pose.orientation()).first.toFloat())
                PITCH -> PinValue.Float(TelemetryMath.eulerDeg(pose.orientation()).second.toFloat())
                ROLL -> PinValue.Float(TelemetryMath.eulerDeg(pose.orientation()).third.toFloat())
                ANGULAR_VELOCITY -> {
                    val w = TelemetryMath.angularVelocity(sub.lastPose().orientation(), pose.orientation())
                    PinValue.Vec3(w.x, w.y, w.z)
                }
                else -> null
            }
        }
    }
}
