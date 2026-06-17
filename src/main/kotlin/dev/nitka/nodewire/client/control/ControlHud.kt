package dev.nitka.nodewire.client.control

import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderGuiEvent

/** Tiny status line shown while piloting a Control Block. */
object ControlHud {

    private const val BG = 0xA0_00_00_00.toInt()
    private const val TEXT = 0xFF_E8_E8_EC.toInt()
    private const val ACCENT = 0xFF_00_FF_66.toInt()

    fun onRenderGui(event: RenderGuiEvent.Post) {
        if (!ControlSession.isActive()) return
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return

        val g = event.guiGraphics
        val font = mc.font
        val captured = ControlSession.mouseCaptured
        val text = "● Piloting  ·  RMB block to exit  ·  V: mouse ${if (captured) "ON" else "OFF"}"
        val w = font.width(text)
        val x = (mc.window.guiScaledWidth - w) / 2
        val y = mc.window.guiScaledHeight - 56

        g.fill(x - 4, y - 3, x + w + 4, y + font.lineHeight + 2, BG)
        g.drawString(font, text, x, y, if (captured) ACCENT else TEXT)
    }
}
