package dev.nitka.nodewire.integration.compactflap

import dev.qwxon.compactflap.content.blocks.compact_flap_bearing.CompactFlapBearingBlock
import dev.qwxon.compactflap.content.blocks.compact_flap_bearing.CompactFlapBearingBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

class NodewireFlapBearingBlock(
    props: BlockBehaviour.Properties,
    private val beType: () -> BlockEntityType<NodewireFlapBearingBlockEntity>,
) : CompactFlapBearingBlock(props) {

    @Suppress("UNCHECKED_CAST")
    override fun getBlockEntityClass(): Class<CompactFlapBearingBlockEntity> =
        NodewireFlapBearingBlockEntity::class.java as Class<CompactFlapBearingBlockEntity>

    @Suppress("UNCHECKED_CAST")
    override fun getBlockEntityType(): BlockEntityType<CompactFlapBearingBlockEntity> =
        beType() as BlockEntityType<CompactFlapBearingBlockEntity>

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        NodewireFlapBearingBlockEntity(beType(), pos, state)
}
