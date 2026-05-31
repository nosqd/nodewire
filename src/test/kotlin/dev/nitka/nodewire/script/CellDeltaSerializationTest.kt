package dev.nitka.nodewire.script

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CellDeltaSerializationTest {

    private class M : ScriptModule() {
        var hp by state(0, replicated = true)
        var sig by state(Redstone.OFF, replicated = true)
    }

    @Test fun `delta tag round-trips into a fresh module cell, REDSTONE clamped`() {
        val server = M()
        server.snapshotReplicated()
        server.hp = 42
        server.sig = Redstone.of(15)
        val deltas = server.drainReplicatedDeltas()

        // Host encodes each delta to a single-key tag (the helper added below).
        val tags = deltas.associate { it.key to ScriptModuleReplication.encodeCell(it) }

        // Client merges into a fresh module.
        val client = M()
        val merged = CompoundTag().apply { tags.values.forEach { merge(it) } }
        ScriptModuleReplication.applyCells(client, merged)

        assertEquals(42, client.hp)
        assertEquals(15, client.sig.power)
    }
}
