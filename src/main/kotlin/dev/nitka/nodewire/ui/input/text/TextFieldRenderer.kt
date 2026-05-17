package dev.nitka.nodewire.ui.input.text

import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.Renderer
import net.minecraft.client.gui.Font

/**
 * Draws text, selection highlight (under text), and a blinking caret bar
 * for a [TextFieldStateHolder]. Replaces the original "static Text + 1×7
 * caret Box" layout — that approach can't render selection or position
 * the caret at sub-character offsets.
 *
 * The renderer is the visual; the holder is the model. No state lives
 * inside the renderer.
 */
class TextFieldRenderer(
    private val holder: TextFieldStateHolder,
    @Suppress("UnusedPrivateProperty") private val font: Font,
    private val textColor: Color,
    private val placeholderColor: Color,
    private val selectionColor: Color,
    private val caretColor: Color,
    private val placeholder: String,
    private val isFocused: () -> Boolean,
    private val blinkOn: () -> Boolean,
) : Renderer {

    override fun NwCanvas.render(node: UiNode) {
        val state = holder.state
        val w = node.layoutWidth
        val h = node.layoutHeight
        val padL = holder.paddingLeftPx
        val textY = (h - holder.fontHeightPx) / 2

        pushClip(0, 0, w, h)
        try {
            // Empty + unfocused → placeholder
            if (state.text.isEmpty() && !isFocused() && placeholder.isNotEmpty()) {
                drawText(placeholder, padL, textY, placeholderColor)
                return
            }

            // Selection rect under text
            holder.selectionPixelRange()?.let { range ->
                val x = padL + range.first - holder.scrollXPx
                val width = (range.last - range.first).coerceAtLeast(1)
                fillRect(x, textY, width, holder.fontHeightPx, selectionColor)
            }

            // Text
            drawText(state.text, padL - holder.scrollXPx, textY, textColor)

            // Caret
            if (isFocused() && (blinkOn() || state.hasSelection)) {
                val cx = padL + holder.caretPixelX() - holder.scrollXPx
                fillRect(cx, textY, 1, holder.fontHeightPx, caretColor)
            }
        } finally {
            popClip()
        }
    }
}
