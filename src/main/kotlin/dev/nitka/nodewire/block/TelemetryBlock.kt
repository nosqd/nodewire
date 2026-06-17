package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * A telemetry probe. Place it on a Sable physics sub-level — a ship, an
 * Aeronautics aircraft, a Synaxis contraption — and it exposes that
 * structure's live physical state as Channel-Link output pins: world
 * position, linear velocity, orientation, yaw/pitch/roll, and angular
 * velocity (see [TelemetryBlockEntity]).
 *
 * Pure SOURCE in the unified-link model: it owns no graph, accepts no input
 * pins, and needs no ticker or BER. Consumers (a Logic Block channel, a Screen,
 * a script node) pull its pins each server tick through the
 * [dev.nitka.nodewire.link.PinPort] surface — so adding it required zero Link
 * Tool or packet changes. Off a sub-level it reports a static pose with zero
 * velocity, so it is safe to place anywhere.
 */
class TelemetryBlock(props: Properties) : Block(props), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        TelemetryBlockEntity(pos, state)
}
