# Tweaked Controller integration + Vector toolkit — Design

**Date:** 2026-05-17
**Status:** Approved
**Scope:** Add a comprehensive vector-math node library (using existing `VEC2`/`VEC3` pin types) and a Tweaked Controller (TC) integration that lets a Logic Block read game-controller input from a bound controller item.

---

## Motivation

Two related needs:

1. **Vector toolkit.** `PinType.VEC2`/`VEC3` already exist with full codec support, but no composables let users *use* them — there are no compose/decompose nodes, no math, no constants. Sticks on a controller output Vec2 naturally, so vector support is a prerequisite for meaningful controller integration.
2. **Controller input.** Players want to drive in-world contraptions (ships, machines) with a physical gamepad via the Tweaked Controller mod. Today nothing in the graph reads gamepad state; the only inputs are vanilla redstone, BlockInput endpoints, and Create redstone links.

The user experience target is the Drive-By-Wire hub pattern: hold the controller item, RMB the Logic Block, that specific item is now bound to that block. No menus, no GUI, no item-pair frequencies.

---

## Architecture overview

```
┌────────────────────────────────────────────┐
│ Sub-project A: Vector toolkit              │
│   3 new node types                         │
│   1 CONSTANT slot extension                │
│   Pure-data ops, no external deps          │
└────────────────────────────────────────────┘
                  ▼ uses
┌────────────────────────────────────────────┐
│ Sub-project B: Tweaked Controller          │
│   1 new node type (controller_input)       │
│   LogicBlockEntity gains controllerId      │
│   RightClickBlock event for binding        │
│   Soft-dep on TC (graceful when missing)   │
└────────────────────────────────────────────┘
```

