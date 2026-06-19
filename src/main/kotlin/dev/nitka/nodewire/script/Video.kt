package dev.nitka.nodewire.script

/**
 * Script-facing video handle. A wrapper over a [java.util.UUID] so the script
 * type system stays free of `graph.PinValue` / Mojang refs (sandbox-safe — UUID
 * resolves VIA_PLATFORM). Frames are client-local, keyed by [handle].
 *
 * [signal] is a transient 0..1 reception quality that rides WITH the value (1 =
 * a clean camera / wired feed; a Radio Receiver re-emits the same handle weaker),
 * so `image()` degrades a radio feed with noise exactly like a Screen does.
 * Equality is by [handle] ONLY (signal-agnostic) so the idiomatic
 * `video != Video.NONE` nil-check keeps working when a feed is noisy.
 */
class Video(val handle: java.util.UUID, val signal: Float = 1f) {

    override fun equals(other: Any?): Boolean = other is Video && other.handle == handle
    override fun hashCode(): Int = handle.hashCode()
    override fun toString(): String = "Video($handle, signal=$signal)"

    companion object {
        /** "No video bound" — the nil UUID; renders no frames. */
        val NONE = Video(java.util.UUID(0L, 0L))
    }
}
