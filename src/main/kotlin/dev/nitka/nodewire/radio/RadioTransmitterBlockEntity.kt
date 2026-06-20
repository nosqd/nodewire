package dev.nitka.nodewire.radio

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.PinValueConversion
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinLink
import dev.nitka.nodewire.link.PinLinkScratch
import dev.nitka.nodewire.link.PinLinkSink
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Radio Transmitter — a [PinLinkSink] you wire data into (≤64 m). Each server
 * tick it pulls its wired inputs and [publishes][RadioRegistry.publish] the
 * current `{frequency, slots, video}` so receivers on the same frequency in
 * range can pick it up.
 *
 * Pins (all inputs): `frequency` (FLOAT), one dedicated `video` (VIDEO), and an
 * indexed data bus `ch0…ch15` (ANY — but VIDEO is rejected at bind via
 * [acceptsSource], so the lone video stream can only land on `video`).
 */
class RadioTransmitterBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.RADIO_TRANSMITTER_BE.get(), pos, state),
    PinLinkSink,
    AntennaHost {

    private val antenna = AntennaSlot()
    override fun radioAntenna(): AntennaSlot = antenna

    private val pinLinks: MutableList<PinLink> = mutableListOf()
    override val pinLinkScratch = PinLinkScratch()
    override fun pinLinks(): MutableList<PinLink> = pinLinks
    override fun onPinLinksChanged() = setChanged()

    // Live (transient) payload, refreshed each tick by writePin.
    private var frequency: Float = 0f
    private var videoHandle: UUID = RadioChannels.NIL_HANDLE
    private val slots = arrayOfNulls<PinValue>(RadioChannels.NUM_CHANNELS)

    override fun pinInputs(ctx: LinkContext): List<LinkPin> = buildList {
        add(LinkPin(RadioChannels.FREQ_PIN, PinType.FLOAT, "frequency"))
        add(LinkPin(RadioChannels.VIDEO_PIN, PinType.VIDEO, "video"))
        for (i in 0 until RadioChannels.NUM_CHANNELS) add(LinkPin(RadioChannels.chId(i), PinType.ANY, "ch$i"))
    }

    /** Keep VIDEO out of the ANY data slots (ANY accepts everything otherwise);
     *  the one video stream may only land on the dedicated `video` pin. */
    override fun acceptsSource(targetPin: String, srcType: PinType): Boolean =
        !(srcType == PinType.VIDEO && targetPin.startsWith(RadioChannels.CH_PREFIX))

    override fun writePin(id: String, value: PinValue) {
        when {
            id == RadioChannels.FREQ_PIN ->
                frequency = (PinValueConversion.convert(value, PinType.FLOAT) as? PinValue.Float)?.value ?: 0f
            id == RadioChannels.VIDEO_PIN -> videoHandle = (value as? PinValue.Video)?.handle ?: RadioChannels.NIL_HANDLE
            else -> RadioChannels.chIndex(id)?.let { if (value !is PinValue.Video) slots[it] = value }
        }
        setChanged()
    }

    override fun clearPin(id: String) {
        when {
            id == RadioChannels.FREQ_PIN -> frequency = 0f
            id == RadioChannels.VIDEO_PIN -> videoHandle = RadioChannels.NIL_HANDLE
            else -> RadioChannels.chIndex(id)?.let { slots[it] = null }
        }
        setChanged()
    }

    /** Publish the live payload to the registry (called from the server ticker). */
    fun publish(level: Level) {
        val p = antennaProfile()
        RadioRegistry.publish(
            level,
            RadioRegistry.TxEntry(
                pos = blockPos,
                center = worldCenter(level),
                freqKey = RadioRegistry.freqKey(frequency),
                range = p.range,
                gain = p.gain,
                crossWorld = p.crossWorld,
                aim = p.aim,
                focus = p.focus,
                slots = slots.copyOf(),
                video = videoHandle,
                stamp = level.gameTime,
            ),
        )
    }

    private fun worldCenter(level: Level): Vec3 =
        runCatching { EndpointRef.from(level, blockPos).worldCenter(level) }.getOrNull()
            ?: Vec3.atCenterOf(blockPos)

    override fun setRemoved() {
        level?.let { if (!it.isClientSide) RadioRegistry.remove(it, blockPos) }
        super.setRemoved()
    }

    // ── persistence + sync (antenna + links; payload is live) ──
    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        antenna.save(tag, registries)
        if (pinLinks.isNotEmpty()) {
            PinLink.CODEC.listOf().encodeStart(NbtOps.INSTANCE, pinLinks.toList())
                .result().ifPresent { tag.put(PIN_LINKS_KEY, it) }
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        antenna.load(tag, registries)
        pinLinks.clear()
        if (tag.contains(PIN_LINKS_KEY)) {
            PinLink.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get(PIN_LINKS_KEY))
                .result().ifPresent { pinLinks.addAll(it) }
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { saveAdditional(it, registries) }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private const val PIN_LINKS_KEY = "pin_links"
    }
}
