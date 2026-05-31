package dev.nitka.nodewire.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateReplicationDirtyTest {

    // Minimal module: one replicated Int cell + one non-replicated Int cell,
    // exposed for direct mutation in the test.
    private class M : ScriptModule() {
        var repl by state(0, replicated = true)
        var plain by state(0, replicated = false)
    }

    @Test fun `changing a replicated cell yields exactly one delta`() {
        val m = M()
        m.snapshotReplicated()          // baseline = current values
        m.repl = 7
        val dirty = m.drainReplicatedDeltas()
        assertEquals(1, dirty.size)
        assertEquals("repl", dirty[0].key)
        assertTrue(dirty[0].value is Int && (dirty[0].value as Int) == 7)
    }

    @Test fun `no change yields no delta`() {
        val m = M()
        m.snapshotReplicated()
        assertTrue(m.drainReplicatedDeltas().isEmpty())
    }

    @Test fun `non-replicated cell never appears in deltas`() {
        val m = M()
        m.snapshotReplicated()
        m.plain = 99
        assertTrue(m.drainReplicatedDeltas().isEmpty())
    }

    @Test fun `draining clears dirty state`() {
        val m = M()
        m.snapshotReplicated()
        m.repl = 1
        assertEquals(1, m.drainReplicatedDeltas().size)
        assertTrue(m.drainReplicatedDeltas().isEmpty())   // already drained, no further change
    }
}
