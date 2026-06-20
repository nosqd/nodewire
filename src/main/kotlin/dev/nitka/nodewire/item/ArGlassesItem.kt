package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.ArHubBlockEntity
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterial
import net.minecraft.world.item.ArmorMaterials
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.UseOnContext

class ArGlassesItem(props: Properties) :
    ArmorItem(ArmorMaterials.IRON, ArmorItem.Type.HELMET, props) {

    override fun useOn(ctx: UseOnContext): InteractionResult {
        val level = ctx.level
        val player = ctx.player ?: return InteractionResult.PASS
        val pos = ctx.clickedPos
        val stack = ctx.itemInHand

        if (!player.isShiftKeyDown) return InteractionResult.PASS
        if (level.getBlockEntity(pos) !is ArHubBlockEntity) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val dim = level.dimension().location().toString()
        val longPos = pos.asLong()
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        tag.putString(ArHubBlockEntity.HUB_DIM_KEY, dim)
        tag.putLong(ArHubBlockEntity.HUB_POS_KEY, longPos)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))

        player.displayClientMessage(
            Component.literal("AR Glasses linked to (${pos.x}, ${pos.y}, ${pos.z})")
                .withStyle(ChatFormatting.GREEN),
            true,
        )
        return InteractionResult.CONSUME
    }

    override fun getEquipmentSlot(stack: ItemStack): EquipmentSlot = EquipmentSlot.HEAD
}
