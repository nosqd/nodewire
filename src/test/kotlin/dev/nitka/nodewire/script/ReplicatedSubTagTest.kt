package dev.nitka.nodewire.script

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2b.3b: the late-joiner getUpdateTag piggyback ships ONLY replicated keys from a
 * node's full (saveState-shaped) state tag. Pure helper, unit-tested directly.
 */
class ReplicatedSubTagTest {

    private class M : ScriptModule() {
        var repl by state(7, replicated = true)
        var plain by state(3, replicated = false)
    }

    @Test fun `sub-tag carries replicated keys only`() {
        // Build a full state tag the way the server evaluator holds it.
        val m = M()
        m.repl = 7
        m.plain = 3
        val full = CompoundTag()
        m.saveState(full)
        assertTrue(full.contains("repl"))
        assertTrue(full.contains("plain"))

        val sub = ScriptModuleReplication.buildReplicatedSubTag(full, m.replicatedKeys())
        assertEquals(7, sub.getInt("repl"))
        assertFalse(sub.contains("plain"))
    }
}
