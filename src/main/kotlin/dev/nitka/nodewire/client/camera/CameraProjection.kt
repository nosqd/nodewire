package dev.nitka.nodewire.client.camera

import dev.nitka.nodewire.client.video.ArClientState
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Client glue behind `VideoCanvas.project`: resolves the live [CameraFeed]
 * for a handle, takes its Sable-aware pose + live FOV and projects a world
 * position into CANVAS px via the pure [WorldToScreen] math.
 *
 * The aspect is taken from the TARGET canvas (not the source surface): the
 * primary pattern is `image(cam.value)` filling the canvas, where the blit
 * stretch and this projection cancel out exactly.
 *
 * AR Glasses override: when [handle] matches [ArClientState.activeHandle],
 * the player's own camera (position/yaw/pitch/FOV) is used instead of a
 * [CameraFeed], so scripts can project 3D world coordinates onto the AR HUD.
 */
object CameraProjection {

    fun projectToCanvas(
        handle: UUID,
        canvasW: Int,
        canvasH: Int,
        worldX: Double,
        worldY: Double,
        worldZ: Double,
    ): FloatArray? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null

        if (handle == ArClientState.activeHandle) {
            val camera = mc.gameRenderer.mainCamera
            val eye = camera.position
            val yawDeg = camera.yRot
            val pitchDeg = camera.xRot
            // EFFECTIVE FOV, not the base setting: multiply the base by the
            // renderer's smoothed sprint/speed FOV factor (lerped oldFov→fov by
            // the frame partial tick) so markers stay locked while you run/stop.
            val partial = mc.timer.getGameTimeDeltaPartialTick(true)
            val fovMul = net.minecraft.util.Mth.lerp(partial, mc.gameRenderer.oldFov, mc.gameRenderer.fov).toDouble()
            val fovYDeg = mc.options.fov().get().toDouble() * fovMul
            return WorldToScreen.project(
                eye.x, eye.y, eye.z,
                yawDeg, pitchDeg,
                fovYDeg,
                canvasW, canvasH,
                worldX, worldY, worldZ,
            )
        }

        val feed = CameraFeedRegistry.byHandle(handle) ?: return null
        val (eye, yawPitch) = feed.worldPose(level, mc.timer) ?: return null
        return WorldToScreen.project(
            eye.x,
            eye.y,
            eye.z,
            yawPitch[0],
            yawPitch[1],
            feed.fovDeg(),
            canvasW,
            canvasH,
            worldX,
            worldY,
            worldZ,
        )
    }
}
