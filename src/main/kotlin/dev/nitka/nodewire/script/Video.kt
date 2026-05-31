package dev.nitka.nodewire.script

/**
 * Script-facing video handle. A wrapper over a [java.util.UUID] so the script
 * type system stays free of `graph.PinValue` / Mojang refs (sandbox-safe — UUID
 * resolves VIA_PLATFORM). Frames are client-local, keyed by [handle]; Phase 2
 * plumbs only the handle (the drawing API is a later Video subsystem).
 */
data class Video(val handle: java.util.UUID) {
    companion object {
        /** "No video bound" — the nil UUID; renders no frames. */
        val NONE = Video(java.util.UUID(0L, 0L))
    }
}
