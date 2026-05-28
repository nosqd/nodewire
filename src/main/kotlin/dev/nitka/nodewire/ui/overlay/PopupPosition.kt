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
    /**
     * Anchor-relative placement. Edge-flip kicks in near the screen edge.
     *
     * [avoidBand] (screen-X range, optional) is the horizontal span occupied
     * by all ancestor panels in a cascade (a submenu chain). When a
     * RightOf/LeftOf popup must flip because it would run off-screen, it
     * flips to the OUTSIDE of this whole band instead of just past its
     * immediate anchor — so a deep submenu never lands on top of a
     * grandparent panel (which sits flush against the parent). When set,
     * the resolved X is also NOT clamped back into the band, preferring to
     * extend partially off-screen over overlapping an ancestor.
     */
    data class Anchored(
        val anchor: LayoutCoordinates,
        val placement: PopupPlacement,
        val gap: Int = 0,
        val avoidBand: IntRange? = null,
    ) : PopupPosition

    /** Absolute screen coordinates. No clamping — caller knows what they're doing. */
    data class AtScreen(val x: Int, val y: Int) : PopupPosition

    /** Centered horizontally and vertically over the entire screen. */
    data object Centered : PopupPosition
}

/** Direction the popup grows away from its anchor. */
enum class PopupPlacement { Below, Above, RightOf, LeftOf }