Two sub-projects, two implementation plans. A ships first (no mod risk), B depends on A only indirectly (a Stick channel's Vec2 output is most useful with vec_split).

---

## Sub-project A: Vector toolkit

### A.1 Pin types

`PinType.VEC2` and `PinType.VEC3` already exist in `graph/PinType.kt`. `PinValue.Vec2`/`Vec3` already exist in `graph/PinValue.kt` with full codecs. **No changes to pin types.**

Wire colors `pinVec2` / `pinVec3` already defined in `NwColors`.

### A.2 Node `vec_make`

Compose scalars → vector. Polymorphic over dimension.

- **Config:** `dim: "VEC2" | "VEC3"`, default `VEC2`.
- **Inputs:** `x:FLOAT`, `y:FLOAT`, plus `z:FLOAT` when `dim=VEC3`.
- **Outputs:** `out:VEC2` or `out:VEC3` per `dim`.
- **Pin reshape:** changing `dim` adds/removes the `z` input pin and changes the output pin type. Stale edges on dropped pins are removed.
- **Default config:** `dim=VEC2`.
- **Category:** `NodeCategory.VECTOR`.

### A.3 Node `vec_split`

Decompose vector → scalars. Inverse of `vec_make`.

- **Config:** `dim: "VEC2" | "VEC3"`.
- **Inputs:** `in:VEC2` or `in:VEC3`.
- **Outputs:** `x:FLOAT, y:FLOAT` or `x:FLOAT, y:FLOAT, z:FLOAT`.
- **Pin reshape:** same rules as `vec_make`.
- **Category:** `NodeCategory.VECTOR`.

### A.4 Node `vec_op`

Universal vector operation. One node type, `op` + `dim` config; pin shape morphs per op. Mirrors the existing `Math` / `Compare` node pattern.

**Config:**
- `op: VecOp` — enum below.
- `dim: "VEC2" | "VEC3"` — auto-locked when op is dimension-specific.

**Op catalog** (each row = (op, input pins, output pins)):

| Op | Inputs | Output | Dim |
|---|---|---|---|
| `ADD` | a:V, b:V | out:V | configurable |
| `SUB` | a:V, b:V | out:V | configurable |
| `MUL_COMPONENT` | a:V, b:V | out:V | configurable |
| `MIN` | a:V, b:V | out:V (componentwise) | configurable |
| `MAX` | a:V, b:V | out:V (componentwise) | configurable |
| `NEGATE` | v:V | out:V | configurable |
| `NORMALIZE` | v:V | out:V (zero stays zero) | configurable |
| `ABS` | v:V | out:V (componentwise) | configurable |
| `SCALE` | v:V, s:FLOAT | out:V | configurable |
| `CLAMP_MAG` | v:V, max:FLOAT | out:V | configurable |
| `LERP` | a:V, b:V, t:FLOAT | out:V | configurable |
| `PROJECT` | a:V, b:V | out:V (a onto b) | configurable |
| `REFLECT` | v:V, n:V | out:V | configurable |
| `DOT` | a:V, b:V | out:FLOAT | configurable |
| `LENGTH` | v:V | out:FLOAT | configurable |
| `LENGTH_SQ` | v:V | out:FLOAT | configurable |
| `DISTANCE` | a:V, b:V | out:FLOAT | configurable |
| `ANGLE` | a:V, b:V | out:FLOAT (radians) | configurable |
| `CROSS` | a:VEC3, b:VEC3 | out:VEC3 | **locked VEC3** |
| `ROTATE2D` | v:VEC2, angle:FLOAT | out:VEC2 | **locked VEC2** |
| `TO_VEC3` | v:VEC2, z:FLOAT | out:VEC3 | dim ignored |
| `TO_VEC2` | v:VEC3 | out:VEC2 (drops z) | dim ignored |

Total: 22 operations.

**Pin reshape:** every change of `op` or `dim` recomputes the input/output pin list; edges whose endpoint type changed are dropped (existing mechanism for `Math`/`Convert`).

**Category:** `NodeCategory.VECTOR`.

**Default config:** `op=ADD`, `dim=VEC2`.

### A.5 Edge cases

- `LENGTH(0,0)` → `0`. `NORMALIZE(0,0)` → `(0,0)`. No NaN propagation.
- `ANGLE` between any vector and the zero vector → `0` (not `acos` NaN).
- `PROJECT a onto 0` → `(0,0,0)`. `REFLECT` against zero normal → `v` unchanged.
- `DISTANCE` is symmetric; equal vectors → `0` (not −0 from float rounding).
- `CROSS` always returns VEC3 regardless of `dim` config (config UI locks dim=VEC3 for this op).
- `TO_VEC3` / `TO_VEC2` ignore the `dim` config — their I/O types are fixed by the op name.

### A.6 CONSTANT node extension

Existing `CONSTANT` node in `StockNodeTypes` already supports INT/FLOAT/BOOL/STRING via a `slot` enum. Extend:

- New slot values: `VEC2`, `VEC3`.
- Config UI: 2 (or 3) `TextInput`s for components, like the existing FLOAT slot.
- Output pin type set to VEC2/VEC3 accordingly; pin reshape on slot change (already wired).
- Default value: zero vector.

### A.7 UI sections (in `NodeConfigContent`)

- `VecMake`: `dim` `Select`. Same select pattern as existing `MathType`.
- `VecSplit`: `dim` `Select`.
- `VecOp`: `op` `Select` (grouped visually: Binary→Unary→Scalar-mixed→Reductions→Dim-specific→Conversions), `dim` `Select` (disabled when op is dim-locked).
- `Constant`: extend to render two/three FLOAT inputs when slot=VEC2/VEC3.

### A.8 Files (sub-project A)

**New:**
- `graph/nodetypes/VectorNodes.kt` — `VEC_MAKE`, `VEC_SPLIT`, `VEC_OP` NodeType definitions, `VecOp` enum, `VecDim` helper.
- `graph/evaluators/VectorEvaluators.kt` — `VecMakeEval`, `VecSplitEval`, `VecOpEval` (one big `when(op)`).
- `src/test/kotlin/dev/nitka/nodewire/graph/VectorEvaluatorsTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/graph/VecOpPinReshapeTest.kt`

**Modified:**
- `graph/StockNodeTypes.kt` — register new types; extend CONSTANT slot enum + default factory.
- `graph/NodeCategory.kt` — add `VECTOR`.
- `client/screen/NodeCard.kt` — header color for VECTOR (purple).
- `client/screen/NodeConfigContent.kt` — add `VecMake`, `VecSplit`, `VecOp`, extend `Constant`.
- `client/screen/EditorState.kt` — add `changeVecOp(nodeId, op)`, `changeVecDim(nodeId, dim)` mutators; both wrap `mutateGraph`.
- `assets/nodewire/lang/en_us.json` — display names.

---

## Sub-project B: Tweaked Controller integration

### B.1 Dependency

- Declared as `modCompileOnly` / `modRuntimeOnly` in `build.gradle.kts` (Modrinth maven, project slug per TC's mod id).
- Runtime check via `ModList.get().isLoaded("tweakedcontrollers")` (resolve actual modid at impl time).
- **Soft:** Nodewire must build and run with TC absent. `controller_input` node remains in the registry; evaluator returns `PinValue.default(...)` for each output pin when TC isn't loaded or no controller is bound.

### B.2 LogicBlockEntity changes

- New field: `controllerId: UUID?` (nullable, default `null`).
- NBT key: `controllerId` (UUID `LongArray`, present iff bound).
- Getter/setter; setter triggers `setChanged()` + block update.
- `SetControllerIdPacket` (S→C) — server pushes updates so client toolbar reflects bound state. Modeled on existing `SetBlockNamePacket`.

### B.3 Binding gesture (Drive-By-Wire-style)

Subscribe to `PlayerInteractEvent.RightClickBlock` on the MOD bus:

```
on RightClickBlock(player, block, item):
    if block !is LogicBlock: return
    if !TC.isLoaded(): return  // fallback to vanilla editor flow
    val cid = TC.controllerItemId(item) ?: return  // not a controller item
    val be = block as LogicBlockEntity
    if player.isShiftKeyDown:
        be.controllerId = null   // unbind
        chat: "Controller unbound"
    else:
        be.controllerId = cid
        chat: "Controller bound (id=${cid.short()})"
    event.useBlock = DENY  // suppress editor open
```

- Empty hand or non-controller item → event passes through → vanilla RMB opens editor (current behavior, unchanged).
- Re-binding with a different controller item: silently replaces previous.

### B.4 Channel node `controller_input`

**Config:**
- `channel: ControllerChannel` — see catalog below.
- `outputMode: OutputMode` — variants depend on channel category.
- `deadzone: Float` (used when category is Stick or Trigger and outputMode is Bool/Redstone), 0.0..1.0, default 0.15.
- `invert: Bool` (used when category is Trigger or for sign-flip on axes), default false.

**Channel catalog:**

| Category | Channels |
|---|---|
| Stick | `LEFT_STICK`, `RIGHT_STICK` |
| Trigger | `LEFT_TRIGGER`, `RIGHT_TRIGGER` |
| Button | `BUTTON_A`, `BUTTON_B`, `BUTTON_X`, `BUTTON_Y`, `LEFT_BUMPER`, `RIGHT_BUMPER`, `LEFT_STICK_CLICK`, `RIGHT_STICK_CLICK`, `START`, `BACK` |
| D-Pad single | `DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT` |
| D-Pad composite | `DPAD` (synthesises a Vec2: x=right−left, y=up−down, each ∈ {−1,0,1}) |

Channels are stored as enum names in NBT. Adding new channels later appends entries.

**OutputMode catalog per category:**

| Category | OutputMode | Output pins |
|---|---|---|
| Stick / DPad-composite | `VEC2_RAW` | `xy:VEC2` (each component −1..1) |
| | `XY_RAW` | `x:FLOAT, y:FLOAT` (−1..1) |
| | `XY_REDSTONE` | `x:REDSTONE, y:REDSTONE` (mapped `(axis+1)/2*15`, rounded) |
| | `MAGNITUDE_BOOL` | `pressed:BOOL` (`length > deadzone`) |
| Trigger | `RAW` | `value:FLOAT` (0..1) |
| | `REDSTONE` | `value:REDSTONE` (`value*15`, rounded) |
| | `BOOL` | `pressed:BOOL` (`value > deadzone`) |
| Button / DPad-single | `BOOL` | `pressed:BOOL` |
| | `REDSTONE` | `value:REDSTONE` (0 or 15) |

**Pin reshape:** changing channel may change category, which may force outputMode to a category-appropriate default (e.g. switching from Stick to Button forces outputMode → BOOL). Output pins recompute on every change of channel or outputMode.

### B.5 Evaluation

`controller_input` evaluator on each tick:

1. Read `controllerId` from the BE.
2. If null OR TC not loaded → emit `PinValue.default(pinType)` for every output pin. Return.
3. Query TC API: `TC.getControllerState(controllerId)` → `ControllerState?` (raw state for that controller item, even if no player is currently using it returns `null`).
4. If null → default values.
5. Read raw channel from state (e.g. `state.leftStickX`).
6. Apply `outputMode` mapping (deadzone, invert, redstone scaling).
7. Emit values on output pins.

**Open question (resolve in first impl task):** does TC replicate controller state to the server? If yes — server-side eval reads it directly. If no — Nodewire adds a per-tick `ControllerStatePacket` (client → server) sent by the holder of a controller item that's currently bound to *some* logic block. The plan's first task is a 30-min spike on TC's source to decide; both branches are scoped in the impl plan.

### B.6 Editor toolbar — controller indicator

Right of the block-name `TextInput`, add a small composable:

- When `controllerId == null` and TC loaded: muted text `Controller: (unbound)`.
- When bound: `Controller: <short-id-or-name>` + small `Unbind` button.
- When TC not loaded: `Controller: (TC not loaded)` (muted, no button).

Click `Unbind` → sends `SetControllerIdPacket(null)` to server.

### B.7 UI sections (controller node)

`NodeConfigContent.ControllerInput`:
- `channel` Select (grouped by category).
- `outputMode` Select (options morph with channel category).
- `deadzone` FLOAT slider (shown only if category=Stick or Trigger and outputMode involves a threshold).
- `invert` checkbox (shown for Trigger and individual-axis Stick modes).

### B.8 Files (sub-project B)

**New:**
- `integration/tweakedcontroller/TweakedController.kt` — soft-dep wrapper (`isLoaded`, `getControllerState`, `controllerItemId`).
- `integration/tweakedcontroller/ControllerChannel.kt` — enum + helpers (`category`, `allowedOutputModes`, `applyOutputMode`).
- `integration/tweakedcontroller/ControllerInputNode.kt` — NodeType registration + evaluator.
- `integration/tweakedcontroller/ControllerBindHandler.kt` — RightClickBlock event handler.
- `net/SetControllerIdPacket.kt` — sync packet.
- `src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerChannelTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/integration/tweakedcontroller/ControllerInputPinReshapeTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/block/LogicBlockEntityControllerBindTest.kt`

**Modified:**
- `block/LogicBlockEntity.kt` — controllerId field + NBT.
- `Nodewire.kt` — register bind handler on MOD bus when TC loaded.
- `net/NodewireNetwork.kt` — register `SetControllerIdPacket`.
- `client/screen/EditorToolbar.kt` — controller indicator.
- `client/screen/EditorState.kt` — `setControllerId(uuid?)` mutator + flow.
- `build.gradle.kts` — TC dependency, Modrinth maven if not already added.
- `assets/nodewire/lang/en_us.json` — channel names, output mode names.

---

## Cross-cutting

### Forward-compat & save migration

- Vec-related changes are purely additive (new node types, new CONSTANT slot values) — old saves load unchanged.
- `controllerId` is nullable; absent in old NBT → `null` on load.
- Channel/OutputMode enums saved by `name`; the existing `fromName(...) ?: <default>` pattern handles unknown values from forward-loaded saves.

### Categories & visual

- New `NodeCategory.VECTOR` with header color = accent purple (`0xFF_8C_5A_E8`, distinct from `MATH`'s blue-ish).
- `controller_input` uses `NodeCategory.IO` (same as `BLOCK_INPUT`) — fits semantically (external world input). No new category needed.

### Performance

- `vec_op` evaluator: single `when(op)` over an enum; no allocation in hot path beyond returning new `PinValue.Vec*` (already the case for FLOAT math).
- `controller_input` evaluator: one TC API call per node per tick. If many controller nodes per block share a controllerId, the TC call could be memoised per-tick (premature opt — measure first).
- Bind handler: only fires on RightClickBlock, negligible cost.

### Testing

**Unit (no MC bootstrap):**
- `VectorEvaluatorsTest` — every `VecOp` on Vec2 and Vec3 (where applicable), edge cases (zero, parallel, antiparallel, unit vectors).
- `VecOpPinReshapeTest` — change op/dim, pins reshape, incompatible edges dropped.
- `ControllerChannelTest` — `applyOutputMode` table-driven: deadzone below/above, invert, axis sign mapping, redstone rounding.
- `ControllerInputPinReshapeTest` — change channel/outputMode reshapes output pins.
- `LogicBlockEntityControllerBindTest` — NBT round-trip with controllerId set / null.

**In-game (manual checklist):**
- Vec roundtrip: CONSTANT(VEC3) → vec_split → vec_make → equality verified through downstream math.
- Each `vec_op` operation visually correct via in-game wiring.
- Bind controller (RMB with controller item) → toolbar shows id → stick produces VEC2 output reactive in real time.
- Shift+RMB → unbind → toolbar updates, channel node outputs zero.
- TC removed at runtime → mod loads, controller_input node spawnable, outputs zero, toolbar shows "TC not loaded".

### Success criteria

1. All 22 `vec_op` operations produce correct results (unit-tested).
2. `CONSTANT` with VEC2/VEC3 slot serialises NBT round-trip.
3. Bound controller drives `controller_input` outputs in real time. Verified for at least one channel per category (Stick, Trigger, Button, DPad-single, DPad-composite) and at least one outputMode per category.
4. Unbound block + controller nodes → no crashes, outputs are zero.
5. TC absent at runtime → mod loads, nodes spawnable, outputs zero, toolbar warns.

---

## Decomposition

Two sequential plans:

1. **`2026-05-17-vector-toolkit.md`** — sub-project A. Self-contained. Ship first.
2. **`2026-05-17-tweaked-controller-integration.md`** — sub-project B. Builds on A.

---

## Out of scope (YAGNI list)

- Vec4 type, color helpers.
- Vector swizzling (`xy`/`yz`/`xz` selectors as a dedicated node).
- Polar / Euler / quaternion ↔ vector conversions.
- Multi-controller per block, per-pin player override.
- TC rumble / LED / haptic output.
- Bind via in-editor UI (toolbar Bind button); binding is RMB-with-controller-item only.
- Auto-rebinding when controller item changes hands.
