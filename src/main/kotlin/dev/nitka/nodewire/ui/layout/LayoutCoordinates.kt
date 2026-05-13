package dev.nitka.nodewire.ui.layout

/**
 * Snapshot of a node's position + size in screen-space (GUI pixels).
 * Delivered by [Modifier.onPositioned] each frame the node's geometry
 * changes. `(screenX, screenY)` is the top-left corner.
 */
data class LayoutCoordinates(
    val screenX: Int,
    val screenY: Int,
    val width: Int,
    val height: Int,
) {
    val screenRight: Int get() = screenX + width
    val screenBottom: Int get() = screenY + height
    val centerX: Int get() = screenX + width / 2
    val centerY: Int get() = screenY + height / 2
}
