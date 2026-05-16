# Create Redstone Link Integration — Design Spec

Adds two new node types — `redstone_link_input` and `redstone_link_output` — that read from and write to Create's redstone-link network using item-pair frequencies (the same mechanism Create's own Redstone Link block uses). Frequencies are picked via JEI/EMI ghost-drag onto two slot widgets, or via an inline inventory popover.

## Goal

Let a Nodewire graph publish to and subscribe from Create's redstone-link network. Interoperates seamlessly with vanilla Create Redstone Link Receiver/Transmitter blocks on the same frequency, and with other Nodewire blocks running the same node type.

## Architecture

Both new nodes are regular `NodeType` entries in `StockNodeTypes`, registered unconditionally so saved graphs containing them survive a temporary Create absence. Their evaluators check `ModList.isLoaded("create")` at runtime and no-op when Create is missing.

A new `dev.nitka.nodewire.integration.create.CreateRedstoneLink` file isolates all Create API imports — `Frequency`, `Couple<Frequency>`, `RedstoneLinkNetworkHandler`, `IRedstoneLinkable`. Without Create, the file's classes are not loaded by anything, so core compiles+runs without Create.

## Node types

```kotlin
val REDSTONE_LINK_INPUT = nodeType(
    id = "redstone_link_input",
    displayName = "Redstone Link Input",
    category = NodeCategory.IO,
    outputs = listOf(Pin("out", "Signal", PinType.REDSTONE)),
    defaultConfig = { CompoundTag().apply {
        put("freq1", ItemStack.EMPTY.save(CompoundTag()))
        put("freq2", ItemStack.EMPTY.save(CompoundTag()))
    } },
    configContent = NodeConfigContent.RedstoneLinkFrequency,
    evaluate = StockEvaluators.RedstoneLinkInput,
)
val REDSTONE_LINK_OUTPUT = nodeType(
    id = "redstone_link_output",
    displayName = "Redstone Link Output",
    category = NodeCategory.IO,
    inputs = listOf(Pin("in", "Signal", PinType.REDSTONE)),
    defaultConfig = { /* same freq1+freq2 shape */ },
    configContent = NodeConfigContent.RedstoneLinkFrequency,
    evaluate = StockEvaluators.RedstoneLinkOutput,
)
```

**Frequency storage:** two `CompoundTag` entries in node config (`freq1`, `freq2`), each a serialized `ItemStack` via `ItemStack.save(CompoundTag())`. Empty stack is a legal frequency slot (Create supports this — empty pair is the "default frequency").

## ConfigContent UI

New `NodeConfigContent.RedstoneLinkFrequency` composable. Two 18×18 ghost slots side-by-side, captioned "Frequency". Each slot:

- **Renders** the current `ItemStack` sprite + count, or empty placeholder.
- **Hover** → tooltip with the item's display name.
- **LMB** → opens an inline popover (described below).
- **RMB** → clears the slot to `ItemStack.EMPTY`.
- **JEI/EMI ghost drag target** → accepts a drop, sets the slot.

### Inline inventory popover

Triggered by LMB on a slot. Renders on top of the node editor via existing `OverlayHost` / `OverlayState`:

- Search input at the top (filters by display-name substring, case-insensitive).
- Grid 9×4 (36 visible) of player-inventory items (unique items only — duplicates collapsed). Scroll/arrows if more.
- Click an item → set slot → close popover.
- Esc / outside click → close.

### JEI/EMI integration

Two new files isolate the optional-mod handlers:

- `client/ghost/JeiGhostHandler.kt` — implements `IGhostIngredientHandler<NodeEditorScreen>`; `getTargets(stack, doStart)` walks the layout tree for live `RedstoneLinkFrequency` slot rects and returns `Target<ItemStack>` wrappers whose `accept(stack)` mutates the slot.
- `client/ghost/EmiGhostHandler.kt` — same pattern for EMI's drag-drop interface.

Both register via `@JeiPlugin` / EMI's plugin entry point. Without the relevant mod, the file isn't loaded; with it loaded, the handler is auto-discovered.

### Frequency persistence

No new packet. Freq changes mutate `node.config` client-side; `NodeEditorScreen.removed()` already sends `SaveGraphPacket(pos, currentGraph)` on close, which round-trips the whole graph including new freq slots. (Existing flow per `NodeEditorScreen.kt:91`.)

## Server-side integration

All Create-API contact lives in `integration/create/CreateRedstoneLink.kt`:

```kotlin
object CreateRedstoneLink {
    fun frequencyOf(cfg: CompoundTag): Couple<Frequency> {
        val s1 = ItemStack.of(cfg.getCompound("freq1"))
        val s2 = ItemStack.of(cfg.getCompound("freq2"))
        return Couple.create(Frequency.of(s1), Frequency.of(s2))
    }

    fun strongestSignal(level: Level, freq: Couple<Frequency>): Int { /* query handler */ }

    class NodeLinkable(
        private val be: LogicBlockEntity,
        private val nodeId: UUID,
        @Volatile var freq: Couple<Frequency>,
        @Volatile var lastTransmit: Int = 0,
    ) : IRedstoneLinkable {
        override fun getNetworkKey() = freq
        override fun getTransmittedStrength() = lastTransmit
        override fun setReceivedStrength(value: Int) {}
        override fun isAlive() = !be.isRemoved
        override fun getLocation() = be.blockPos
    }
}
```

### Input node tick path

In `LogicBlockEntity.serverTick`, when collecting `external` inputs, additionally:

