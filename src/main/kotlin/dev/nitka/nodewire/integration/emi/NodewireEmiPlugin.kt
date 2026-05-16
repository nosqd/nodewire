package dev.nitka.nodewire.integration.emi

import dev.emi.emi.api.EmiEntrypoint
import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.EmiDragDropHandler
import dev.emi.emi.api.stack.EmiIngredient
import dev.nitka.nodewire.client.screen.NodeEditorScreen
import dev.nitka.nodewire.client.screen.RedstoneLinkSlotRegistry
import net.minecraft.world.item.ItemStack

/**
 * EMI integration: registers a drag-drop handler for [NodeEditorScreen]
 * that targets active redstone-link frequency slots via [RedstoneLinkSlotRegistry].
 *
 * Discovered automatically by EMI via the [@EmiEntrypoint] annotation at
 * runtime, so the plugin loads only when EMI is present.
 */
@EmiEntrypoint
class NodewireEmiPlugin : EmiPlugin {
    override fun register(registry: EmiRegistry) {
        registry.addDragDropHandler(NodeEditorScreen::class.java, NodeEditorDropHandler())
    }
}

private class NodeEditorDropHandler : EmiDragDropHandler<NodeEditorScreen> {
    override fun dropStack(
        screen: NodeEditorScreen,
        ingredient: EmiIngredient,
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        if (ingredient.isEmpty) return false
        val itemStack = ingredient.emiStacks.firstOrNull()?.itemStack ?: return false
        if (itemStack.isEmpty) return false
        for (slot in RedstoneLinkSlotRegistry.all()) {
            val withinX = mouseX in slot.screenX until slot.screenX + slot.width
            val withinY = mouseY in slot.screenY until slot.screenY + slot.height
            if (withinX && withinY) {
                slot.accept(itemStack.copy().also { it.count = 1 })
                return true
            }
        }
        return false
    }
}
