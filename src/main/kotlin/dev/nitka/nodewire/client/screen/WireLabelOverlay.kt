package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.components.TextInput
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.width

/**
 * Inline rename popup for the wire currently in `editor.renamingEdge`.
 * Lives inside the [NodeCanvas] so it pans/zooms with the world — sits
 * at the wire's midpoint in world coordinates.
 */
@Composable
fun WireLabelOverlay() {
    val editor = LocalEditorState.current ?: return
    val edge = editor.renamingEdge ?: return
    val positions = editor.pinPositions
    val from = positions.get(PinKey(edge.from.node, edge.from.pin, PinSide.Output)) ?: return
    val to = positions.get(PinKey(edge.to.node, edge.to.pin, PinSide.Input)) ?: return
    val midX = ((from.first + to.first) * 0.5f).toInt() - 40
    val midY = ((from.second + to.second) * 0.5f).toInt() - 6
    var text by remember(edge) { mutableStateOf(edge.label ?: "") }
    Box(
        modifier = Modifier
            .absolutePosition(midX, midY)
            .width(80),
    ) {
        TextInput(
            value = text,
            placeholder = "label",
            onValueChange = { text = it },
            onSubmit = {
                editor.setEdgeLabel(edge, text)
                editor.renamingEdge = null
            },
        )
    }
}
