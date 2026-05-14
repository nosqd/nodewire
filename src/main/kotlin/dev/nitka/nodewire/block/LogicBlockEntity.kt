package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.StatefulGraphEvaluator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Stores the editable [NodeGraph] for one logic block, and on the server
 * runs the per-tick evaluator that drives redstone I/O.
 *
 * Lifecycle:
 *   * [graph] holds the persisted graph data. Editor save replaces this
 *     via the SaveGraphPacket handler.
 *   * [serverEvaluator] is created lazily on the first tick. Held across
 *     ticks so [StatefulGraphEvaluator]'s per-node state (Timer counters,
 *     edge detectors, …) advances continuously. Rebuilt on graph swap
 *     via [invalidateEvaluator].
 *   * [faceOutputs] is computed at the end of each tick — keyed by the
 *     world face that should emit the redstone signal. [LogicBlock]'s
 *     [getSignal] reads from it.
 *
 * No nodeStates persistence yet — Timer counters reset on world reload.
 * Adding it is a small follow-up (StatefulGraphEvaluator can serialize
 * its state map to NBT).
 */
class LogicBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.LOGIC_BLOCK_BE.get(), pos, state) {

    var graph: NodeGraph = NodeGraph()

    private var serverEvaluator: StatefulGraphEvaluator? = null

    /** Last computed redstone power per face. Empty until first server tick. */
    var faceOutputs: Map<Direction, Int> = emptyMap()
        private set

    /** Call after replacing [graph] (e.g. from SaveGraphPacket) so the next tick rebuilds. */
    fun invalidateEvaluator() {
        serverEvaluator = null
    }

    /**
     * One server tick: read neighbour redstone into `side_input` nodes,
     * advance the evaluator, push `side_output` results into [faceOutputs],
     * and notify neighbours if anything actually changed.
     *
     * Cost is bounded by graph size + edge count; trivial for typical
     * graphs and runs once per game tick (20 Hz).
     */
    fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        val eval = serverEvaluator ?: StatefulGraphEvaluator(graph).also { serverEvaluator = it }

        // Read neighbour redstone for every SideInput node.
        val external = HashMap<Pair<java.util.UUID, String>, PinValue>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "side_input") continue
            val face = directionOf(node.config.getString("face")) ?: continue
            // Power emitted by the neighbour towards us = neighbour's getSignal
            // in the direction back at our block.
            val signal = level.getSignal(pos.relative(face), face.opposite)
            external[node.id to "out"] = PinValue.Redstone(signal)
        }

        val result = eval.tick(external)

        // Collect SideOutput values.
        val updated = HashMap<Direction, Int>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "side_output") continue
            val face = directionOf(node.config.getString("face")) ?: continue
            val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == "in" }
            val value = edge?.let { result.valueAt(it.from.node, it.from.pin) }
            val signal = when (value) {
                is PinValue.Redstone -> value.value.coerceIn(0, 15)
                is PinValue.Int -> value.value.coerceIn(0, 15)
                is PinValue.Bool -> if (value.value) 15 else 0
                else -> 0
            }
            // Later face writes win if the user has two SideOutputs on the
            // same face — that's a user error caught by the future
            // duplicate-face validator; here we just take the last.
            updated[face] = signal
        }

        if (updated != faceOutputs) {
            faceOutputs = updated
            // Notify neighbours so vanilla redstone propagates from the
            // newly-emitting faces. `updateNeighborsAt` posts redstone-
            // signal updates around our position.
            level.updateNeighborsAt(pos, blockState.block)
            setChanged()
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put("graph", graph.toNbt())
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        graph = if (tag.contains("graph")) {
            NodeGraph.fromNbt(tag.getCompound("graph"))
        } else {
            NodeGraph()
        }
        // Force a rebuild — old evaluator references the previous graph.
        invalidateEvaluator()
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun onDataPacket(net: Connection, pkt: ClientboundBlockEntityDataPacket) {
        pkt.tag?.let { load(it) }
    }

    companion object {
        private val DIRECTIONS_BY_NAME = Direction.entries.associateBy { it.name.lowercase() }

        /** Case-insensitive face-name parser; null on unknown values. */
        private fun directionOf(name: String): Direction? =
            DIRECTIONS_BY_NAME[name.lowercase()]
    }
}
