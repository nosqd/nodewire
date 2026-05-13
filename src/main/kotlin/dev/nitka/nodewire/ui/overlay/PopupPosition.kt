package dev.nitka.nodewire.ui.overlay

import dev.nitka.nodewire.ui.layout.LayoutCoordinates

/**
 * Strategy for resolving a popup's screen position. [Popup] takes a
 * [PopupPosition] and computes its final `(x, y)` once per frame from
 * the current screen size + the popup's own measured size + (for
 * anchored variants) the anchor's [LayoutCoordinates].
 *
 * Edge-flipping / clamping happens in the popup implementation, not here —
 * this type just declares *intent*.
 */
sealed interface PopupPosition {
    /** Anchor-relative placement. Edge-flip kicks in near the screen edge. */
    data class Anchored(
        val anchor: LayoutCoordinates,
        val placement: PopupPlacement,
        val gap: Int = 0,
    ) : PopupPosition

    /** Absolute screen coordinates. No clamping — caller knows what they're doing. */
    data class AtScreen(val x: Int, val y: Int) : PopupPosition

    /** Centered horizontally and vertically over the entire screen. */
    data object Centered : PopupPosition
}

/** Direction the popup grows away from its anchor. */
enum class PopupPlacement { Below, Above, RightOf, LeftOf }