```kotlin
"redstone_link_input" -> {
    if (!ModList.get().isLoaded("create")) continue
    val freq = CreateRedstoneLink.frequencyOf(node.config)
    val signal = CreateRedstoneLink.strongestSignal(level, freq)
    external[node.id to "out"] = PinValue.Redstone(signal)
}
```

### Output node tick path

After `eval.tick(...)`, before the channel-binding propagation:

```kotlin
if (ModList.get().isLoaded("create")) updateRedstoneLinkOutputs(level, result)
```

`updateRedstoneLinkOutputs` maintains a transient `MutableMap<UUID, NodeLinkable>` on the BE. For each `redstone_link_output` node:

1. Compute desired `freq` from config.
2. If no entry exists, create + register a `NodeLinkable` with `RedstoneLinkNetworkHandler` for this level.
3. If existing entry's `freq` differs from desired, unregister + register fresh.
4. Set `linkable.lastTransmit = redstoneOf(incomingEdgeValue)` and call `networkHandler.updateNetworkOf(level, linkable)` so the mesh recomputes its max.

After the per-node loop, also unregister stale linkables whose `nodeId` no longer exists in the graph (user deleted the node).

### Cleanup

`LogicBlockEntity.setRemoved()` walks the linkables map and unregisters all of them (Create's handler also drops dead linkables via `isAlive()`, but explicit cleanup avoids a one-tick stale signal).

The linkables map is transient (no NBT save). On chunk reload the BE rebuilds entries from the graph on its next tick — Create's network is rebuilt from registrations, no persisted state needed.

## Cross-frame behaviour

`RedstoneLinkNetworkHandler.getNetworkOf(level, freq)` is per-`Level`. Ship blocks live in the same `Level` (in shipyard regions). So a Nodewire block on a ship and one in the world freely transmit/receive on the same frequency, mirroring Create's own redstone-link block behaviour. No backend touching needed.

## Edge cases

- **Empty frequency:** `Frequency.of(ItemStack.EMPTY)` is Create's "default" frequency. Multiple uninitialized link nodes all sit on it — same as vanilla Create blocks.
- **Frequency change at runtime:** server re-tick detects diff, re-registers linkable on the new freq.
- **Create removed:** evaluator no-ops, linkables never created, UI shows a warning icon by the slots ("Create not loaded"). Saved freq config preserved.
- **JEI not installed AND EMI not installed:** ghost-drag does nothing; inline popover still works (uses player inventory only).
- **Node deleted while running:** server tick scans `linkables.keys - currentGraph.nodes.keys` and unregisters orphans.

## Manual test plan

1. Two LogicBlocks. First: `redstone_link_output` set to (`diamond`, `emerald`) + `constant 15` → `out` edge to output. Second: `redstone_link_input` with same freq, output via existing `channel_*` or `side_output` to a vanilla lamp.
2. Place a vanilla Create Redstone Link Receiver with the same freq → it should also receive 15.
3. Change freq on either side → signal drops to 0 on the other.
4. Open editor on a logic block, drag an item from JEI panel onto a freq slot → slot updates.
5. RMB freq slot → cleared.
6. Place logic blocks on opposite ends of a large render-distance area; signal still passes (network is per-Level, not per-chunk).
7. Remove Create from mods, reload world → bindings preserved, no crash, warning icon visible.

## Out of scope (separate sub-projects)

- **Tweaked Controllers input** — separate spec follows.
- **JEI focused-recipe lookup** from freq slot (R/U key while hovering) — nice-to-have follow-up.
- **Per-node "active transmitters: N" tooltip** — follow-up.

## File layout

**New:**
- `src/main/kotlin/dev/nitka/nodewire/integration/create/CreateRedstoneLink.kt` — all Create API contact (`frequencyOf`, `strongestSignal`, `NodeLinkable`, network register/unregister/update helpers).
- `src/main/kotlin/dev/nitka/nodewire/client/screen/RedstoneLinkSlotPicker.kt` — inline popover composable + overlay state.
- `src/main/kotlin/dev/nitka/nodewire/client/ghost/JeiGhostHandler.kt` — JEI `IGhostIngredientHandler` plugin.
- `src/main/kotlin/dev/nitka/nodewire/client/ghost/EmiGhostHandler.kt` — EMI drag-drop plugin.

**Modified:**
- `graph/StockNodeTypes.kt` — register `REDSTONE_LINK_INPUT` + `REDSTONE_LINK_OUTPUT`.
- `graph/StockEvaluators.kt` — `RedstoneLinkInput` + `RedstoneLinkOutput` evaluators (gated on `isLoaded`).
- `block/LogicBlockEntity.kt` — input read path inside `serverTick` external collection; output path after eval; transient `linkables: MutableMap<UUID, NodeLinkable>` field; cleanup in `setRemoved`.
- `client/screen/NodeConfigContent.kt` — add `RedstoneLinkFrequency` sealed variant + slot rendering composable.

## Decisions log

- **JEI/EMI ghost drag + inline inventory popover.** Both work; one for power users, one as fallback / for non-JEI users.
- **Unconditional node-type registration.** Saved graphs containing these nodes load fine even without Create.
- **Per-(BE, nodeId) `NodeLinkable`.** Cleanest: each output node = one network participant; lifecycle tied to node + BE existence.
- **No new packet for freq changes.** `SaveGraphPacket` already round-trips the entire graph on editor close.
- **Empty `ItemStack` is a valid freq slot.** Matches Create's own semantics.
