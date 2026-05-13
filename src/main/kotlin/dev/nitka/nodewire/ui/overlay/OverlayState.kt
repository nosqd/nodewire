package dev.nitka.nodewire.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * One registered popup. [id] is a stable identity (typically an `Any()` per
 * [Popup] call site, kept across recompositions via [remember]) so updates
 * replace the existing entry instead of creating a duplicate.
 *
 * `(x, y)` is the popup's resolved top-left in screen-space. [Popup]
 * computes this from a [PopupPosition] strategy + its own measured size
 * each frame, then re-puts the entry; OverlayHost just renders at (x, y).
 *
 * [scrim] and [onDismissRequest] are honored by OverlayHost — a scrim'd
 * popup gets a full-screen dim-bg sibling underneath that consumes input.
 */
class PopupEntry(
    val id: Any,
    val x: Int,
    val y: Int,
    val scrim: Boolean,
    val dismissOnClickOutside: Boolean,
    val onDismissRequest: (() -> Unit)?,
    val content: @Composable () -> Unit,
)

/**
 * Live registry of popups for one [NwComposeScreen]. The list backing
 * [popups] is a Compose [SnapshotStateList], so [OverlayHost] re-renders
 * automatically when popups appear, change, or disappear.
 */
class OverlayState {
    val popups: SnapshotStateList<PopupEntry> = mutableStateListOf()

    fun put(entry: PopupEntry) {
        val existing = popups.indexOfFirst { it.id === entry.id }
        if (existing >= 0) popups[existing] = entry else popups.add(entry)
    }

    fun remove(id: Any) {
        popups.removeAll { it.id === id }
    }
}

/**
 * The current [OverlayState]. [OverlayHost] provides this; callers should
 * never need to construct an OverlayState themselves — use [Popup] / [Dialog].
 */
val LocalOverlay = compositionLocalOf<OverlayState> {
    error("LocalOverlay not provided — wrap your screen content in NwThemeProvider (it installs OverlayHost).")
}
