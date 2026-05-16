# VS Ships Support — Design Spec

**Sub-project #1 of the cross-frame bindings effort.** Adds first-class support for LogicBlocks placed on Valkyrien Skies ships, including cross-ship and world↔ship channel bindings. Designed so that a future sub-project #2 (Create contraptions) plugs in without core changes.

## Goal

Allow LogicBlocks to live on VS ships and form channel bindings:
- world ↔ world (already works — must not regress)
- world ↔ ship
- ship ↔ ship (same ship or different ships)

Wire renderer must follow ships visually (lines stick to moving ships). Server-side signal propagation must work transparently across world/ship boundaries.

## Architecture — pluggable backends

The system is built around an open `EndpointBackend` registry instead of a closed sealed hierarchy, so that any future "block container" mod (Create contraptions, custom platforms, etc.) integrates by registering one class — no edits to core.

```kotlin
interface EndpointPayload                          // backend-specific opaque data

interface EndpointBackend {
    val id: ResourceLocation
    val payloadCodec: Codec<out EndpointPayload>

    fun resolveBlockEntity(level: Level, payload: EndpointPayload): BlockEntity?
    fun worldCenter(level: Level, payload: EndpointPayload): Vec3?
    fun claims(level: Level, worldPos: BlockPos): EndpointPayload?
}

object EndpointBackends {
    private val byId = LinkedHashMap<ResourceLocation, EndpointBackend>()
    fun register(b: EndpointBackend)
    fun get(id: ResourceLocation): EndpointBackend?
    fun all(): Collection<EndpointBackend>          // insertion order
}

data class EndpointRef(val backendId: ResourceLocation, val payload: EndpointPayload) {
    fun resolve(level: Level): BlockEntity?
    fun worldCenter(level: Level): Vec3?
}
```

**Resolve-on-bind algorithm.** When a raycast hits a world-space BlockPos:

```kotlin
fun EndpointRef.Companion.from(level: Level, hitWorldPos: BlockPos): EndpointRef {
    for (b in EndpointBackends.all()) {            // VS first, World last (fallback)
        b.claims(level, hitWorldPos)?.let { return EndpointRef(b.id, it) }
    }
    error("no backend claimed pos $hitWorldPos")
}
```

The `world` backend always claims (returns the world `BlockPos`), so it lives last. Ship/contraption backends inspect VS/Create state and claim only when applicable.

## Built-in backends (this sub-project)

| Backend ID            | Payload                                    | Registration condition                                    |
|-----------------------|--------------------------------------------|-----------------------------------------------------------|
| `nodewire:world`      | `BlockPos` (world-space)                    | always (fallback)                                          |
| `nodewire:vs_ship`    | `(shipId: Long, localPos: BlockPos)`        | only if `ModList.get().isLoaded("valkyrienskies")`         |

Sub-project #2 will add `nodewire:create_contraption` by calling `EndpointBackends.register(...)` from its own initializer — zero core edits.

### `nodewire:world` backend

- `resolveBlockEntity` → `level.getBlockEntity(pos)`
- `worldCenter` → `Vec3.atCenterOf(pos)`
- `claims` → `Payload.World(pos)` always

### `nodewire:vs_ship` backend

Lives in `dev.nitka.nodewire.integration.vs.VsShipBackend`. Imports VS API directly (no reflection). VS is in `modImplementation` (build.gradle.kts:134) — always on classpath. `ModList.isLoaded` gates `register()` at runtime; the class itself is always loadable.

- `claims(level, worldPos)` → `VSGameUtilsKt.getShipObjectManagingPos(level, worldPos)?.let { Payload.OnShip(it.id, worldPos) }` (note: when raycast goes through VS, `worldPos` is already the ship-local pos)
- `resolveBlockEntity(level, p)` → `level.getBlockEntity(p.localPos)` — VS hooks `getBlockEntity` so ship-local positions resolve correctly
- `worldCenter(level, p)` → look up ship via `VSGameUtilsKt.getShipObjectWorld(level, p.shipId)`; if null (unloaded/deleted), return null. Else transform `Vec3.atCenterOf(p.localPos)` through `ship.renderTransform.shipToWorld` (`renderTransform` is the tick-interpolated pose — gives jitter-free wires)

