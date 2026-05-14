package dev.nitka.nodewire.client.wire

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.block.ChannelBinding
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.PinType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Always-on world rendering of channel bindings between logic blocks.
 *
 * Per binding we draw:
 *   1. A straight, camera-facing colored quad — source-center to target-center.
 *   2. Two text labels at each end (source channel name near the source,
 *      target channel name near the target), oriented along the wire.
 *
 * Multiple bindings that share an endpoint are *fanned out* radially around
 * the block center so each line is visible on its own — without it three
 * wires going out of a single block would Z-fight as one line.
 */
object WireWorldRenderer {

    private object Shards : RenderStateShard("", Runnable {}, Runnable {}) {
        val POSITION_COLOR = POSITION_COLOR_SHADER
        val NO_LIGHTMAP = RenderStateShard.NO_LIGHTMAP
        val TRANSLUCENT = TRANSLUCENT_TRANSPARENCY
        val NO_CULL_S = NO_CULL
        val LEQUAL_DEPTH = LEQUAL_DEPTH_TEST
        val COLOR_DEPTH = COLOR_DEPTH_WRITE
    }

    private val WIRE_TYPE: RenderType = RenderType.create(
        "nodewire_wire",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(Shards.POSITION_COLOR)
            .setLightmapState(Shards.NO_LIGHTMAP)
            .setTransparencyState(Shards.TRANSLUCENT)
            .setCullState(Shards.NO_CULL_S)
            .setDepthTestState(Shards.LEQUAL_DEPTH)
            .setWriteMaskState(Shards.COLOR_DEPTH)
            .createCompositeState(false),
    )

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val tracked = ClientLogicBlockTracker.all()
        if (tracked.isEmpty()) return

        // Pre-compute endpoint-counts so each binding can fan out to a unique
        // slot. Key: "source pos + source channel" → slots out, and target
        // pos + target channel → slots in. We just count for now and assign
        // an index per binding in render order so it stays stable frame-to-
        // frame (bindings list is ordered).
        val outCount = HashMap<Long, Int>()
        val inCount = HashMap<Long, Int>()
        val bindList = mutableListOf<RenderBinding>()
        for (source in tracked) {
            val bindings = source.bindingsSnapshot()
            if (bindings.isEmpty()) continue
            for (b in bindings) {
                val srcKey = source.blockPos.asLong()
                val dstKey = b.targetPos.asLong()
                val srcIdx = outCount[srcKey] ?: 0
                val dstIdx = inCount[dstKey] ?: 0
                outCount[srcKey] = srcIdx + 1
                inCount[dstKey] = dstIdx + 1
                bindList.add(RenderBinding(source, b, srcIdx, dstIdx))
            }
        }
        // Side bindings (drive-by-wire to non-logic targets) — collected
        // separately because they're rendered as wire + face frame, not as
        // a paired endpoint with a target channel name.
        val sideList = mutableListOf<RenderSideBinding>()
        for (source in tracked) {
            val sbs = source.sideBindingsSnapshot()
            if (sbs.isEmpty()) continue
            for (sb in sbs) {
                val srcKey = source.blockPos.asLong()
                val srcIdx = outCount[srcKey] ?: 0
                outCount[srcKey] = srcIdx + 1
                sideList.add(RenderSideBinding(source, sb, srcIdx))
            }
        }

        if (bindList.isEmpty() && sideList.isEmpty()) return

        // Totals per endpoint so we can normalize index→angle.
        val outTotal = HashMap<Long, Int>()
        val inTotal = HashMap<Long, Int>()
        for (rb in bindList) {
            outTotal.merge(rb.source.blockPos.asLong(), 1, Int::plus)
            inTotal.merge(rb.binding.targetPos.asLong(), 1, Int::plus)
        }
        for (sb in sideList) {
            outTotal.merge(sb.source.blockPos.asLong(), 1, Int::plus)
        }

