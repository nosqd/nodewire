package dev.nitka.nodewire.ui.modifier.input

import dev.nitka.nodewire.ui.core.InputModifierElement
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.LayoutCoordinates

/**
 * Fires whenever the node's screen-space position or size changes between
 * frames. The callback receives a [LayoutCoordinates] snapshot — use it to
 * anchor popups (tooltip / dropdown / context menu) to a moving target.
 *
 * NwUiOwner runs this during its post-layout walk; each modifier tracks its
 * own [lastCoords] so the callback only fires on actual change.
 */
class OnPositionedModifier(
    val callback: (LayoutCoordinates) -> Unit,
) : InputModifierElement<OnPositionedModifier> {
    var lastCoords: LayoutCoordinates? = null

    override fun mergeWith(other: OnPositionedModifier) = other
}

fun Modifier.onPositioned(callback: (LayoutCoordinates) -> Unit) =
    this then OnPositionedModifier(callback)
