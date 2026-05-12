# Nodewire — Node Editor MVP Design

**Date:** 2026-05-13
**Status:** Draft
**Scope:** GUI-first vertical slice. Editable typed node graph stored in `logic_block`'s BlockEntity. **Execution / ticking is out of scope** — graph holds data only. Cross-block linking and Create/VS integration also out of scope (separate specs).

---

## Purpose

Prove the editor UX end-to-end: place a `logic_block`, right-click → open Blueprint-style canvas, drag nodes from a palette, wire them with typed connections, save → graph persists in BlockEntity NBT and re-opens identically.

---

## Out of scope

- Logic execution / per-tick evaluation
- Cross-block wires (`Link` capability, ShipId-aware)
- Create rotation/stress integration
- VS ship-space coordinate handling
- Comments, groups, sub-graphs
- Undo/redo (deferred to next slice)
- JEI/EMI recipe display for nodes

---

## Architecture

```
Server (authoritative)              Client
──────────────────                  ──────
LogicBlockEntity                    NodeEditorScreen
  graph: NodeGraph        <───┐         graph: NodeGraph  (local copy)
  save/load NBT               │         renderer: CanvasRenderer
                              │         input: pan/zoom/drag
  C→S: SaveGraphPacket    ────┘         S→C: open via BE sync
```

**Authority model:** Server owns the canonical graph. Client opens GUI with the BE's synced graph data, makes local edits, sends one `SaveGraphPacket` on screen close. No incremental sync (MVP). This keeps networking trivial — one round trip per editing session.

---

## Type system

Pin types form a closed enum. Connections require **exact type match** (no implicit coercion in MVP).

| Type id | Kind | Wire color | Default value |
|---|---|---|---|
| `BOOL` | primitive | red | `false` |
| `INT` | primitive | cyan | `0` |
| `FLOAT` | primitive | yellow | `0.0` |
| `VEC2` | composite | green | `(0,0)` |
| `VEC3` | composite | lime | `(0,0,0)` |
| `QUAT` | composite | purple | identity |

Each type has a Kotlin sealed class `PinValue` with NBT (de)serializers. Adding a new type means: enum entry, `PinValue` subclass, NBT serializer, wire color.

**Why exact match:** Implicit conversions create surprises ("why is my Vec3 collapsing to int?"). User can place an explicit `ToFloat` / `ToVec3` conversion node later. YAGNI for MVP.

---

## Node model

```kotlin
sealed class Node {
    abstract val id: NodeId            // UUID
    abstract val type: NodeType        // registry key (e.g. "nodewire:and")
    abstract val pos: Vec2             // canvas position
    abstract val inputs: List<Pin>     // typed input pins
    abstract val outputs: List<Pin>    // typed output pins
    open val config: CompoundTag = CompoundTag()  // per-node settings (e.g. constant value)
}

data class Pin(
    val id: PinId,
    val name: String,
    val type: PinType,
)

data class Edge(
    val from: PinRef,  // (nodeId, pinId)
    val to: PinRef,
)

class NodeGraph {
    val nodes: MutableMap<NodeId, Node>
    val edges: MutableList<Edge>
    fun toNbt(): CompoundTag
    companion object { fun fromNbt(tag: CompoundTag): NodeGraph }
}
```

**NodeType registry** is a `DeferredRegister<NodeType>` keyed by `ResourceLocation`. Each `NodeType` defines:
- display name
- pin layout (input/output pins with names + types)
- factory (creates instance with default config + position)
- category for palette grouping (`Logic`, `Math`, `IO`, `Constants`)

This makes the system data-driven — new node types are just registry entries, no GUI code changes.

---

## MVP node types

| Registry id | Category | Inputs | Outputs | Notes |
|---|---|---|---|---|
| `nodewire:block_input` | IO | — | `out: BOOL` (per side) | 6 outputs, one per face. Placeholder for external world input. |
| `nodewire:block_output` | IO | `in: BOOL` (per side) | — | 6 inputs, one per face. Placeholder for external world output. |
| `nodewire:and` | Logic | `a, b: BOOL` | `out: BOOL` | |
| `nodewire:or` | Logic | `a, b: BOOL` | `out: BOOL` | |
| `nodewire:not` | Logic | `in: BOOL` | `out: BOOL` | |
| `nodewire:bool_const` | Constants | — | `out: BOOL` | config: value |
| `nodewire:int_const` | Constants | — | `out: INT` | config: value |
| `nodewire:float_const` | Constants | — | `out: FLOAT` | config: value |
| `nodewire:vec3_const` | Constants | — | `out: VEC3` | config: x,y,z |
| `nodewire:timer` | Constants | `period: INT` | `out: BOOL` | config: period (ticks). No execution in MVP — purely declarative. |
| `nodewire:add_int` | Math | `a, b: INT` | `out: INT` | |
| `nodewire:add_float` | Math | `a, b: FLOAT` | `out: FLOAT` | |
| `nodewire:add_vec3` | Math | `a, b: VEC3` | `out: VEC3` | |
| `nodewire:compare_int` | Math | `a, b: INT` | `gt, eq, lt: BOOL` | three outputs |

13 node types — proves type system handles primitive + composite + multi-output.

---

## Screen / canvas

**Layer order (back→front):**
1. Background (dark grid)
2. Edges (bezier curves, colored by source pin type)
3. Nodes (rounded rect with title bar + pin rows)
4. Drag-preview edge (when dragging from a pin)
5. Palette overlay (left side)