        val cameraPos = event.camera.position
        val pose = event.poseStack
        val bufferSource = mc.renderBuffers().bufferSource()
        val builder = bufferSource.getBuffer(WIRE_TYPE)

        pose.pushPose()
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        val matrix = pose.last().pose()

        // First pass: quads.
        val endpoints = ArrayList<EndpointInfo>(bindList.size)
        val sideEndpoints = ArrayList<SideEndpointInfo>(sideList.size)
        for (rb in bindList) {
            val srcKey = rb.source.blockPos.asLong()
            val dstKey = rb.binding.targetPos.asLong()
            val src = fanOffset(rb.source.blockPos.center, rb.srcIdx, outTotal[srcKey]!!)
            val dst = fanOffset(rb.binding.targetPos.center, rb.dstIdx, inTotal[dstKey]!!)
            val color = colorForBinding(rb.source, rb.binding.sourceChannelName)
            drawStraightWire(builder, matrix, src, dst, cameraPos, color)
            endpoints.add(EndpointInfo(rb, src, dst, color))
        }
        for (sb in sideList) {
            val srcKey = sb.source.blockPos.asLong()
            val src = fanOffset(sb.source.blockPos.center, sb.srcIdx, outTotal[srcKey]!!)
            // Target end of the wire = the centre of the bound face on
            // the target block. That's targetPos.center pushed half a
            // block in the targetSide direction.
            val tCenter = sb.binding.targetPos.center
            val n = sb.binding.targetSide.normal
            val dst = Vec3(
                tCenter.x + n.x * 0.5,
                tCenter.y + n.y * 0.5,
                tCenter.z + n.z * 0.5,
            )
            val color = colorForBinding(sb.source, sb.binding.sourceChannelName)
            drawStraightWire(builder, matrix, src, dst, cameraPos, color)
            drawFaceFrame(builder, matrix, sb.binding.targetPos, sb.binding.targetSide, color)
            sideEndpoints.add(SideEndpointInfo(sb, src, dst, color))
        }
        pose.popPose()
        bufferSource.endBatch(WIRE_TYPE)

