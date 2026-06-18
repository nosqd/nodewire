package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.control.BindKind
import dev.nitka.nodewire.block.control.Binding
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinPort
import dev.nitka.nodewire.link.PinReading
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.lwjgl.glfw.GLFW

/**
 * BlockEntity for [ControlBlock] — a configurable cockpit that exposes the
 * pilot's keyboard/mouse as [PinPort] output pins.
 *
 * Its pin set is DATA-DRIVEN: a list of [Binding]s (authored in the config
 * menu, persisted + synced) maps input to named pins (a key → BOOL, two keys →
 * an AXIS float, WASD → a VEC2, the mouse → look, …). During a control session
 * the *client* reads the bound keys, computes each binding's value and streams
 * them here ([applyInput]); [readPin] serves the latest values to linked
 * consumers, falling back to type defaults once the input goes stale (the
 * pilot left, disconnected, …) so a held key never sticks.
 */
class ControlBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.CONTROL_BLOCK_BE.get(), pos, state),
    PinPort {

    private var bindings: MutableList<Binding> = defaultBindings()

    /** Server-side live values from the latest [applyInput]; transient. */
    private val liveValues: MutableMap<String, PinValue> = mutableMapOf()
    private var lastInputTick: Long = Long.MIN_VALUE

    fun bindings(): List<Binding> = bindings

    /** Replace the binding layout (config menu commit). Server side. */
    fun setBindings(list: List<Binding>) {
        bindings = list.toMutableList()
        liveValues.clear()
        setChanged()
        syncToClients()
    }

    /** Store a tick of computed pin values from the piloting client. */
    fun applyInput(values: Map<String, PinValue>) {
        liveValues.clear()
        liveValues.putAll(values)
        lastInputTick = level?.gameTime ?: 0L
    }

    override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
        buildList {
            bindings.forEach { add(LinkPin(it.pin, it.type)) }
            add(LinkPin(ACTIVE_PIN, PinType.BOOL, "active"))
            add(LinkPin(MOUSE_CAPTURED_PIN, PinType.BOOL, "mouse_captured"))
        }

    private fun pinType(id: String): PinType? = when (id) {
        ACTIVE_PIN, MOUSE_CAPTURED_PIN -> PinType.BOOL
        else -> bindings.firstOrNull { it.pin == id }?.type
    }

    override fun readPin(id: String): PinReading? {
        val type = pinType(id) ?: return null
        val held = liveValues[id]
        // Absolute aim (MOUSE_LOOK) HOLDS its last value after the pilot leaves,
        // so a turret stays where it was aimed; momentary inputs (buttons, axes,
        // mouse delta, scroll, active flags) fall back to defaults when stale.
        if (held != null && bindings.firstOrNull { it.pin == id }?.kind == BindKind.MOUSE_LOOK) {
            return PinReading(held)
        }
        val fresh = liveValues.isNotEmpty() &&
            (level?.let { it.gameTime - lastInputTick <= STALE_TICKS } ?: false)
        return PinReading(if (fresh) held ?: PinValue.default(type) else PinValue.default(type))
    }

    // ── persistence + sync ────────────────────────────────────────────────

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        Binding.CODEC.listOf()
            .encodeStart(NbtOps.INSTANCE, bindings.toList())
            .result().ifPresent { tag.put(BINDINGS_KEY, it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(BINDINGS_KEY)) {
            Binding.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get(BINDINGS_KEY))
                .result().ifPresent { bindings = it.toMutableList() }
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { saveAdditional(it, registries) }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    private fun syncToClients() {
        val lvl = level ?: return
        if (!lvl.isClientSide) lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    companion object {
        const val ACTIVE_PIN = "active"
        const val MOUSE_CAPTURED_PIN = "mouse_captured"
        private const val BINDINGS_KEY = "bindings"

        /** Stale input older than this (ticks) → pins fall back to defaults. */
        private const val STALE_TICKS = 5L

        /** Sensible out-of-the-box layout so a freshly placed block is useful. */
        fun defaultBindings(): MutableList<Binding> = mutableListOf(
            Binding("move", BindKind.VECTOR, listOf(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_D)),
            Binding("jump", BindKind.BUTTON, listOf(GLFW.GLFW_KEY_SPACE)),
            Binding("crouch", BindKind.BUTTON, listOf(GLFW.GLFW_KEY_LEFT_SHIFT)),
        )
    }
}
