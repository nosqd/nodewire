package dev.nitka.nodewire.client.video

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.item.ArGlassesItem
import dev.nitka.nodewire.radio.RadioChannels
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import org.slf4j.Logger
import com.mojang.logging.LogUtils

import java.util.UUID

object ArClientState {
    private val LOG: Logger = LogUtils.getLogger()

    @Volatile
    var activeHandle: UUID = RadioChannels.NIL_HANDLE

    private var wasWearingLastTick = false

    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val stack = player.getItemBySlot(EquipmentSlot.HEAD)
        val wearing = stack.item is ArGlassesItem

        if (wearing && activeHandle != RadioChannels.NIL_HANDLE) {
            val w = mc.window.guiScaledWidth
            val h = mc.window.guiScaledHeight
            VideoManager.requestSurfaceSize(activeHandle, w, h)
        }

        if (!wearing) {
            if (wasWearingLastTick) {
                activeHandle = RadioChannels.NIL_HANDLE
            }
        }
        wasWearingLastTick = wearing
    }

    fun onRenderGuiPre(event: RenderGuiEvent.Pre) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val stack = player.getItemBySlot(EquipmentSlot.HEAD)
        val wearing = stack.item is ArGlassesItem
        if (!wearing) return

        LOG.info("AR: rendering pre, handle={}", activeHandle)

        RenderSystem.disableDepthTest()
        try {
            if (activeHandle == RadioChannels.NIL_HANDLE) {
                val txt = Component.literal("NO SIGNAL").withStyle(ChatFormatting.RED)
                val w = mc.window.guiScaledWidth
                event.guiGraphics.drawString(
                    mc.font, txt,
                    w / 2 - mc.font.width(txt) / 2,
                    10,
                    0xFFFF0000.toInt(),
                    false,
                )
                return
            }

            val surface = VideoManager.getOrCreate(activeHandle) as? GlVideoSurface ?: return
            val texId = surface.colorTextureId()
            val w = mc.window.guiScaledWidth
            val h = mc.window.guiScaledHeight

            LOG.info("AR: blit texId={}, w={}, h={}", texId, w, h)

            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableCull()
            RenderSystem.setShader { GameRenderer.getPositionTexShader() }
            RenderSystem.setShaderTexture(0, texId)
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
            // Full-screen quad. FBO textures are bottom-up → V flipped.
            buf.addVertex(0f, 0f, 0f).setUv(0f, 1f)
            buf.addVertex(0f, h.toFloat(), 0f).setUv(0f, 0f)
            buf.addVertex(w.toFloat(), h.toFloat(), 0f).setUv(1f, 0f)
            buf.addVertex(w.toFloat(), 0f, 0f).setUv(1f, 1f)
            BufferUploader.drawWithShader(buf.buildOrThrow())
        } finally {
            RenderSystem.enableDepthTest()
        }
    }
}