**Interactions:**
- Pan: middle-mouse drag or space+drag
- Zoom: scroll wheel (clamped 0.25× – 3×)
- Add node: click palette entry → node spawns at viewport center
- Move node: left-drag on node body
- Delete node: select + Delete key
- Wire: drag from output pin → release on input pin of compatible type
- Cancel wire: release on empty canvas
- Delete edge: hover edge + right-click (single click, no confirmation)
- Save & close: ESC or close icon → `SaveGraphPacket` sent

**No undo/redo in MVP.** Closing without saving discards changes (confirm dialog if dirty).

**Canvas coords vs screen coords:** Store node positions in canvas space (unbounded float). Screen-space is canvas × zoom + pan offset. All hit-testing in canvas space.

---

## Persistence

`LogicBlockEntity` extends `BlockEntity`:
- `graph: NodeGraph`
- `saveAdditional(tag)`: `tag.put("graph", graph.toNbt())`
- `loadAdditional(tag)`: `graph = NodeGraph.fromNbt(tag.getCompound("graph"))`
- `getUpdateTag()` / `handleUpdateTag()`: full graph (for initial chunk sync)
- `getUpdatePacket()`: null (no incremental sync in MVP — GUI session reads from BE on open)

NBT layout:
```
graph: CompoundTag
  nodes: ListTag<CompoundTag>
    - id: UUID
      type: ResourceLocation as String
      pos: { x: float, y: float }
      config: CompoundTag (type-specific)
  edges: ListTag<CompoundTag>
    - from: { node: UUID, pin: String }
    - to:   { node: UUID, pin: String }
```

---

## Networking

Single packet (server-bound):

```kotlin
class SaveGraphPacket(val pos: BlockPos, val graphNbt: CompoundTag) {
    // handler: server-side
    //   1. validate sender is within 8 blocks of pos
    //   2. validate BlockEntity at pos is LogicBlockEntity
    //   3. parse NBT → NodeGraph; reject if invalid (logs warning, no crash)
    //   4. BE.graph = parsed; BE.setChanged(); level.sendBlockUpdated()
}
```

**Open flow:**
1. Client right-clicks block (server-side `use` returns `InteractionResult.SUCCESS`)
2. Server-side `use` calls `NetworkHooks.openScreen(player, BE)` with `BlockPos` in extra data
3. Client receives screen-open, requests fresh graph by reading BE via `level.getBlockEntity(pos)` (already synced via `getUpdateTag`)
4. Screen instantiates with a deep copy of graph for local editing

**No incremental sync:** All editing is local until save. This is OK because (a) MVP has no concurrent editors, (b) packet is small (<1 KB for hundreds of nodes), (c) simplifies state model.

---

## Validation

Server validates on `SaveGraphPacket`:
- All `Edge.from`/`to` reference existing nodes + pins
- Edge types match (output pin type == input pin type)
- No duplicate edges to the same input pin (1 input = 1 source max in MVP)
- No cycles (depth-first walk; reject if cycle detected)

**Why server-side validation:** Client could be malicious or buggy. Cycles will matter when execution is added.

Client UX hint: drag-target pin highlights green if compatible, red if not, before release. No need to bother user with cycle detection in UI — server rejects on save with chat error message.

---

## File structure

```
src/main/kotlin/dev/nitka/nodewire/
  Nodewire.kt
  Registry.kt
  block/
    LogicBlock.kt              # use() opens screen
    LogicBlockEntity.kt        # graph storage
  graph/
    PinType.kt                 # enum + NBT codecs
    PinValue.kt                # sealed class hierarchy
    Pin.kt
    PinRef.kt
    Edge.kt
    Node.kt
    NodeType.kt                # registry
    NodeGraph.kt
    NodeTypes.kt               # DeferredRegister + all 13 type registrations
  graph/types/
    AndNode.kt, OrNode.kt, NotNode.kt
    ConstNodes.kt              # Bool/Int/Float/Vec3 constants
    TimerNode.kt
    MathNodes.kt               # Add*, CompareInt
    IoNodes.kt                 # BlockInput, BlockOutput
  net/
    NodewireNetwork.kt         # SimpleChannel setup
    SaveGraphPacket.kt
  client/
    NodewireClient.kt          # @Mod.EventBusSubscriber(Dist.CLIENT) registers screens
    screen/
      NodeEditorScreen.kt      # main Screen subclass
      CanvasRenderer.kt        # background grid, nodes, edges, drag preview
      Palette.kt               # left-side node picker
      Camera.kt                # pan/zoom state, coord conversion
      Theme.kt                 # colors, dimensions
```

---

## Acceptance criteria

- **AC1**: Place logic_block, right-click → editor opens
- **AC2**: Palette shows all 13 node types grouped by category
- **AC3**: Click palette entry → node appears at canvas center; drag moves it
- **AC4**: Drag from BOOL output → BOOL input creates a wire (red); type-mismatched drag shows red highlight and rejects
- **AC5**: Pan (middle-drag) and zoom (scroll) work, node positions follow correctly
- **AC6**: Close screen → reopen → graph identical (nodes, positions, edges)
- **AC7**: World save → reload → graph identical
- **AC8**: Server log shows no errors; client log shows no mixin/registry warnings
- **AC9**: Two clients in multiplayer can each open editor on different logic_blocks; saves don't interfere

---

## Why this scope

This slice intentionally **avoids execution** so we can iterate on the editor UX without coupling it to logic-engine bugs. Once the editor is solid:
- Next slice: in-block execution (single-tick evaluation, no cross-block)
- Slice after: cross-block wires + ShipId-aware linking
- Then: Create rotation/stress in/out adapters

This order keeps each slice independently testable.
