package dev.nitka.nodewire.ui.render

import dev.nitka.nodewire.ui.core.UiNode

/**
 * Like [SurfaceRenderer], but flushes the underlying [NwCanvas] first AND
 * pushes the pose matrix forward in Z so all subsequent draws sort above
 * the main tree. Used at popup / overlay boundaries.
 *
 * Two-pronged fix:
 *   * `flush()` commits the queued text batch from the main tree —
 *     otherwise MC's deferred text rendering would draw on top of the
 *     popup background even though we drew the bg later.
 *   * `pose().translate(_, _, Z)` raises the popup's depth so MC's
 *     depth test gives priority to it. Z = 200 mirrors vanilla MC's
 *     own tooltip layer offset; gives a wide margin over main content.
 */
object FlushingSurfaceRenderer : Renderer {
    /** Same offset vanilla MC uses for its tooltip layer — comfortably above HUD/GUI. */
    private const val POPUP_Z = 200f

    override fun NwCanvas.render(node: UiNode) {
        flush()
        gfx.pose().pushPose()
        gfx.pose().translate(0f, 0f, POPUP_Z)
        with(SurfaceRenderer) { render(node) }
    }

    override fun NwCanvas.renderAfterChildren(node: UiNode) {
        with(SurfaceRenderer) { renderAfterChildren(node) }
        gfx.pose().popPose()
    }
}