        // Second pass: text labels.
        renderLabels(event, bufferSource, cameraPos, endpoints, sideEndpoints)
    }

    /**
     * Draws four thin filled quads on the perimeter of the bound face —
     * a 1‑pixel‑style frame highlighting that this side of the target is
     * being driven. Y/X/Z normal axes each get a matching local frame.
     */
    private fun drawFaceFrame(
        builder: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: Matrix4f,
        pos: net.minecraft.core.BlockPos,
        face: net.minecraft.core.Direction,
        color: Int,
    ) {
        // Outset the frame slightly past the block face so we don't z-fight
        // with the block texture.
        val outset = 0.005
        val thick = FRAME_THICKNESS
        val cx = pos.x + 0.5
        val cy = pos.y + 0.5
        val cz = pos.z + 0.5
        val n = face.normal
        // Centre of the face plane.
        val fx = cx + n.x * (0.5 + outset)
        val fy = cy + n.y * (0.5 + outset)
        val fz = cz + n.z * (0.5 + outset)
        // Tangent basis on the face plane: pick u/v as two world axes that
        // aren't the face normal.
        val basis = faceBasis(face)
        val ux = basis.ux; val uy = basis.uy; val uz = basis.uz
        val vx = basis.vx; val vy = basis.vy; val vz = basis.vz
        // Half-extents along u and v.
        val half = 0.5
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF

        // Four edges of the square. Each edge is a thin rectangle: along
        // the edge direction, narrow across.
        // Edge centres (in u,v plane coordinates):
        //   top: ( 0, +half), bot: ( 0, -half), L: (-half, 0), R: (+half, 0)
        // Edge: top — runs along u, narrow along v.
        emitEdge(builder, matrix,
            fx, fy, fz,
            ux, uy, uz, vx, vy, vz,
            0.0, +half, half, thick * 0.5, r, g, b, a)
        emitEdge(builder, matrix,
            fx, fy, fz,
            ux, uy, uz, vx, vy, vz,
            0.0, -half, half, thick * 0.5, r, g, b, a)
        // Edge: left — runs along v, narrow along u.
        emitEdge(builder, matrix,
            fx, fy, fz,
            vx, vy, vz, ux, uy, uz,
            0.0, -half, half, thick * 0.5, r, g, b, a)
        emitEdge(builder, matrix,
            fx, fy, fz,
            vx, vy, vz, ux, uy, uz,
            0.0, +half, half, thick * 0.5, r, g, b, a)
    }

    /**
     * Emits one rectangle on the face plane centred at (centre_u, centre_v)
     * relative to the face centre, length [length] along (ux,uy,uz),
     * half-width [halfWidth] along the perpendicular (vx,vy,vz).
     */
    private fun emitEdge(
        builder: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: Matrix4f,
        cx: Double, cy: Double, cz: Double,
        ux: Double, uy: Double, uz: Double,
        vx: Double, vy: Double, vz: Double,
        offsetU: Double, offsetV: Double,
        length: Double, halfWidth: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val ex = cx + ux * offsetU + vx * offsetV
        val ey = cy + uy * offsetU + vy * offsetV
        val ez = cz + uz * offsetU + vz * offsetV
        // Quad vertices: (-length,-halfWidth), (+length,-halfWidth), (+length,+halfWidth), (-length,+halfWidth)
        val l = length
        val w = halfWidth
        emit(builder, matrix, ex - ux * l - vx * w, ey - uy * l - vy * w, ez - uz * l - vz * w, r, g, b, a)
        emit(builder, matrix, ex + ux * l - vx * w, ey + uy * l - vy * w, ez + uz * l - vz * w, r, g, b, a)
        emit(builder, matrix, ex + ux * l + vx * w, ey + uy * l + vy * w, ez + uz * l + vz * w, r, g, b, a)
        emit(builder, matrix, ex - ux * l + vx * w, ey - uy * l + vy * w, ez - uz * l + vz * w, r, g, b, a)
    }

    /**
     * Pick two unit vectors u, v on the face plane such that u × v = face
     * normal. Stored as a flat tuple for easy destructuring.
     */
    private fun faceBasis(face: net.minecraft.core.Direction): FaceBasis = when (face) {
        net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN ->
            FaceBasis(1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH ->
            FaceBasis(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST ->
            FaceBasis(0.0, 0.0, 1.0, 0.0, 1.0, 0.0)
    }

    private data class FaceBasis(
        val ux: Double, val uy: Double, val uz: Double,
        val vx: Double, val vy: Double, val vz: Double,
    )

    private fun renderLabels(
        event: RenderLevelStageEvent,
        bufferSource: MultiBufferSource.BufferSource,
        cameraPos: Vec3,
        endpoints: List<EndpointInfo>,
        sideEndpoints: List<SideEndpointInfo>,
    ) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val pose = event.poseStack
        for (ep in endpoints) {
            drawLabel(
                pose, bufferSource, font, cameraPos,
                worldPos = ep.src,
                otherEnd = ep.dst,
                text = ep.rb.binding.sourceChannelName,
                color = ep.color,
            )
            drawLabel(
                pose, bufferSource, font, cameraPos,
                worldPos = ep.dst,
                otherEnd = ep.src,
                text = ep.rb.binding.targetChannelName,
                color = ep.color,
            )
        }
        for (sep in sideEndpoints) {
            drawLabel(
                pose, bufferSource, font, cameraPos,
                worldPos = sep.src,
                otherEnd = sep.dst,
                text = sep.sb.binding.sourceChannelName,
                color = sep.color,
            )
            drawLabel(
                pose, bufferSource, font, cameraPos,
                worldPos = sep.dst,
                otherEnd = sep.src,
                text = sep.sb.binding.targetSide.name.lowercase(),
                color = sep.color,
            )
        }
        bufferSource.endBatch()
    }

    /**
     * Draws [text] near [worldPos], oriented along the line toward
     * [otherEnd]. Text scales with distance so it's still readable from
     * a few blocks out without ballooning when far away.
     */
    private fun drawLabel(
        pose: PoseStack,
        bufferSource: MultiBufferSource.BufferSource,
        font: net.minecraft.client.gui.Font,
        cameraPos: Vec3,
        worldPos: Vec3,
        otherEnd: Vec3,
        text: String,
        color: Int,
    ) {
        if (text.isEmpty()) return
        val dx = otherEnd.x - worldPos.x
        val dy = otherEnd.y - worldPos.y
        val dz = otherEnd.z - worldPos.z
        // Yaw: along the line projected onto XZ. Pitch: separately computed
        // from camera position (NOT from line) so the text always tilts to
        // face the player vertically, while staying aligned with the wire
        // horizontally — no roll around X from the line's slope.
        val yawRad = atan2(-dx, dz).toFloat()

        // Quarter-block offset along the line from this end. The label sits
        // close to its endpoint so source-name and target-name don't run
        // together visually.
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4) return
        val ux = dx / len; val uy = dy / len; val uz = dz / len
        val along = 0.25
        val px = worldPos.x + ux * along
        val py = worldPos.y + uy * along + 0.05
        val pz = worldPos.z + uz * along

        // Pitch toward camera: tilt around the (already-yawed) local X axis
        // so the text's normal points at the camera in the vertical plane.
        val camDx = cameraPos.x - px
        val camDy = cameraPos.y - py
        val camDz = cameraPos.z - pz
        val camPlanar = sqrt(camDx * camDx + camDz * camDz)
        val pitchRad = (-atan2(camDy, camPlanar)).toFloat()

        // Scale text with distance for constant pixel size. Same formula
        // as vanilla entity nameplates (`0.025f * dist`) but a hair smaller
        // since we often render multiple labels close together. Hard cap
        // at far distance so text doesn't explode.
        val distance = sqrt(
            (px - cameraPos.x) * (px - cameraPos.x)
                + (py - cameraPos.y) * (py - cameraPos.y)
                + (pz - cameraPos.z) * (pz - cameraPos.z),
        )
        val scale = (distance * 0.020).coerceIn(0.04, 0.5).toFloat()

        pose.pushPose()
        pose.translate(px - cameraPos.x, py - cameraPos.y, pz - cameraPos.z)
        pose.mulPose(com.mojang.math.Axis.YP.rotation(yawRad))
        pose.mulPose(com.mojang.math.Axis.XP.rotation(pitchRad))
        // Negative Y flips the font's screen-down convention to up. Negative
        // X mirrors so glyphs face the camera after the YP rotation.
        pose.scale(-scale, -scale, scale)
        val matrix = pose.last().pose()

        val width = font.width(text)
        val tx = -width / 2f
        val ty = -font.lineHeight / 2f
        // Solid-ish dark plate (75% alpha) for high contrast against any
        // terrain texture. Font.drawInBatch's `backgroundColor` overload
        // emits the plate as a single quad in the text RenderType, so we
        // don't need a separate POSITION_COLOR batch.
        val bgColor = 0xC0_00_00_00.toInt()
        // White text (rather than channel colour) reads cleanly on the
        // dark plate; the channel colour is already on the wire. Drop
        // shadow on for that extra crispness MC text relies on.
        font.drawInBatch(
            Component.literal(text),
            tx, ty,
            0xFFFFFFFF.toInt(),
            true,
            matrix,
            bufferSource,
            net.minecraft.client.gui.Font.DisplayMode.NORMAL,
            bgColor,
            0xF0_00_F0,
        )
        pose.popPose()
    }

    /**
     * If [total] is 1, returns [center] unchanged. Otherwise spreads N
     * endpoints around a small horizontal circle so each wire's endpoint
     * is visually distinct.
     */
    private fun fanOffset(center: Vec3, index: Int, total: Int): Vec3 {
        if (total <= 1) return center
        val radius = 0.22
        val angle = (Math.PI * 2.0 * index) / total
        return Vec3(
            center.x + Math.cos(angle) * radius,
            center.y,
            center.z + Math.sin(angle) * radius,
        )
    }

    private fun drawStraightWire(
        builder: VertexConsumer,
        matrix: Matrix4f,
        src: Vec3,
        dst: Vec3,
        cameraPos: Vec3,
        color: Int,
    ) {
        val dx = dst.x - src.x
        val dy = dst.y - src.y
        val dz = dst.z - src.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4) return

        val midX = (src.x + dst.x) * 0.5
        val midY = (src.y + dst.y) * 0.5
        val midZ = (src.z + dst.z) * 0.5
        var vx = cameraPos.x - midX
        var vy = cameraPos.y - midY
        var vz = cameraPos.z - midZ
        val vlen = sqrt(vx * vx + vy * vy + vz * vz)
        if (vlen < 1e-4) return
        vx /= vlen; vy /= vlen; vz /= vlen

        val sdx = dx / len; val sdy = dy / len; val sdz = dz / len
        var px = sdy * vz - sdz * vy
        var py = sdz * vx - sdx * vz
        var pz = sdx * vy - sdy * vx
        val plen = sqrt(px * px + py * py + pz * pz)
        if (plen < 1e-4) return
        val half = WIRE_THICKNESS * 0.5
        px = px / plen * half; py = py / plen * half; pz = pz / plen * half

        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF

        emit(builder, matrix, src.x - px, src.y - py, src.z - pz, r, g, b, a)
        emit(builder, matrix, src.x + px, src.y + py, src.z + pz, r, g, b, a)
        emit(builder, matrix, dst.x + px, dst.y + py, dst.z + pz, r, g, b, a)
        emit(builder, matrix, dst.x - px, dst.y - py, dst.z - pz, r, g, b, a)
    }

    private fun emit(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x: Double, y: Double, z: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        builder.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
            .color(r, g, b, a)
            .endVertex()
    }

    private fun colorForBinding(source: LogicBlockEntity, channelName: String): Int {
        val node = source.graph.nodes.values.firstOrNull { node ->
            node.typeKey.path == "channel_output"
                && node.config.getString("name") == channelName
        }
        val type = node?.config?.getString("type")
            ?.let { PinType.fromName(it) }
            ?: return 0xFFFFFFFF.toInt()
        return colorForType(type)
    }

    private fun colorForType(type: PinType): Int = when (type) {
        PinType.BOOL -> 0xFF_E8_5C_5C.toInt()
        PinType.INT -> 0xFF_5C_C8_E8.toInt()
        PinType.FLOAT -> 0xFF_E8_C8_5C.toInt()
        PinType.REDSTONE -> 0xFF_B8_30_30.toInt()
        PinType.STRING -> 0xFF_F0_8A_4A.toInt()
        PinType.VEC2 -> 0xFF_7C_E8_5C.toInt()
        PinType.VEC3 -> 0xFF_AC_E8_5C.toInt()
        PinType.QUAT -> 0xFF_C8_7C_E8.toInt()
    }

    private const val WIRE_THICKNESS = 0.08
    private const val FRAME_THICKNESS = 0.04

    private data class RenderSideBinding(
        val source: LogicBlockEntity,
        val binding: dev.nitka.nodewire.block.SideBinding,
        val srcIdx: Int,
    )

    private data class SideEndpointInfo(
        val sb: RenderSideBinding,
        val src: Vec3,
        val dst: Vec3,
        val color: Int,
    )

    private data class RenderBinding(
        val source: LogicBlockEntity,
        val binding: ChannelBinding,
        val srcIdx: Int,
        val dstIdx: Int,
    )

    private data class EndpointInfo(
        val rb: RenderBinding,
        val src: Vec3,
        val dst: Vec3,
        val color: Int,
    )
}
