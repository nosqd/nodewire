package dev.nitka.nodewire.integration.veil

import com.mojang.logging.LogUtils
import net.neoforged.fml.ModList
import java.lang.reflect.Field

/**
 * Veil-style compatibility for the camera capture pass.
 *
 * Veil's `PipelineLevelRendererMixin#blit` wraps the level blit with its own
 * `FramebufferStack` push/pop, but only when NOT in its perspective rendering
 * mode (the gate is `VeilLevelPerspectiveRenderer.isRenderingPerspective()`).
 * Our nested `renderLevel` inside the capture isn't a Veil perspective render,
 * so without help Veil pushes its framebuffer state our pass doesn't match, and
 * on return the stack imbalances — flicker / crash.
 *
 * Vista's MIXIN approach (mix into Veil's own mixin handler via MixinSquared)
 * needs an extra library dep. We instead pre-flip Veil's static
 * `renderingPerspective` flag to `true` for the duration of the capture pass
 * via reflection, so Veil treats it as its own perspective render — which its
 * stack already handles. Vista has a commented-out reflective sibling for the
 * same field, so the approach is sanctioned by their codebase; they only
 * dropped it because they wanted Veil's lights to *still* run during their
 * feed. We don't care about that for a camera blit.
 *
 * Reflection so we don't need a new compileOnly dep and so the mod loads
 * cleanly when Veil is absent. If the field is renamed in a future Veil
 * release, we log once and fall back to running the capture unguarded (better
 * than crashing).
 */
object VeilCaptureGuard {

    private val LOG = LogUtils.getLogger()

    @Volatile private var resolved = false
    @Volatile private var available = false
    @Volatile private var renderingPerspectiveField: Field? = null

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        if (!ModList.get().isLoaded("veil")) {
            available = false; return
        }
        try {
            val cls = Class.forName("foundry.veil.api.client.render.VeilLevelPerspectiveRenderer")
            val f = cls.getDeclaredField("renderingPerspective")
            f.isAccessible = true
            renderingPerspectiveField = f
            available = true
            LOG.info("[NW-CAMERA] Veil detected — its FramebufferStack will be bypassed during camera captures (Vista technique)")
        } catch (t: Throwable) {
            available = false
            LOG.warn("[NW-CAMERA] Veil present but its renderingPerspective field did not resolve; cameras may flicker or crash under Veil: {}", t.toString())
        }
    }

    /** Force Veil's `renderingPerspective` to true for the duration of [block],
     *  then restore the prior value. No-op when Veil is absent or unresolved. */
    fun aroundCapture(block: () -> Unit) {
        resolveOnce()
        if (!available) { block(); return }
        val f = renderingPerspectiveField!!
        val prev: Boolean = try {
            f.getBoolean(null)
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] Veil getBoolean failed; running capture unguarded: {}", t.toString())
            block(); return
        }
        try {
            f.setBoolean(null, true)
        } catch (t: Throwable) {
            LOG.warn("[NW-CAMERA] Veil setBoolean(true) failed; running capture unguarded: {}", t.toString())
            block(); return
        }
        try {
            block()
        } finally {
            try {
                f.setBoolean(null, prev)
            } catch (t: Throwable) {
                LOG.warn("[NW-CAMERA] Veil setBoolean restore failed; Veil's perspective flag may now be stuck: {}", t.toString())
            }
        }
    }
}
