package dev.nitka.nodewire.client.screen

import net.minecraft.client.renderer.ShaderInstance

/**
 * Holds the registered `nodewire:screen_noise` core shader instance (set by the
 * [net.neoforged.neoforge.client.event.RegisterShadersEvent] handler in
 * NodewireClient). Null until registration runs, or if the shader fails to
 * compile — [ScreenBlockRenderer] then falls back to a clean (noiseless) blit.
 */
object ScreenNoiseShader {
    @JvmStatic
    var instance: ShaderInstance? = null
}