## Data model changes

```kotlin
// before
data class Binding(val sourceChannel: String, val targetPos: BlockPos, val targetChannel: String)
data class SideBinding(val sourceChannel: String, val targetPos: BlockPos, val targetSide: Direction)

// after
data class Binding(val sourceChannel: String, val target: EndpointRef, val targetChannel: String)
data class SideBinding(val sourceChannel: String, val target: EndpointRef, val targetSide: Direction)
```

`sourcePos` stays as `BlockPos` on packets — the source is always the BE itself, and its ship membership is computed on the fly via `EndpointRef.from(level, this.blockPos)` when needed.

## Codec / serialization

`EndpointRef` codec — tagged dispatch on `backendId`:

```kotlin
val ENDPOINT_REF_CODEC: Codec<EndpointRef> =
    ResourceLocation.CODEC.dispatch(
        "backend",
        EndpointRef::backendId,
        { id ->
            EndpointBackends.get(id)?.payloadCodec?.xmap(
                { p -> EndpointRef(id, p) }, { ref -> ref.payload }
            ) ?: UNKNOWN_BACKEND_CODEC(id)        // fail-soft: keep raw NBT
        }
    )
```

NBT examples:

```
{ backend: "nodewire:vs_ship", payload: { ship: 4231L, pos: [I; 3, 64, -2] } }
{ backend: "nodewire:world",   payload: [I; 12, 70, 8] }
```

**Legacy migration.** Old worlds have `Binding` with `targetPos: [I;..]` (no `target` field). `BindingCodec` uses `Codec.either(legacyCodec, newCodec)`:
- legacy path reads `targetPos` → wraps as `EndpointRef(world, pos)`
- writes always use new format

Migration is automatic, one-way, runs once per BE on first save after upgrade.

**Unknown backend (dependency mod removed).** Codec returns `EndpointRef(id, UnknownPayload(rawNbt))`. `resolve()` and `worldCenter()` return null. The binding is preserved in NBT and re-serialized as-is, so re-adding the mod restores it. Cleanup is manual (future "Cleanup dangling bindings" UI in Bindings Manager).

## Packets

All packets that carry endpoint identity migrate `BlockPos` → `EndpointRef`:

- `BindChannelPacket.targetPos` → `target: EndpointRef`
- `SetChannelSourcePacket.sourcePos` (if it currently stores raw) — review and align
- `SaveGraphPacket` — any per-binding pos
- Wire-cache sync packet (server→client) — both `src` and `tgt` become `EndpointRef`
- `HighlightPacket.pos` → `endpoint: EndpointRef` (so chat-link / Bindings Manager highlight works for ship blocks)

Encode/decode through `buf.writeCodec(ENDPOINT_REF_CODEC, ref)` / `buf.readCodec(ENDPOINT_REF_CODEC)` — same pattern as existing packets.

**Server-side reach validation in `BindChannelPacket`:** today's `MAX_REACH_SQ = 16²` compares `player.distanceToSqr(sourcePos.center)`. For ship blocks `sourcePos` is ship-local (lives in shipyard region), so the distance check is meaningless. Fix:

```kotlin
val srcWorld = EndpointBackends.get(srcRef.backendId)?.worldCenter(level, srcRef.payload)
    ?: srcRef.payload.toCenterVec()              // world backend
if (player.distanceToSqr(srcWorld) > MAX_REACH_SQ) reject(...)
```

Always compare in world-space via backend.

## Channel Link Tool

Vanilla `Player.pick(...)` doesn't see ship blocks (they sit in shipyard regions). Replace with VS-aware raycast when VS is loaded:

```kotlin
val hit: BlockHitResult = if (ModList.get().isLoaded("valkyrienskies")) {
    RaycastUtilsKt.clipIncludeShips(
        level, ClipContext(eye, end, Block.OUTLINE, Fluid.NONE, player)
    )
} else {
    level.clip(ClipContext(eye, end, Block.OUTLINE, Fluid.NONE, player))
}
val ref = EndpointRef.from(level, hit.blockPos)  // hit.blockPos is ship-local if VS hit a ship
```

