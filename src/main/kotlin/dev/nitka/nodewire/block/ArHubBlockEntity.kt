package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.item.ArGlassesItem
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinLink
import dev.nitka.nodewire.link.PinLinkEngine
import dev.nitka.nodewire.link.PinLinkScratch
import dev.nitka.nodewire.link.PinReading
import dev.nitka.nodewire.net.SyncArStatePacket
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

class ArHubBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.AR_HUB_BE.get(), pos, state),
    dev.nitka.nodewire.link.PinLinkSink {

    private val pinLinks: MutableList<PinLink> = mutableListOf()

    override val pinLinkScratch = PinLinkScratch()

    override fun pinLinks(): MutableList<PinLink> = pinLinks

    override fun onPinLinksChanged() {
        pushSync()
    }

    override fun pinInputs(ctx: LinkContext): List<LinkPin> =
        listOf(LinkPin(VIDEO_PIN, PinType.VIDEO))

    override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
        listOf(
            LinkPin(IS_WORN_PIN, PinType.BOOL),
            LinkPin(PLAYER_POS_PIN, PinType.VEC3),
            LinkPin(PLAYER_LOOK_PIN, PinType.VEC3),
        )

    private var lastIsWorn: Boolean = false
    private var lastPlayerPos: Vec3 = Vec3.ZERO
    private var lastPlayerLook: Vec3 = Vec3.ZERO

    override fun readPin(id: String): PinReading? = when (id) {
        IS_WORN_PIN -> PinReading(PinValue.Bool(lastIsWorn))
        PLAYER_POS_PIN -> PinReading(
            PinValue.Vec3(lastPlayerPos.x, lastPlayerPos.y, lastPlayerPos.z)
        )
        PLAYER_LOOK_PIN -> PinReading(
            PinValue.Vec3(lastPlayerLook.x, lastPlayerLook.y, lastPlayerLook.z)
        )
        else -> null
    }

    override fun writePin(id: String, value: PinValue) {
        channelInputs[id] = value
        setChanged()
    }

    override fun clearPin(id: String) {
        if (channelInputs.remove(id) != null) {
            setChanged()
            val lvl = level
            if (lvl != null && !lvl.isClientSide) {
                lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
            }
        }
    }

    private val channelInputs: MutableMap<String, PinValue> = mutableMapOf()
    private var lastBoundPlayerUUID: UUID? = null

    fun tickServer(lvl: Level) {
        PinLinkEngine.tick(lvl, this)

        val server = lvl.server ?: return
        val dim = lvl.dimension().location()
        val pos = blockPos

        var foundPlayer: ServerPlayer? = null

        for (player in server.playerList.players) {
            val stack = player.getItemBySlot(EquipmentSlot.HEAD)
            if (stack.item !is ArGlassesItem) continue
            val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
            if (tag.contains(HUB_DIM_KEY) && tag.contains(HUB_POS_KEY)) {
                val hubDim = tag.getString(HUB_DIM_KEY)
                val hubPos = BlockPos.of(tag.getLong(HUB_POS_KEY))
                if (hubDim == dim.toString() && hubPos == pos) {
                    foundPlayer = player
                    break
                }
            }
        }

        if (foundPlayer != null) {
            lastIsWorn = true
            lastPlayerPos = foundPlayer.position()
            val yaw = Math.toRadians(foundPlayer.yRot.toDouble())
            val pitch = Math.toRadians(foundPlayer.xRot.toDouble())
            val lookX = -Math.sin(yaw) * Math.cos(pitch)
            val lookY = -Math.sin(pitch)
            val lookZ = Math.cos(yaw) * Math.cos(pitch)
            lastPlayerLook = Vec3(lookX, lookY, lookZ)
        } else {
            lastIsWorn = false
            lastPlayerPos = Vec3.ZERO
            lastPlayerLook = Vec3.ZERO
        }

        val videoHandle = (channelInputs[VIDEO_PIN] as? PinValue.Video)?.handle
            ?: dev.nitka.nodewire.radio.RadioChannels.NIL_HANDLE

        if (foundPlayer != null) {
            PacketDistributor.sendToPlayer(foundPlayer, SyncArStatePacket(videoHandle))
            lastBoundPlayerUUID = foundPlayer.uuid
        } else {
            val prev = lastBoundPlayerUUID
            if (prev != null) {
                val prevPlayer = server.playerList.getPlayer(prev)
                if (prevPlayer != null) {
                    PacketDistributor.sendToPlayer(
                        prevPlayer, SyncArStatePacket(dev.nitka.nodewire.radio.RadioChannels.NIL_HANDLE)
                    )
                }
                lastBoundPlayerUUID = null
            }
        }
    }

    fun clearClientState() {
        val server = level?.server ?: return
        val prev = lastBoundPlayerUUID
        if (prev != null) {
            val prevPlayer = server.playerList.getPlayer(prev)
            if (prevPlayer != null) {
                PacketDistributor.sendToPlayer(
                    prevPlayer, SyncArStatePacket(dev.nitka.nodewire.radio.RadioChannels.NIL_HANDLE)
                )
            }
            lastBoundPlayerUUID = null
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        if (pinLinks.isNotEmpty()) {
            PinLink.CODEC.listOf()
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, pinLinks.toList())
                .result().ifPresent { tag.put(TAG_PIN_LINKS, it) }
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        pinLinks.clear()
        if (tag.contains(TAG_PIN_LINKS)) {
            PinLink.CODEC.listOf()
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get(TAG_PIN_LINKS))
                .result().ifPresent { pinLinks.addAll(it) }
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { saveAdditional(it, registries) }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    private fun pushSync() {
        setChanged()
        val lvl = level
        if (lvl != null && !lvl.isClientSide) {
            lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        }
    }

    companion object {
        const val VIDEO_PIN = "video"
        const val IS_WORN_PIN = "is_worn"
        const val PLAYER_POS_PIN = "player_pos"
        const val PLAYER_LOOK_PIN = "player_look"
        const val HUB_DIM_KEY = "nw:hubDim"
        const val HUB_POS_KEY = "nw:hubPos"
        private const val TAG_PIN_LINKS = "pin_links"
    }
}
