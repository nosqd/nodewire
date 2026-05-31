package dev.nitka.nodewire.script

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * CONTRACT test for the non-replicated client-read throw (spec §5.7 / D2). Pinned
 * here in 2b so the contract is recorded, but the client-side `state` read
 * accessor + the client-mode flag land in Phase 2c — so this stays @Disabled
 * until then. Do NOT enable without the 2c client read accessor.
 */
@Disabled("enabled in Phase 2c with the client read accessor")
class NonReplicatedClientReadTest {
    private class M : ScriptModule() {
        var local by state(0, replicated = false)
    }

    @Test fun `reading a non-replicated cell on the client throws a clear error`() {
        val m = M()
        // m.setClientSide(true)   // 2c client-mode flag
        val ex = runCatching { @Suppress("UNUSED_EXPRESSION") m.local }.exceptionOrNull()
        assertTrue(ex?.message?.contains("not replicated") == true)
    }
}
