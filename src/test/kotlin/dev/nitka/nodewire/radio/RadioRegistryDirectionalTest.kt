package dev.nitka.nodewire.radio

import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Directional radio math ([RadioRegistry.lobe] + [RadioRegistry.localStrength]).
 * Pure — no [net.minecraft.world.level.Level] needed.
 */
class RadioRegistryDirectionalTest {

    private fun tx(center: Vec3, gain: Double = 1.0, range: Double = 512.0, aim: Vec3? = null, focus: Double = 0.0) =
        RadioRegistry.TxEntry(
            pos = BlockPos.ZERO, center = center, freqKey = 0, range = range, gain = gain,
            crossWorld = false, aim = aim, focus = focus,
            slots = arrayOfNulls<PinValue>(16), video = UUID(0L, 0L), stamp = 0L,
        )

    private fun omni(range: Double = 512.0, gain: Double = 1.0) = AntennaProfile(range, gain)

    // ── lobe ──
    @Test
    fun `lobe is omni when aim null or focus zero`() {
        assertEquals(1.0, RadioRegistry.lobe(null, Vec3(1.0, 0.0, 0.0), 4.0), 1e-9)
        assertEquals(1.0, RadioRegistry.lobe(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), 0.0), 1e-9)
    }

    @Test
    fun `lobe peaks on axis and zeroes behind`() {
        val aim = Vec3(1.0, 0.0, 0.0)
        assertEquals(1.0, RadioRegistry.lobe(aim, Vec3(5.0, 0.0, 0.0), 2.0), 1e-9)
        assertEquals(0.0, RadioRegistry.lobe(aim, Vec3(-5.0, 0.0, 0.0), 2.0), 1e-9)
    }

    @Test
    fun `higher focus tightens the off-axis falloff`() {
        val aim = Vec3(1.0, 0.0, 0.0)
        val off45 = Vec3(1.0, 1.0, 0.0) // cosθ ≈ 0.707
        val soft = RadioRegistry.lobe(aim, off45, 2.0)
        val tight = RadioRegistry.lobe(aim, off45, 8.0)
        assertTrue(soft > 0.0)
        assertTrue(tight < soft, "tighter focus should attenuate off-axis more ($tight !< $soft)")
    }

    // ── localStrength: omni parity ──
    @Test
    fun `omni closer transmitter is stronger`() {
        val rx = omni()
        assertTrue(
            RadioRegistry.localStrength(tx(Vec3(10.0, 0.0, 0.0)), Vec3.ZERO, rx) >
                RadioRegistry.localStrength(tx(Vec3(100.0, 0.0, 0.0)), Vec3.ZERO, rx),
        )
    }

    @Test
    fun `omni out of range yields negative`() {
        val e = tx(Vec3(1000.0, 0.0, 0.0), range = 100.0)
        assertTrue(RadioRegistry.localStrength(e, Vec3.ZERO, omni(range = 100.0)) < 0.0)
    }

    // ── directionality ──
    @Test
    fun `directional transmitter aimed away is rejected`() {
        val center = Vec3(10.0, 0.0, 0.0)
        val rx = omni()
        val toward = tx(center, aim = Vec3(-1.0, 0.0, 0.0), focus = 4.0) // points at RX at origin
        val away = tx(center, aim = Vec3(1.0, 0.0, 0.0), focus = 4.0)    // points away
        assertTrue(RadioRegistry.localStrength(toward, Vec3.ZERO, rx) > 0.0)
        assertTrue(RadioRegistry.localStrength(away, Vec3.ZERO, rx) < 0.0)
    }

    @Test
    fun `directional receiver only hears within its lobe`() {
        val e = tx(Vec3(10.0, 0.0, 0.0)) // omni TX east of the RX
        val rxToward = AntennaProfile(512.0, 1.0, aim = Vec3(1.0, 0.0, 0.0), focus = 4.0) // ear east, at TX
        val rxAway = AntennaProfile(512.0, 1.0, aim = Vec3(-1.0, 0.0, 0.0), focus = 4.0)  // ear west
        assertTrue(RadioRegistry.localStrength(e, Vec3.ZERO, rxToward) > 0.0)
        assertTrue(RadioRegistry.localStrength(e, Vec3.ZERO, rxAway) < 0.0)
    }
}
