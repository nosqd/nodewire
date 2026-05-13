package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.onSizeChanged
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.delay

/**
 * Wraps [content] with a hover-delayed text tooltip rendered just below it.
 *
 * Behavior:
 *   * Pointer enters → [LaunchedEffect] schedules `visible = true` after
 *     [delayMs]. Leaving cancels the coroutine (LaunchedEffect's keyed
 *     restart semantics) so quick brushes don't flash a tooltip.
 *   * Visible tooltip is an absolutely-positioned [Surface] below the target
 *     — outside parent flow, so it doesn't push other layout around. It
 *     paints after the target because it's the second child of the wrapper
 *     Box (siblings paint in declaration order).
 *
 * Limitations: no smart edge-flipping (a tooltip near the bottom of the
 * screen will be clipped); single-line text only. Both lift once Phase 13+
 * adds a real popup layer.
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
    var targetHeight by remember { mutableStateOf(0) }

    LaunchedEffect(hovered) {
        if (hovered) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }

    Box(modifier = modifier.onHover { hovered = it }) {
        Box(modifier = Modifier.onSizeChanged { targetHeight = it.height }) {
            content()
        }
        if (visible) {
            Surface(
                modifier = Modifier.absolutePosition(0, targetHeight + NwTheme.dimens.space4),
                style = SurfaceStyle(
                    color = NwTheme.colors.surface,
                    shape = NwTheme.shapes.small,
                    border = BorderStroke(NwTheme.dimens.borderThin, NwTheme.colors.border),
                    padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
                ),
            ) {
                Text(text, style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurface))
            }
        }
    }
}
