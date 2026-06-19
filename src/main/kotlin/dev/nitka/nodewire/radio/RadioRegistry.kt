package dev.nitka.nodewire.radio

import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Server-side directory of live radio transmitters — the wireless hop behind the
 * radio channels. Transmitters [publish] their state each server tick; receivers
 * [best]-resolve the strongest in-range transmitter on their frequency.
 *
 * One entry per transmitter (keyed by [BlockPos] within a dimension); the
 * frequency is a field, so a TX that retunes simply overwrites its own entry —
 * no stale per-frequency ghosts. Entries older than [STALE_TICKS] (TX unloaded /
 * crashed) are pruned lazily on lookup. Cleared wholesale on server stop.
 *
 * All access is on the server thread (BE tickers, readPin, lifecycle events);
 * `@Synchronized` is belt-and-braces.
 */
object RadioRegistry {

    /** A transmitter's broadcast snapshot. [slots] + [video] are the payload. */
    class TxEntry(
        val pos: BlockPos,
        val center: Vec3,
        val freqKey: Int,
        val range: Double,
        val gain: Double,
        val crossWorld: Boolean,
        val slots: Array<PinValue?>,
        val video: UUID,
        var stamp: Long,
    )

    /** A re-tuning TX older than this many ticks is treated as gone. */
    private const val STALE_TICKS = 5L

    // dimension → (transmitter pos → entry)
    private val byDim = HashMap<ResourceKey<Level>, HashMap<BlockPos, TxEntry>>()

    /** 0.1-channel-resolution frequency bucket (float equality is unsafe). */
    fun freqKey(freq: Float): Int = Math.round(freq * 10f)

    @Synchronized
    fun publish(level: Level, entry: TxEntry) {
        byDim.getOrPut(level.dimension()) { HashMap() }[entry.pos] = entry
    }

    @Synchronized
    fun remove(level: Level, pos: BlockPos) {
        byDim[level.dimension()]?.remove(pos)
    }

    /**
     * Strongest transmitter on [freqKey] for this receiver.
     *
     * 1. **Local (same dimension):** in range if `dist ≤ TX.range + rxRange`
     *    (both antennas extend reach); strength = `gain / max(1, distSq)` —
     *    bigger antenna and/or closer wins. A local match always wins.
     * 2. **Cross-world fallback:** only when the RX antenna is [rxCrossWorld] and
     *    no local TX was found — reach a *cross-world* TX in ANOTHER dimension
     *    (symmetric: both ends need a cross-world antenna). Distance is
     *    meaningless across dimensions, so these rank by `gain` alone.
     *
     * Prunes stale entries it walks past.
     */
    @Synchronized
    fun best(level: Level, freqKey: Int, rxCenter: Vec3, rxRange: Double, rxCrossWorld: Boolean, now: Long): TxEntry? {
        val dim = level.dimension()

        // 1. Local pass.
        val local = byDim[dim]?.let { map ->
            var best: TxEntry? = null
            var bestStrength = -1.0
            val it = map.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.stamp > STALE_TICKS) { it.remove(); continue }
                if (e.freqKey != freqKey) continue
                val distSq = e.center.distanceToSqr(rxCenter)
                val reach = e.range + rxRange
                if (distSq > reach * reach) continue
                val strength = e.gain / Math.max(1.0, distSq)
                if (strength > bestStrength) { bestStrength = strength; best = e }
            }
            best
        }
        if (local != null) return local

        // 2. Cross-world fallback.
        if (!rxCrossWorld) return null
        var best: TxEntry? = null
        var bestGain = -1.0
        for ((d, map) in byDim) {
            if (d == dim) continue
            val it = map.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.stamp > STALE_TICKS) { it.remove(); continue }
                if (e.freqKey != freqKey) continue
                if (!e.crossWorld) continue
                if (e.gain > bestGain) { bestGain = e.gain; best = e }
            }
        }
        return best
    }

    @Synchronized
    fun clear(dim: ResourceKey<Level>) {
        byDim.remove(dim)
    }

    @Synchronized
    fun clearAll() {
        byDim.clear()
    }
}
