package dev.nitka.nodewire.integration.compactflap

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModList
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * CompactFlap integration — Nodewire Flap Bearing.
 *
 * ALL references to CompactFlap / Create classes live in this package.
 * [init] is guarded by [ModList.isLoaded] so the JVM never loads
 * CompactFlap classes when the mod isn't installed.
 */
object CompactFlapIntegration {
    private val LOG = LogUtils.getLogger()
    private const val MOD_ID = "compactflap"

    private val BLOCKS: DeferredRegister.Blocks =
        DeferredRegister.createBlocks("nodewire")
    private val ITEMS: DeferredRegister.Items =
        DeferredRegister.createItems("nodewire")
    private val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "nodewire")

    /** Initialized inside [init] — not touched before the ModList gate. */
    lateinit var FLAP_BEARING_BLOCK: DeferredBlock<NodewireFlapBearingBlock>
        private set

    lateinit var FLAP_BEARING_ITEM: DeferredHolder<Item, BlockItem>
        private set

    lateinit var FLAP_BEARING_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<NodewireFlapBearingBlockEntity>>
        private set

    /**
     * Wire up everything. No-op when CompactFlap isn't installed.
     * Must be called from [dev.nitka.nodewire.Nodewire.init] during mod
     * construction, AFTER [dev.nitka.nodewire.Registry.register].
     */
    fun init(bus: IEventBus) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            LOG.info("[nodewire/compactflap] CompactFlap not loaded — skipping")
            return
        }
        FLAP_BEARING_BLOCK = BLOCKS.register("nodewire_flap_bearing") { _ ->
            NodewireFlapBearingBlock(
                BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK),
                { FLAP_BEARING_BE.get() },
            )
        }
        @Suppress("UNCHECKED_CAST")
        FLAP_BEARING_ITEM = ITEMS.register("nodewire_flap_bearing") { _ ->
            BlockItem(FLAP_BEARING_BLOCK.get(), Item.Properties())
        } as DeferredHolder<Item, BlockItem>
        FLAP_BEARING_BE = BLOCK_ENTITIES.register("nodewire_flap_bearing") { _ ->
            BlockEntityType.Builder
                .of(
                    { pos: BlockPos, state: BlockState ->
                        NodewireFlapBearingBlockEntity(FLAP_BEARING_BE.get(), pos, state)
                    },
                    FLAP_BEARING_BLOCK.get(),
                )
                .build(null)
        }
        BLOCKS.register(bus)
        ITEMS.register(bus)
        BLOCK_ENTITIES.register(bus)
        bus.addListener(::onBuildTabs)
        LOG.info("[nodewire/compactflap] Nodewire Flap Bearing registered")
    }

    private fun onBuildTabs(event: net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(FLAP_BEARING_ITEM.get())
        }
    }
}
