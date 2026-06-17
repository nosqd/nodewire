package dev.nitka.nodewire.client.control

import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.block.ControlBlockEntity
import dev.nitka.nodewire.block.control.BindKind
import dev.nitka.nodewire.block.control.Binding
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.net.ControlInputPacket
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW

/**
 * Client-side piloting session for a Control Block. While [active], every
 * client tick reads the bound keys/mouse-buttons, computes each [Binding]'s pin
 * value from the block's synced layout and streams them to the server
 * ([ControlInputPacket]) for the BE to serve to linked consumers.
 *
 * Toggled by right-clicking the block ([toggle]); auto-exits if the player gets
 * too far away or the block is gone. Capture pauses while a GUI is open so the
 * pilot can use menus without "ghost" input.
 */
object ControlSession {

    private const val EXIT_DIST_SQ = 8.0 * 8.0

    var active: BlockPos? = null
        private set

    /** Mouse aiming on/off (toggled by the keybind); only meaningful in a session. */
    var mouseCaptured: Boolean = false
        private set

    /** Wheel delta accumulated since the last sent tick (only while captured). */
    private var scrollAccum: Double = 0.0

    fun isActive(): Boolean = active != null

    /** Right-click a block: enter its session, or exit if already piloting it. */
    fun toggle(pos: BlockPos) {
        active = if (active == pos) null else pos
        mouseCaptured = false
        scrollAccum = 0.0
    }

    fun exit() {
        active = null
        mouseCaptured = false
        scrollAccum = 0.0
    }

    /** Keybind: toggle mouse aiming (the "capture") within the active session. */
    fun toggleMouse() {
        if (isActive()) mouseCaptured = !mouseCaptured
    }

    /** Feed wheel deltas while aiming (from the scroll event). */
    fun addScroll(delta: Double) {
        if (isActive() && mouseCaptured) scrollAccum += delta
    }

    /** One capture tick. No-op unless a session is active and unobstructed. */
    fun update() {
        val pos = active ?: return
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return // don't capture while a GUI is open
        val player = mc.player ?: return exit()
        val level = mc.level ?: return exit()
        val be = level.getBlockEntity(pos) as? ControlBlockEntity ?: return exit()
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > EXIT_DIST_SQ) return exit()

        val window = mc.window.window
        val values = HashMap<String, PinValue>(be.bindings().size + 2)
        for (b in be.bindings()) values[b.pin] = compute(b, window, player)
        values[ControlBlockEntity.ACTIVE_PIN] = PinValue.Bool(true)
        values[ControlBlockEntity.MOUSE_CAPTURED_PIN] = PinValue.Bool(mouseCaptured)
        scrollAccum = 0.0 // consumed this tick
        PacketDistributor.sendToServer(ControlInputPacket(pos, values))
    }

    private fun down(window: Long, key: Int): Boolean {
        if (key < 0) return false
        return if (key >= Binding.MOUSE_BUTTON_BASE) {
            GLFW.glfwGetMouseButton(window, key - Binding.MOUSE_BUTTON_BASE) == GLFW.GLFW_PRESS
        } else {
            InputConstants.isKeyDown(window, key)
        }
    }

    private fun compute(b: Binding, window: Long, player: net.minecraft.world.entity.player.Player): PinValue {
        fun key(i: Int): Int = b.keys.getOrElse(i) { -1 }
        // Axis from a (negative, positive) key pair → -1 / 0 / +1.
        fun axis(neg: Int, pos: Int): Float =
            (if (down(window, pos)) 1f else 0f) - (if (down(window, neg)) 1f else 0f)

        return when (b.kind) {
            BindKind.BUTTON -> PinValue.Bool(down(window, key(0)))
            BindKind.AXIS -> PinValue.Float(axis(key(0), key(1)))
            BindKind.VECTOR -> {
                // keys = [forward, back, left, right]
                val y = axis(key(1), key(0)) // pos=forward(0), neg=back(1) → forward − back
                val x = axis(key(2), key(3)) // pos=right(3), neg=left(2) → right − left
                PinValue.Vec2(x.toDouble(), y.toDouble())
            }
            // Absolute head look (degrees) while captured: x = yaw, y = pitch.
            // The graph gates on `mouse_captured` (e.g. sample-and-hold on release).
            BindKind.MOUSE_LOOK ->
                if (mouseCaptured) PinValue.Vec2(player.yRot.toDouble(), player.xRot.toDouble())
                else PinValue.Vec2(0.0, 0.0)
            BindKind.SCROLL -> PinValue.Float(if (mouseCaptured) scrollAccum.toFloat() else 0f)
        }
    }
}
