package dev.nitka.nodewire.integration.compactflap

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinLink
import dev.nitka.nodewire.link.PinLinkEngine
import dev.nitka.nodewire.link.PinLinkScratch
import dev.nitka.nodewire.link.PinLinkSink
import dev.nitka.nodewire.link.PinReading
import dev.qwxon.compactflap.content.blocks.compact_flap_bearing.CompactFlapBearingBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

class NodewireFlapBearingBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : CompactFlapBearingBlockEntity(type, pos, state),
    PinLinkSink {

    /** The angle we want the bearing to move to, set via nodewire pin links. */
    private var nodewireTargetAngle: Float = 0f

    // ── PinLinkSink ───────────────────────────────────────────────────────

    private val pinLinks: MutableList<PinLink> = mutableListOf()

    override val pinLinkScratch = PinLinkScratch()

    override fun pinLinks(): MutableList<PinLink> = pinLinks

    override fun onPinLinksChanged() {
        setChanged()
    }

    // ── PinPort: this BE's linkable surface ───────────────────────────────

    override fun pinInputs(ctx: LinkContext): List<LinkPin> = listOf(
        LinkPin(INPUT_PIN, PinType.FLOAT, "Target Angle"),
    )

    override fun pinOutputs(ctx: LinkContext): List<LinkPin> = listOf(
        LinkPin(OUTPUT_PIN, PinType.FLOAT, "Current Angle"),
    )

    override fun readPin(id: String): PinReading? = when (id) {
        OUTPUT_PIN -> PinReading(PinValue.Float(angle))
        else -> null
    }

    override fun writePin(id: String, value: PinValue) {
        if (id != INPUT_PIN) return
        val newAngle = (value as? PinValue.Float)?.value?.coerceIn(-180f, 180f) ?: return
        if (kotlin.math.abs(newAngle - nodewireTargetAngle) > 1e-5f) {
            nodewireTargetAngle = newAngle
            setChanged()
            val lvl = level
            if (lvl != null) {
                lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
            }
        }
    }

    override fun clearPin(id: String) {
        if (id == INPUT_PIN) {
            val prev = nodewireTargetAngle
            nodewireTargetAngle = 0f
            if (kotlin.math.abs(prev) > 1e-5f) {
                setChanged()
            }
        }
    }

    /** Override the bearing's target angle to use our nodewire value. */
    override fun getTargetAngle(): Float = nodewireTargetAngle

    // ── Tick ──────────────────────────────────────────────────────────────

    override fun tick() {
        super.tick()
        val lvl = level
        if (lvl != null && !lvl.isClientSide) {
            PinLinkEngine.tick(lvl, this)
        }
    }

    // ── NBT (Create SmartBlockEntity hooks) ───────────────────────────────

    override fun write(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        super.write(tag, registries, clientPacket)
        tag.putFloat(TARGET_ANGLE_TAG, nodewireTargetAngle)
        if (pinLinks.isNotEmpty()) {
            PinLink.CODEC.listOf()
                .encodeStart(NbtOps.INSTANCE, pinLinks.toList())
                .result()
                .ifPresent { tag.put(PIN_LINKS_TAG, it) }
        }
    }

    override fun read(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        super.read(tag, registries, clientPacket)
        nodewireTargetAngle = tag.getFloat(TARGET_ANGLE_TAG).coerceIn(-180f, 180f)
        pinLinks.clear()
        if (tag.contains(PIN_LINKS_TAG)) {
            PinLink.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get(PIN_LINKS_TAG))
                .result()
                .orElse(emptyList())
                .let { pinLinks.addAll(it) }
        }
    }

    companion object {
        const val INPUT_PIN = "target_angle"
        const val OUTPUT_PIN = "current_angle"
        private const val TARGET_ANGLE_TAG = "nodewire_target_angle"
        private const val PIN_LINKS_TAG = "pin_links"
    }
}
