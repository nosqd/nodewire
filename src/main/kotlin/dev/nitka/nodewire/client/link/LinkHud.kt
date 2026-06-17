package dev.nitka.nodewire.client.link

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValueConversion
import dev.nitka.nodewire.item.ChannelLinkToolItem
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinPorts
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Client-side state for the Channel Link Tool's inline pin picker — the small
 * hover window ([LinkHudRenderer]) that replaces the old full-screen pickers.
 *
 * Every client tick [update] raycasts the crosshair, enumerates the targeted
 * block's pins through [PinPorts], and tags each row **active** or **inactive**
 * for the current phase:
 *
 *  * No source armed → the block's OUTPUT pins are active (arm candidates);
 *    its inputs show greyed.
 *  * A source armed → the INPUT pins that the source type converts into are
 *    active (the same [PinValueConversion] check the bind packet validates);
 *    incompatible inputs and all outputs show greyed.
 *
 * The scroll wheel ([scroll]) moves the highlight across **active rows only**;
 * inactive rows are visible but never selectable. RMB on the tool acts on
 * [highlightedPin]. All state is render-thread/client-tick local — nothing here
 * crosses the network.
 */
object LinkHud {

    /** One pin row in the window. [output] drives the in/out tag; [active]
     *  decides selectability + brightness. */
    data class Row(val pin: LinkPin, val output: Boolean, val active: Boolean)

    var targetPos: BlockPos? = null
        private set
    var rows: List<Row> = emptyList()
        private set
    var highlight: Int = -1
        private set

    /** Header info: the armed source's label/type, or null while arming. */
    var armedLabel: String? = null
        private set
    var armedType: PinType? = null
        private set

    /** True when the crosshair is on the armed source's own block (no self-link). */
    var sameAsSource: Boolean = false
        private set

    private var lastPos: BlockPos? = null

    /** Recompute from the crosshair + tool state. Call once per client tick. */
    fun update() {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return clear()
        val player = mc.player ?: return clear()
        val level = mc.level ?: return clear()
        val stack = player.mainHandItem
        if (stack.item !is ChannelLinkToolItem) return clear()
        if (ChannelLinkToolItem.readMode(stack) != ChannelLinkToolItem.Mode.LINK) return clear()
        val hit = mc.hitResult as? BlockHitResult ?: return clear()
        if (hit.type != HitResult.Type.BLOCK) return clear()

        val pos = hit.blockPos
        val face = hit.direction
        val armed = ChannelLinkToolItem.readArmedSource(stack)
        armedLabel = armed?.label
        armedType = armed?.type
        sameAsSource = armed != null && armed.source.payload.blockPos == pos

        val port = PinPorts.at(level, pos, face)
        if (port == null) return clear()
        val ctx = LinkContext(level, pos, level.getBlockState(pos), face)
        val outs = port.pinOutputs(ctx)
        val ins = port.pinInputs(ctx)
        val newRows = buildList {
            if (armed == null) {
                // Picking a source → show only this block's OUTPUT pins.
                outs.forEach { add(Row(it, output = true, active = true)) }
            } else {
                // Picking a target → show only INPUT pins; incompatible ones
                // stay visible but inactive (greyed) so you see why they can't
                // be chosen, and the scroll skips them.
                ins.forEach {
                    add(Row(it, output = false, active = !sameAsSource && PinValueConversion.canConvert(armed.type, it.type)))
                }
            }
        }

        // Reset the highlight to the first active row whenever the targeted
        // block changes; otherwise keep the player's scroll position.
        if (pos != lastPos) {
            lastPos = pos
            highlight = newRows.indexOfFirst { it.active }
        }
        rows = newRows
        targetPos = pos
        if (highlight !in rows.indices || !rows[highlight].active) {
            highlight = rows.indexOfFirst { it.active }
        }
    }

    /** Move the highlight to the next/previous ACTIVE row (wraps). */
    fun scroll(dir: Int) {
        val actives = rows.indices.filter { rows[it].active }
        if (actives.isEmpty()) return
        val cur = actives.indexOf(highlight).coerceAtLeast(0)
        val next = Math.floorMod(cur + (if (dir > 0) 1 else -1), actives.size)
        highlight = actives[next]
    }

    /** The currently selectable pin, or null when nothing active is highlighted. */
    fun highlightedPin(): LinkPin? = rows.getOrNull(highlight)?.takeIf { it.active }?.pin

    /** Whether the window currently offers at least one selectable pin (gates
     *  whether plain scroll cycles pins vs. falls through to the hotbar). */
    fun hasActive(): Boolean = rows.any { it.active }

    fun clear() {
        targetPos = null
        rows = emptyList()
        highlight = -1
        armedLabel = null
        armedType = null
        sameAsSource = false
        lastPos = null
    }
}
