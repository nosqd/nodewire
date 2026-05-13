package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.overlay.Popup
import dev.nitka.nodewire.ui.overlay.PopupPosition
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme

/**
 * Modal dialog: centered popup with a dim scrim behind it. The scrim
 * absorbs clicks (so the page below can't be interacted with) and, when
 * [dismissOnClickOutside] is true, calls [onDismissRequest] on click —
 * mirror of how the user would click outside in a typical desktop dialog.
 *
 * Rendered in the overlay layer (no parent-bounds clipping, paints on top
 * of everything). Use [Surface] inside [content] to style the dialog body;
 * a sensible default is provided as [DialogContent] for convenience.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    dismissOnClickOutside: Boolean = true,
    content: @Composable () -> Unit,
) {
    Popup(
        position = PopupPosition.Centered,
        scrim = true,
        dismissOnClickOutside = dismissOnClickOutside,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}

/**
 * Styled dialog body — surface + border + padding + slot for content. Use
 * inside [Dialog]'s `content` for the typical "panel with a title and
 * actions" shape, or skip it and build your own.
 */
@Composable
fun DialogContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        style = SurfaceStyle(
            color = NwTheme.colors.surface,
            shape = NwTheme.shapes.large,
            border = BorderStroke(NwTheme.dimens.borderThin, NwTheme.colors.borderStrong),
            padding = PaddingValues(NwTheme.dimens.space16),
        ),
        content = content,
    )
}
