package dev.nitka.nodewire.mixin.camera;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.nitka.nodewire.client.video.VideoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vista-style Veil compatibility for the camera capture pass.
 *
 * Veil's {@code PipelineLevelRendererMixin#blit} wraps the level blit with its
 * own {@code FramebufferStack} push/pop, but only when NOT in its perspective
 * rendering mode (the latter check is the {@code isRenderingPerspective()} call
 * site). Our nested {@code renderLevel} for a camera capture isn't a Veil
 * perspective render, so without help Veil pushes a framebuffer state our pass
 * doesn't match, and on the way back its stack is imbalanced — flicker + crash.
 *
 * The Vista mod (MehVahdJukaar/cameramod) — same MC + NeoForge line — fixes this
 * by OR-flagging Veil's perspective check with its own live-feed flag, so Veil
 * treats the live feed exactly like its own perspective render (which it already
 * knows how to handle). We do the same with our {@link VideoManager#isCapturing()}.
 *
 * <p>{@code @Pseudo} so the mixin compiles + loads even when Veil is absent. The
 * {@code require = 0} on the injection means a future Veil rename of {@code blit}
 * or the inner call site won't fail mod loading — we'd just lose the integration.
 */
@Pseudo
@Mixin(targets = "foundry.veil.mixin.pipeline.client.PipelineLevelRendererMixin")
public abstract class MixinVeilPipelineLevelRenderer {

    @ModifyExpressionValue(
        method = "blit",
        at = @At(
            value = "INVOKE",
            target = "Lfoundry/veil/api/client/render/VeilLevelPerspectiveRenderer;isRenderingPerspective()Z"
        ),
        require = 0
    )
    private boolean nodewire$treatCameraCaptureAsPerspective(boolean original) {
        return original || VideoManager.isCapturing();
    }
}
