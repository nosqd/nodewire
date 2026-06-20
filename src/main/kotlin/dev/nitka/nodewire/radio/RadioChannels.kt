package dev.nitka.nodewire.radio

import java.util.UUID

/** Pin naming + sizing shared by the transmitter and receiver. */
object RadioChannels {
    const val NUM_CHANNELS = 16
    const val FREQ_PIN = "frequency"
    const val VIDEO_PIN = "video"
    const val RECEIVING_PIN = "receiving"
    const val SIGNAL_PIN = "signal"
    const val CH_PREFIX = "ch"

    /** The nil/empty video handle (matches ScreenBlockEntity / PinValue.default(VIDEO)). */
    val NIL_HANDLE: UUID = UUID(0L, 0L)

    fun chId(i: Int): String = "$CH_PREFIX$i"

    fun chIndex(id: String): Int? =
        if (id.startsWith(CH_PREFIX)) {
            id.substring(CH_PREFIX.length).toIntOrNull()?.takeIf { it in 0 until NUM_CHANNELS }
        } else {
            null
        }
}
