package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.LayoutCoordinates
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.overlay.Popup
import dev.nitka.nodewire.ui.overlay.PopupPlacement
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.delay

/**
 * Hover-delayed tooltip. Wraps [content] in a Box that tracks hover state
 * and its own screen position, then publishes a [Popup] beneath itself via
 * the overlay layer once the pointer's been still over the target for
 * [delayMs]. The popup edge-flips above the target if there's no room
 * below, and is clamped to the screen on either axis.
 *
 * Replaces the Phase-12 in-flow tooltip — the new one is no longer
 * constrained by parent bounds (parent's `overflow=hidden` won't clip it)
 * and renders above sibling content because it lives in the overlay layer.
 */
@Composable
fun Tooltip(
    text: String,
    delayMs: Long = 400L,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(hovered) {
        if (hovered) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }

    Box(
        modifier = modifier
            .onHover { hovered = it }
            .onPositioned { anchor = it },
    ) {
        content()
    }

    val a = anchor
    if (visible && a != null) {
        Popup(
            position = PopupPosition.Anchored(a, PopupPlacement.Below, gap = NwTheme.dimens.space4),
        ) {
            TooltipPanel(text)
        }
    }
}

@Composable
private fun TooltipPanel(text: String) {
    Surface(
        style = SurfaceStyle(
            color = NwTheme.colors.surface,
            shape = NwTheme.shapes.small,
            border = BorderStroke(NwTheme.dimens.borderThin, NwTheme.colors.border),
            padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
        ),
    ) {
        Text(
            text,
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurface),
        )
    }
}
