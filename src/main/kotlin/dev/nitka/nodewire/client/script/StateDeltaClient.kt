package dev.nitka.nodewire.client.script

import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.net.StateDeltaPacket
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag

/**
 * Client-side application of a [StateDeltaPacket]. Runs on the client thread
 * (handler-enqueued work, [StateDeltaPacket.Companion.handle]). Resolves the
 * client BE copy and merges the delta tags into the node's client replicated
 * state via the no-broadcast [LogicBlockEntity.applyClientStateDelta] helper, so
 * a future client script runtime (2c) reads up-to-date replicated values.
 *
 * Phase 2b is server-authoritative one-way: this is the END of the wire — there
 * is NO client runtime yet (2c), only the staged tag.
 */
object StateDeltaClient {
    fun apply(packet: StateDeltaPacket) {
        val be = Minecraft.getInstance().level?.getBlockEntity(packet.blockPos)
            as? LogicBlockEntity ?: return
        val merged = CompoundTag().apply { packet.deltas.forEach { merge(it.value) } }
        be.applyClientStateDelta(packet.nodeId, merged)
    }
}
