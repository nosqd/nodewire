package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.client.screen.ChannelPickerScreen
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.net.BindChannelPacket
import dev.nitka.nodewire.net.NodewireNetwork
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext

/**
 * Channel Link Tool — explicit channel-by-channel wiring.
 *
 * Sneak + right-click a logic block → opens [ChannelPickerScreen] with
 * every named `channel_output` on that block. Pick one ⇒ the source pos
 * and selected channel name are saved into the stack's NBT.
 *
 * Right-click any logic block while a source is set → opens the picker
 * again with the target's `channel_input` nodes filtered to those whose
 * [PinType] matches the source's. Pick one ⇒ [BindChannelPacket] flies to
 * the server, which calls [LogicBlockEntity.addBinding] and pushes the
 * BE update so other clients see the new wire.
 *
 * All UX runs on the client; the server is only contacted once at bind
 * commit. Stack NBT lives on the client copy of the stack — it's just
 * memory between the two clicks, never round-trips.
 */
class ChannelLinkToolItem(props: Properties) : Item(props) {

    /**
     * Runs *before* [LogicBlock.use] in the 1.20.1 interaction order, so
     * the editor screen never opens when this tool is held. Returns
     * SUCCESS to stop both Block.use and Item.useOn from firing.
     */
    override fun onItemUseFirst(stack: ItemStack, ctx: UseOnContext): InteractionResult =
        handle(stack, ctx)

    override fun useOn(ctx: UseOnContext): InteractionResult = handle(ctx.itemInHand, ctx)

    private fun handle(stack: ItemStack, ctx: UseOnContext): InteractionResult {
        val level = ctx.level
        val player = ctx.player ?: return InteractionResult.PASS
        val pos = ctx.clickedPos
        val be = level.getBlockEntity(pos) as? LogicBlockEntity
            ?: return InteractionResult.PASS

        // All UX is client-side; server-side just consumes the click.
        if (level.isClientSide) {
            if (player.isShiftKeyDown) openSourcePicker(stack, be)
            else openTargetPicker(stack, be)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    private fun openSourcePicker(stack: ItemStack, be: LogicBlockEntity) {
        val mc = Minecraft.getInstance()
        val options = be.graph.nodes.values
            .filter { it.typeKey.path == "channel_output" }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) return@mapNotNull null
                val type = PinType.fromName(node.config.getString("type"))
                ChannelPickerScreen.Option(name, type)
            }
        if (options.isEmpty()) {
            actionBar("No named channel outputs on this block.", true)
            return
        }
        val srcPos = be.blockPos
        mc.setScreen(ChannelPickerScreen("Source channel", options) { picked ->
            stack.orCreateTag.put(NBT_SOURCE_POS, NbtUtils.writeBlockPos(srcPos))
            stack.orCreateTag.putString(NBT_SOURCE_NAME, picked)
            actionBar("Source: ${srcPos.toShortString()} / $picked", false)
        })
    }

    private fun openTargetPicker(stack: ItemStack, be: LogicBlockEntity) {
        val tag = stack.tag
        if (tag == null || !tag.contains(NBT_SOURCE_POS) || !tag.contains(NBT_SOURCE_NAME)) {
            actionBar("Pick a source first: Shift + right-click a logic block", true)
            return
        }
        val sourcePos = NbtUtils.readBlockPos(tag.getCompound(NBT_SOURCE_POS))
        val sourceName = tag.getString(NBT_SOURCE_NAME)
        if (sourcePos == be.blockPos) {
            actionBar("Source and target must differ", true)
            return
        }

        val mc = Minecraft.getInstance()
        val sourceBe = mc.level?.getBlockEntity(sourcePos) as? LogicBlockEntity
        if (sourceBe == null) {
            actionBar("Source block no longer exists", true)
            return
        }
        val sourceNode = sourceBe.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output" && it.config.getString("name") == sourceName
        }
        if (sourceNode == null) {
            actionBar("Source channel '$sourceName' no longer exists", true)
            return
        }
        val sourceType = PinType.fromName(sourceNode.config.getString("type"))

        // Only show target inputs that match the source's type.
        val options = be.graph.nodes.values
            .filter { it.typeKey.path == "channel_input" }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) return@mapNotNull null
                val type = PinType.fromName(node.config.getString("type"))
                if (type != sourceType) return@mapNotNull null
                ChannelPickerScreen.Option(name, type)
            }
        if (options.isEmpty()) {
            actionBar("No ${sourceType.name.lowercase()} channel inputs on this block.", true)
            return
        }
        val targetPos = be.blockPos
        mc.setScreen(ChannelPickerScreen("Target channel (${sourceType.name.lowercase()})", options) { picked ->
            NodewireNetwork.CHANNEL.sendToServer(
                BindChannelPacket(sourcePos, sourceName, targetPos, picked),
            )
            actionBar(
                "Bound ${sourcePos.toShortString()}/$sourceName → ${targetPos.toShortString()}/$picked",
                false,
            )
        })
    }

    private fun actionBar(message: String, warn: Boolean) {
        val component = if (warn) {
            Component.literal(message).withStyle(ChatFormatting.YELLOW)
        } else {
            Component.literal(message)
        }
        Minecraft.getInstance().player?.displayClientMessage(component, true)
    }

    companion object {
        private const val NBT_SOURCE_POS = "source_pos"
        private const val NBT_SOURCE_NAME = "source_name"
    }
}
