package dev.nitka.nodewire.radio

import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * An antenna that slots into a Radio Transmitter / Receiver to grant range.
 * [range] is the broadcast/receive reach in blocks; [gain] biases the
 * "strongest wins" contest. Plain data item — the radio block reads these off
 * the held stack's item.
 */
class RadioAntennaItem(
    props: Properties,
    val range: Double,
    val gain: Double,
) : Item(props) {

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        tooltip.add(Component.literal("Range: ${range.toInt()} m").withStyle(net.minecraft.ChatFormatting.AQUA))
        tooltip.add(Component.literal("Gain: $gain").withStyle(net.minecraft.ChatFormatting.DARK_AQUA))
        tooltip.add(
            Component.literal("Slot into a Radio Transmitter / Receiver")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY),
        )
        super.appendHoverText(stack, context, tooltip, flag)
    }
}