The tool's "highlight source" overlay reads `EndpointBackends.get(ref.backendId).worldCenter(level, ref.payload)` so the highlight visually attaches to the actual rendered block, not its shipyard storage location.

## Wire renderer

Wire cache entry becomes `WireEntry(src: EndpointRef, tgt: EndpointRef, color, channelType)`.

Per frame in `RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS`:

```kotlin
for (w in wires) {
    val s = w.src.worldCenter(level) ?: continue   // skip if not loaded
    val t = w.tgt.worldCenter(level) ?: continue
    drawLine(poseStack, s, t, w.color, alpha)
}
```

VS backend uses `ship.renderTransform.shipToWorld` (interpolated pose, render thread is safe). Frustum cull on world-space midpoint as today.

When a ship is unloaded → `worldCenter` returns null → wire silently skipped, **but cache entry is kept**. When ship reloads, wire reappears automatically.

## Edge cases

- **Ship unloaded:** ship-side BE doesn't tick (chunks unloaded). World-side source BE's `target.resolve()` returns null → signal not propagated, no errors. Renderer skips lines. Reverses cleanly on reload.
- **Ship deleted (VS command):** `worldCenter` returns null forever. Binding becomes dangling but survives in NBT — preserved for future "Cleanup" UI.
- **LogicBlock destroyed:** existing `setRemoved()` path handles both world and ship cases identically (same `Level.getBlockEntity(localPos)` machinery). `level.sendBlockUpdated` triggers neighbors to refresh.
- **Cross-dimension:** never supported, no change.
- **Mod removed:** unknown backend → fail-soft (see Codec section).

## Manual test plan

1. World↔world bind — regression check, must work unchanged.
2. Place ship via VS creator. Put LogicBlock on ship, second on the ground, bind via Channel Link Tool. Verify wire renders; fly the ship; wire follows.
3. Two ships, one LogicBlock each, bind cross-ship. Move both ships; wire follows both endpoints.
4. Fly far enough to unload a ship → wire disappears. Fly back → wire reappears, signal resumes.
5. Remove VS from mods (with a backup save), reload world → bindings to ship blocks survive as dangling, world↔world bindings unaffected, no crash.

## Out of scope (separate sub-projects)

- Create contraptions support — sub-project #2 (adds `nodewire:create_contraption` backend + Create: Interactive interop for assembled BEs).
- Cross-frame renderer abstraction layer (if needed, e.g., Flywheel-aware rendering) — decided after sub-project #2.
- "Cleanup dangling bindings" UI in Bindings Manager — follow-up.

## File layout

- `core/EndpointPayload.kt` (interface), `core/EndpointBackend.kt` (interface), `core/EndpointBackends.kt` (registry), `core/EndpointRef.kt` (data class + `from`/`resolve` helpers + codec)
- `core/WorldBackend.kt` — built-in world backend, registered unconditionally in `Nodewire.init`
- `integration/vs/VsShipBackend.kt` — direct VS imports; `register()` called from `Nodewire.init` behind `ModList.get().isLoaded("valkyrienskies")`
- Modified: `Binding`, `SideBinding`, `LogicBlockEntity`, all `*Packet.kt` carrying endpoint identity, `BlockHighlightRenderer`, `WireWorldRenderer`, `ChannelLinkTool` (client + server validator), `BindingCodec` (legacy fallback)

## Decisions log

- **No reflection** for mod APIs — direct compileOnly imports + `ModList.isLoaded` gate.
- **`renderTransform`** (not `transform`) for ship→world matrix — interpolated pose, no jitter.
- **Open registry** (not sealed hierarchy) — third-party container mods integrate without core edits.
- **Unlimited range** — no distance cap on cross-ship bindings; server signal flows as long as both ends are loaded.
- **Legacy NBT migration** auto-runs on first save after upgrade; no manual conversion needed.
