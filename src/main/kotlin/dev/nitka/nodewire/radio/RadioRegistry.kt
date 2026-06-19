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
        /** World-space unit beam direction; null = omnidirectional. */
        val aim: Vec3?,
        /** Lobe exponent; 0 = omni. See [lobe]. */
        val focus: Double,
        val slots: Array<PinValue?>,
        val video: UUID,
        var stamp: Long,
    )

    /** A resolved reception: the winning transmitter + a 0..1 signal strength
     *  (1 at the transmitter, → 0 at the edge of reach; fixed for cross-world). */
    class Match(val tx: TxEntry, val signal: Float)

    /** A re-tuning TX older than this many ticks is treated as gone. */
    private const val STALE_TICKS = 5L

    /** Cross-dimension links have no meaningful distance — they're inherently
     *  long-haul, so the received signal is fixed at a degraded level. */
    private const val CROSS_WORLD_SIGNAL = 0.5f

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
     * Strongest transmitter on [freqKey] for the receiver at [rxCenter] with
     * antenna profile [rx].
     *
     * 1. **Local (same dimension):** [localStrength] — in range if
     *    `dist ≤ TX.range + rx.range`, scaled by both antennas' directional
     *    lobes; a local match always wins.
     * 2. **Cross-world fallback:** only when [rx] is `crossWorld` and no local TX
     *    was found — reach a *cross-world* TX in ANOTHER dimension (symmetric:
     *    both ends need a cross-world antenna). Distance/direction are
     *    meaningless across dimensions, so these rank by `gain` alone.
     *
     * Prunes stale entries it walks past.
     */
    @Synchronized
    fun best(level: Level, freqKey: Int, rxCenter: Vec3, rx: AntennaProfile, now: Long): Match? {
        val dim = level.dimension()

        // 1. Local pass.
        var localBest: TxEntry? = null
        var bestStrength = -1.0
        var bestDistSq = 0.0
        byDim[dim]?.let { map ->
            val it = map.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.stamp > STALE_TICKS) { it.remove(); continue }
                if (e.freqKey != freqKey) continue
                val strength = localStrength(e, rxCenter, rx)
                if (strength > bestStrength) {
                    bestStrength = strength
                    localBest = e
                    bestDistSq = e.center.distanceToSqr(rxCenter)
                }
            }
        }
        localBest?.let { e ->
            // Signal = 1 - (dist/reach)²  — quadratic so it stays near 1 over a
            // generous clean zone close to the transmitter and only falls off
            // sharply near the edge of combined reach (1 at the TX, 0 at the edge).
            val reach = e.range + rx.range
            val d = Math.sqrt(bestDistSq) / reach
            val sig = (1.0 - d * d).coerceIn(0.0, 1.0).toFloat()
            return Match(e, sig)
        }

        // 2. Cross-world fallback.
        if (!rx.crossWorld) return null
        var cwBest: TxEntry? = null
        var bestGain = -1.0
        for ((d, map) in byDim) {
            if (d == dim) continue
            val it = map.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.stamp > STALE_TICKS) { it.remove(); continue }
                if (e.freqKey != freqKey) continue
                if (!e.crossWorld) continue
                if (e.gain > bestGain) { bestGain = e.gain; cwBest = e }
            }
        }
        return cwBest?.let { Match(it, CROSS_WORLD_SIGNAL) }
    }

    /**
     * Local-pass signal strength of [e] at [rxCenter], or `< 0` if out of range
     * or outside either antenna's lobe. `(txGain·gT)·(rxGain·gR) / max(1,distSq)`
     * where gT/gR are the transmit/receive [lobe] factors. With both antennas
     * omnidirectional (`aim == null`) gT = gR = 1, so this reduces to the
     * `gain / dist²` "strongest wins" of the omni case (the rx.gain factor is a
     * per-query constant and doesn't change which TX wins).
     */
    internal fun localStrength(e: TxEntry, rxCenter: Vec3, rx: AntennaProfile): Double {
        val distSq = e.center.distanceToSqr(rxCenter)
        val reach = e.range + rx.range
        if (distSq > reach * reach) return -1.0
        val toRx = rxCenter.subtract(e.center)            // TX → RX
        val gT = lobe(e.aim, toRx, e.focus)               // is RX in the TX beam?
        if (gT <= 0.0) return -1.0
        val gR = lobe(rx.aim, toRx.scale(-1.0), rx.focus) // is TX in the RX "ear"?
        if (gR <= 0.0) return -1.0
        return (e.gain * gT) * (rx.gain * gR) / Math.max(1.0, distSq)
    }

    /**
     * Cosine-power lobe `max(0, cosθ)^focus`, where θ is the angle between the
     * antenna's [aim] and [towardOther] (the direction to the other endpoint).
     * Omnidirectional (1.0) when [aim] is null or [focus] ≤ 0; 0 when the other
     * endpoint is behind the antenna (cosθ ≤ 0).
     */
    internal fun lobe(aim: Vec3?, towardOther: Vec3, focus: Double): Double {
        if (aim == null || focus <= 0.0) return 1.0
        val len = towardOther.length()
        if (len < 1.0e-9) return 1.0
        // aim is unit; normalize towardOther. Clamp for float error so on-axis
        // never exceeds cos=1 (pow(1.0001, k) would peak above 1.0).
        val cos = (aim.dot(towardOther) / len).coerceIn(-1.0, 1.0)
        if (cos <= 0.0) return 0.0
        return Math.pow(cos, focus)
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
