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
import dev.nitka.nodewire.link.PinReading
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Radio Receiver — the mirror of [RadioTransmitterBlockEntity]. Its lone INPUT
 * is `frequency` (FLOAT, wired in ≤64 m); everything else is an OUTPUT sampled
 * by downstream wires:
 *
 *  * `ch0…ch15` (ANY) — the data bus, mirroring the strongest matching TX.
 *  * `video` (VIDEO) — the one video handle (or NIL when nothing is received).
 *  * `receiving` (BOOL) — true while a TX on this frequency is in range.
 *
 * "Strongest wins": among TXs on the tuned frequency that are in range, the one
 * with the greatest `gain / dist²` is mirrored (see [RadioRegistry.best]).
 */
class RadioReceiverBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.RADIO_RECEIVER_BE.get(), pos, state),
    PinLinkSink,
    AntennaHost {

    private val antenna = AntennaSlot()
    override fun radioAntenna(): AntennaSlot = antenna

    private val pinLinks: MutableList<PinLink> = mutableListOf()
    override val pinLinkScratch = PinLinkScratch()
    override fun pinLinks(): MutableList<PinLink> = pinLinks
    override fun onPinLinksChanged() = setChanged()

    // Tuned frequency — delivered live each tick into the FREQ input pin.
    private var frequency: Float = 0f

    // Per-tick resolved transmitter (cached so all output reads in a tick agree).
    private var cacheTick: Long = Long.MIN_VALUE
    private var cached: RadioRegistry.TxEntry? = null

    override fun pinInputs(ctx: LinkContext): List<LinkPin> =
        listOf(LinkPin(RadioChannels.FREQ_PIN, PinType.FLOAT, "frequency"))

    override fun pinOutputs(ctx: LinkContext): List<LinkPin> = buildList {
        for (i in 0 until RadioChannels.NUM_CHANNELS) add(LinkPin(RadioChannels.chId(i), PinType.ANY, "ch$i"))
        add(LinkPin(RadioChannels.VIDEO_PIN, PinType.VIDEO, "video"))
        add(LinkPin(RadioChannels.RECEIVING_PIN, PinType.BOOL, "receiving"))
    }

    override fun writePin(id: String, value: PinValue) {
        if (id == RadioChannels.FREQ_PIN) {
            frequency = (PinValueConversion.convert(value, PinType.FLOAT) as? PinValue.Float)?.value ?: 0f
            setChanged()
        }
    }

    override fun clearPin(id: String) {
        if (id == RadioChannels.FREQ_PIN) {
            frequency = 0f
            setChanged()
        }
    }

    override fun readPin(id: String): PinReading? {
        val lvl = level ?: return null
        if (lvl.isClientSide) return null
        val tx = resolve(lvl)
        return when {
            id == RadioChannels.RECEIVING_PIN -> PinReading(PinValue.Bool(tx != null))
            id == RadioChannels.VIDEO_PIN -> PinReading(PinValue.Video(tx?.video ?: RadioChannels.NIL_HANDLE))
            else -> {
                val i = RadioChannels.chIndex(id) ?: return null
                PinReading(tx?.slots?.getOrNull(i) ?: PinValue.default(PinType.ANY))
            }
        }
    }

    /** Resolve (once per tick) the strongest TX on the tuned frequency in range. */
    private fun resolve(level: Level): RadioRegistry.TxEntry? {
        val now = level.gameTime
        if (now != cacheTick) {
            cacheTick = now
            cached = RadioRegistry.best(
                level = level,
                freqKey = RadioRegistry.freqKey(frequency),
                rxCenter = worldCenter(level),
                rxRange = antenna.range(),
                rxCrossWorld = antenna.crossWorld(),
                now = now,
            )
        }
        return cached
    }

    private fun worldCenter(level: Level): Vec3 =
        runCatching { EndpointRef.from(level, blockPos).worldCenter(level) }.getOrNull()
            ?: Vec3.atCenterOf(blockPos)

    // ── persistence + sync (antenna + links; reception is live) ──
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
