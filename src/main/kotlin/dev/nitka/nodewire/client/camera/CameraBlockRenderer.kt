package dev.nitka.nodewire.client.camera

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.nitka.nodewire.block.CameraBlock
import dev.nitka.nodewire.block.CameraBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation

/**
 * Renders the rotatable Camera Block's moving gimbal — the `camera_yaw` yoke
 * (spins about the vertical axis) and the `camera_head` (pitches + rolls inside
 * the yoke). The static `body` is the chunk-baked block model; only the two
 * moving parts are drawn here, each as a standalone baked model (registered in
 * [dev.nitka.nodewire.client.NodewireClient]).
 *
 * Transform composition (pivots in 0..1 block space):
 *  * yoke  = facing · yaw   about the vertical centre (0.5, ·, 0.5)
 *  * head  = facing · yaw   about the centre, THEN pitch (X) · roll (Z) about
 *            the camera pivot (0.5, 12/16, 0.5) — so the head inherits the
 *            yaw and rides the yoke, exactly like a real PTZ gimbal.
 *
 * This is also the Iris-shaderpack fallback for the (future) Flywheel
 * [CameraBlockEntity] visual; the angles are eased per frame for a smooth sweep.
 *
 * NOTE: rotation SIGNS (facing/yaw/pitch) are the conventions to verify in-game;
 * flip the corresponding [yawSign]/[pitchSign]/[facingSign] constant if a part
 * turns the wrong way.
 */
class CameraBlockRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CameraBlockEntity> {

    override fun render(
        be: CameraBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        // The Fixed Camera renders its whole (static) model from the chunk —
        // no moving parts to draw here.
        if (!be.isRotatable()) return

        val mc = Minecraft.getInstance()
        val models = mc.modelManager
        val yawModel = models.getModel(YAW_MODEL)
        val headModel = models.getModel(HEAD_MODEL)
        val renderer = mc.blockRenderer.modelRenderer
        val state = be.blockState

        // Live gimbal angles (from pins) — followed 1:1 so the lens always
        // matches where the capture actually looks. Smoothness comes from the
        // source signal; easing here only added lag ("rotates very slowly").
        val yaw = be.yawDeg()
        val pitch = be.pitchDeg()
        val roll = be.rollDeg()

        val facing = facingDeg(state.getValue(CameraBlock.FACING))
        val yDeg = facingSign * (facing + yawSign * yaw)

        val vc = buffers.getBuffer(RenderType.cutout())

        // ── yoke: facing + yaw about the vertical centre ──
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(yDeg))
        pose.translate(-0.5, -0.5, -0.5)
        renderer.renderModel(pose.last(), vc, state, yawModel, 1f, 1f, 1f, light, OverlayTexture.NO_OVERLAY)
        pose.popPose()

        // ── head: inherits yaw, then pitch (X) + roll (Z) about the cam pivot ──
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(yDeg))
        pose.translate(-0.5, -0.5, -0.5)
        pose.translate(CAM_PIVOT_X, CAM_PIVOT_Y, CAM_PIVOT_Z)
        pose.mulPose(Axis.XP.rotationDegrees(pitchSign * pitch))
        pose.mulPose(Axis.ZP.rotationDegrees(roll))
        pose.translate(-CAM_PIVOT_X, -CAM_PIVOT_Y, -CAM_PIVOT_Z)
        renderer.renderModel(pose.last(), vc, state, headModel, 1f, 1f, 1f, light, OverlayTexture.NO_OVERLAY)
        pose.popPose()
    }

    companion object {
        val YAW_MODEL: ModelResourceLocation =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("nodewire", "block/camera_yaw"))
        val HEAD_MODEL: ModelResourceLocation =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("nodewire", "block/camera_head"))

        // Camera pivot (model 8,12,8 → 0..1).
        private const val CAM_PIVOT_X = 0.5
        private const val CAM_PIVOT_Y = 12.0 / 16.0
        private const val CAM_PIVOT_Z = 0.5

        // Sign conventions — flip in-game if a part turns the wrong way.
        private const val facingSign = -1f // match vanilla blockstate "y" (negative Y)
        private const val yawSign = 1f
        private const val pitchSign = 1f

        private fun facingDeg(facing: Direction): Float = when (facing) {
            Direction.SOUTH -> 180f
            Direction.WEST -> 270f
            Direction.EAST -> 90f
            else -> 0f // NORTH (and any non-horizontal, shouldn't happen)
        }
    }
}
