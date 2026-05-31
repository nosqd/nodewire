# Camera Block — implementation plan (2026-05-31)

> Authoritative spec: `docs/superpowers/specs/2026-05-31-video-subsystem-design.md` §4.
> Reference: SecurityCraft 1.21.1 `MixinGameRenderer` afterLevelRendering capture (the *only* required mixin).
> Target: MC 1.21.1 + NeoForge 21.1.230 + Kotlin 2.0.20 + ModDevGradle 2.0.141.
> Mixin package: `dev.nitka.nodewire.mixin`, `compatibilityLevel JAVA_17`.
> `validateAccessTransformers = true` is set → a wrong AT entry FAILS the build, so compile-green proves AT validity.
> **NEVER run `runClient`.** Allowed gradle: `./gradlew :compileKotlin :compileJava :test` (and `:build`). User runtime-tests in-game.

The Camera is a **BlockEntity producer**, NOT a pure-function node (NodeEvaluator is server-side/deterministic and cannot drive a client-local GL capture). It mirrors `ScreenBlockEntity` but as the producer end of the existing handle→channel pipeline: it mints+`acquire()`s a stable handle client-side, publishes `PinValue.Video(handle)` (via a channel binding), and a client-side capture loop renders the world-from-its-POV into that handle's `GlVideoSurface` FBO every capture frame. The Screen on the other end blits it — zero new node-graph machinery.

Ordering is **compile-gated**: AT first (so `validateAccessTransformers` passes), then mixin + real `isCapturing()`, then block/BE/registration/BER, then the capture loop + cost controls, then the channel-param producer wiring.

---

## Task 1 — AccessTransformer config + gradle wiring

ModDevGradle 2.0.141 does NOT auto-detect `accesstransformer.cfg`; it must be declared explicitly.

**`build.gradle.kts`** — inside the existing `neoForge { … }` block (after `validateAccessTransformers = true`, line 83), add:
```kotlin
accessTransformers {
    from("src/main/resources/META-INF/accesstransformer.cfg")
}
```

**Create `src/main/resources/META-INF/accesstransformer.cfg`** (mojmap AT syntax — `public[-f] <fqcn> <member>`). Include ONLY members the api-map confirmed have **no public getter** and need direct field/access. Do NOT add any already-public member (it FAILS `validateAccessTransformers`):
```
public-f net.minecraft.client.Minecraft mainRenderTarget
public net.minecraft.client.Camera eyeHeight
public net.minecraft.client.Camera eyeHeightOld
public net.minecraft.client.renderer.LevelRenderer renderBuffers
public net.minecraft.client.renderer.LevelRenderer visibleSections
public net.minecraft.client.renderer.LevelRenderer transparencyChain
```
Rationale per the api-map:
- `Minecraft.mainRenderTarget` is `public-f` — we write the field directly (`mc.mainRenderTarget = frameTarget`); `getMainRenderTarget()` is only a getter.
- `Camera.eyeHeight`/`eyeHeightOld` — no getters; needed to force the marker eye to block-center.
- `LevelRenderer.renderBuffers`/`visibleSections`/`transparencyChain` — no public accessors.
- **Deliberately omitted** (already public via getters — adding them would FAIL the build): `LevelRenderer.translucentTarget/itemEntityTarget/weatherTarget` (use `getTranslucentTarget()/getItemEntityTarget()/getWeatherTarget()`), `GameRenderer.{renderLevel,setRenderBlockOutline,setRenderHand,setPanoramicMode,getMainCamera,getProjectionMatrix,tryTakeScreenshotIfNeeded}`, `RenderTarget.{bindWrite,unbindWrite,clear,getColorTextureId,destroyBuffers}`, `TextureTarget` ctor, `Camera.setup`, `LevelRenderer.{getFrustum,offsetFrustum}`, `Marker` ctor, `EntityType.MARKER`.
- **Omitted** `SectionRenderDispatcher$RenderSection reset()V` — only needed for the short-view-distance flood-fill, which v1 does NOT ship.

`neoforge.mods.toml` needs NO AT reference (ModDev uses the gradle `accessTransformers.from`).

**Compile gate:** `./gradlew :compileKotlin :compileJava` (validates the AT entries against the merged jar — a typo'd target fails here).

---

## Task 2 — Real `VideoManager.isCapturing()` recursion guard

Flip the stub (`VideoManager.kt` lines 58-59 `fun isCapturing(): Boolean = false`) to a real volatile flag. Both `ScreenBlockRenderer.render` (line 49) and `VideoFrameRenderer.drawInto` (line 59) already early-return on it, so wiring it correctly makes both refuse to draw mid-capture with zero changes to them.

**`src/main/kotlin/dev/nitka/nodewire/client/video/VideoManager.kt`** — replace the stub:
```kotlin
@Volatile private var capturing: Boolean = false

@JvmStatic
fun isCapturing(): Boolean = capturing

/** Engage the screen-in-screen recursion guard for the duration of a Camera capture pass. */
@JvmStatic
fun beginCapture() { capturing = true }

/** Release the recursion guard. MUST be called in the capture loop's `finally`. */
@JvmStatic
fun endCapture() { capturing = false }
```
Keep `resetForTest()` setting `capturing = false`.

**Compile gate:** `./gradlew :compileKotlin :test` (existing video tests must stay green).

---

## Task 3 — Capture mixin: GameRenderer @ tryTakeScreenshotIfNeeded

The single required client-only render hook. SecurityCraft's afterLevelRendering point: fires immediately after the main level render flush, before GUI.

**Create `src/main/java/dev/nitka/nodewire/mixin/camera/MixinGameRenderer.java`:**
```java
package dev.nitka.nodewire.mixin.camera;

import dev.nitka.nodewire.client.camera.VideoCameraCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class MixinGameRenderer {

    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V"
        )
    )
    private void nodewire$captureCameras(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        VideoCameraCapture.captureFeeds(deltaTracker);
    }
}
```
- `priority = 1100` → runs after most other mods' hooks.
- Plain `@Inject` + `CallbackInfo` — no MixinExtras needed.
- The dropped SecurityCraft injects (tickFov zoom, renderCameraTint, pick HEAD-cancel, renderLevel lerp distortion-disable) are player-mount/cosmetic → NOT ported. No LevelRendererMixin (no flood-fill in v1).

**Register in `src/main/resources/nodewire.mixins.json`** — add to the `"client"` array (this is CLIENT render code, NOT `"mixins"`), package-relative:
```json
"client": [
  "camera.MixinGameRenderer"
],
```

`VideoCameraCapture` does not exist yet → create a stub in this task so it compiles, fully bodied in Task 6:
**Create `src/main/kotlin/dev/nitka/nodewire/client/camera/VideoCameraCapture.kt`:**
```kotlin
package dev.nitka.nodewire.client.camera

import net.minecraft.client.DeltaTracker

object VideoCameraCapture {
    /** Entry point from MixinGameRenderer (afterLevelRendering). Render thread. */
    @JvmStatic
    fun captureFeeds(deltaTracker: DeltaTracker) {
        // Bodied in Task 6.
    }
}
```

**Compile gate:** `./gradlew :compileKotlin :compileJava` (Java mixin references the Kotlin `object`'s `@JvmStatic` method).

---

## Task 4 — Camera block + BE + Registry + BER registration

Mirror the Screen quartet exactly. The Camera is a producer: it mints a **stable** handle (persisted UUID in BE NBT), `acquire()`s it client-side on load, `release()`s on remove, and exposes params (FOV/on-off/yaw/pitch) it reads from its channel inputs. A `CameraFeed` registry entry (Task 5) lets the capture loop discover it.

**Create `src/main/kotlin/dev/nitka/nodewire/block/CameraBlock.kt`** — copy `ScreenBlock` structure (HorizontalDirectionalBlock FACING, `getStateForPlacement` opposite, `EntityBlock`, `newBlockEntity = CameraBlockEntity(pos, state)`, no ticker):
```kotlin
class CameraBlock(props: Properties) : Block(props), EntityBlock {
    init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)) }
    override fun createBlockStateDefinition(b: StateDefinition.Builder<Block, BlockState>) { b.add(FACING) }
    override fun getStateForPlacement(c: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, c.horizontalDirection.opposite)
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CameraBlockEntity(pos, state)
    companion object { val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING }
}
```

**Create `src/main/kotlin/dev/nitka/nodewire/block/CameraBlockEntity.kt`** — producer counterpart of `ScreenBlockEntity`, implementing `ChannelInputSink`:
- Persisted **stable** handle: `private var handle: UUID` — generate `UUID.randomUUID()` on first `onLoad` if absent; `saveAdditional`/`loadAdditional` round-trip `tag.putUUID(HANDLE_KEY, handle)`; `getUpdateTag`/`getUpdatePacket` like Screen so the client learns it.
- `fun videoHandle(): UUID = handle` (always non-nil; this is the producer surface).
- `channelInputs: MutableMap<String, PinValue>` + `override fun writeChannelInput(name, value)` storing into it (same server→`sendBlockUpdated(UPDATE_CLIENTS)` / client retarget split as Screen, but here the inputs are the **params**, not a handle).
- Param readers with safe defaults:
  - `fun fovDeg(): Double = (channelInputs[FOV_CHANNEL] as? PinValue.Num)?.value?.toDouble() ?: 90.0` (clamp 30.0..110.0)
  - `fun enabled(): Boolean = (channelInputs[ENABLE_CHANNEL] as? PinValue.Bool)?.value ?: true`
  - `fun yawDeg(): Float = (channelInputs[YAW_CHANNEL] as? PinValue.Num)?.value?.toFloat() ?: 0f`
  - `fun pitchDeg(): Float = (channelInputs[PITCH_CHANNEL] as? PinValue.Num)?.value?.toFloat() ?: 0f`
  (use the real `PinValue` subtype names from `graph/PinValue.kt` — `Num`/`Bool`/etc.; verify exact names while editing.)
- Client lifecycle (gated `level?.isClientSide == true`):
  - `onLoad()` → `VideoManager.acquire(handle)` AND `CameraFeedRegistry.register(CameraFeed(this))` (Task 5).
  - `setRemoved()` → `VideoManager.release(handle)` + `CameraFeedRegistry.unregister(handle)`.
- `companion object`: `HANDLE_KEY = "video_handle"`, channel slot names `FOV_CHANNEL="fov"`, `ENABLE_CHANNEL="enable"`, `YAW_CHANNEL="yaw"`, `PITCH_CHANNEL="pitch"`, plus a `CHANNEL_TARGET: ChannelTargetProvider` returning `listOf(TargetSlot.Channel(FOV_CHANNEL, PinType.NUMBER), TargetSlot.Channel(ENABLE_CHANNEL, PinType.BOOL), TargetSlot.Channel(YAW_CHANNEL, PinType.NUMBER), TargetSlot.Channel(PITCH_CHANNEL, PinType.NUMBER))` — verify the exact `PinType` enum constant names against `graph/PinType.kt`.

**Create `src/main/kotlin/dev/nitka/nodewire/client/camera/CameraBlockRenderer.kt`** — minimal `BlockEntityRenderer<CameraBlockEntity>`. The Camera face is NOT a video blit (it's the producer), so v1 can render nothing (empty `render`) or a simple lens-quad. Keep it a no-op `render` body for v1 (the block still needs a BER registration only if it has special rendering; if none, this file can be omitted and the registration line dropped). **Decision: omit the BER** — the Camera needs no custom rendering; drop the `registerBlockEntityRenderer` line. (Listed here for symmetry; do NOT create it unless a lens overlay is wanted.)

**`Registry.kt`** — add the trio (mirror SCREEN_*):
```kotlin
val CAMERA_BLOCK: DeferredBlock<CameraBlock> = BLOCKS.register("camera_block") { _ ->
    CameraBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK))
}
val CAMERA_BLOCK_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem(CAMERA_BLOCK)
val CAMERA_BLOCK_BE: DeferredHolder<BlockEntityType<*>, BlockEntityType<CameraBlockEntity>> =
    BLOCK_ENTITIES.register("camera_block") { _ ->
        BlockEntityType.Builder.of(::CameraBlockEntity, CAMERA_BLOCK.get()).build(null)
    }
```
In `register(bus)`, add the channel-target registration inside the existing `FMLCommonSetupEvent` listener:
```kotlin
ChannelTargetRegistry.register(CAMERA_BLOCK.get(), CameraBlockEntity.CHANNEL_TARGET)
```
In `onBuildTabs`, add `event.accept(CAMERA_BLOCK_ITEM.get())` under the `REDSTONE_BLOCKS` branch.

(No BER registration line in `NodewireClient.kt` per the omit-BER decision above.)

**Compile gate:** `./gradlew :compileKotlin :compileJava :test`.

---

## Task 5 — CameraFeed + CameraFeedRegistry (capture discovery + frustum/FPS state)

Client-side registry of active camera producers the capture loop iterates. Holds per-feed FPS-decouple timestamps, frustum-visibility test, and the world-pose resolution (Sable render-pose aware).

**Create `src/main/kotlin/dev/nitka/nodewire/client/camera/CameraFeed.kt`:**
- Fields: `val handle: UUID`, `val pos: BlockPos`, `val level: Level` (the camera's client level), and references needed to resolve params (a back-ref to the `CameraBlockEntity` or copies of its readers).
- `@Volatile var lastActiveTimeSec: Double = 0.0` (per-feed FPS gate), `@Volatile var lastFrameRenderedShared` lives on the registry (static) not here.
- `var removed: Boolean = false`; `fun markForRemoval() { removed = true }` (self-heal on capture exception).
- `fun renderTarget(): RenderTarget?` = `(VideoManager.getOrCreate(handle) as? GlVideoSurface)?.target()`.
- `fun hasFrameInFrustum(playerFrustum: Frustum): Boolean` — track the consumer Screen `BlockPos`(es) for this handle and test `playerFrustum.isVisible(AABB(screenPos))`. v1 simplification: maintain a set of consumer positions in a `ScreenConsumerIndex` populated by `ScreenBlockEntity` onLoad/setRemoved keyed by handle; if the index is empty for this handle, treat as **not visible** (don't waste a capture on a screen nobody can see). If consumer tracking is not yet wired, v1 fallback: always return `true` (render every active feed) — gated additionally by the active-camera cap (Task 6) so cost stays bounded. **Ship v1 with the always-true fallback; add the consumer-index test as a follow-up.**
- `fun worldPose(level: Level, deltaTracker: DeltaTracker): Pair<Vec3, FloatArray>?` — resolve world position + (yaw,pitch). Route through the **client render-pose**: if the block is in a Sable sub-level, use `SableSubLevelBackend.worldCenter(level, payload)` for position and `worldDirection(level, payload, localForward)` for the look vector (both internally use `ClientSubLevelAccess.renderPose()` on the client — partial-tick smooth, NO judder on a moving aircraft). Otherwise plain `Vec3(pos.x+0.5, pos.y+0.5, pos.z+0.5)` and a direction from `CameraBlock.FACING` + the channel-driven `yaw/pitch` offsets. Derive `(yawDeg, pitchDeg)` from the resolved look vector (or facing+offsets in the non-Sable path).

**Create `src/main/kotlin/dev/nitka/nodewire/client/camera/CameraFeedRegistry.kt`:**
```kotlin
object CameraFeedRegistry {
    private val feeds = java.util.concurrent.ConcurrentHashMap<UUID, CameraFeed>()
    fun register(feed: CameraFeed) { feeds[feed.handle] = feed }
    fun unregister(handle: UUID) { feeds.remove(handle) }
    fun active(): Collection<CameraFeed> = feeds.values
    fun isEmpty(): Boolean = feeds.isEmpty()
}
```

**Compile gate:** `./gradlew :compileKotlin :compileJava :test`.

---

## Task 6 — The capture loop (VideoCameraCapture.captureFeeds) + cost controls

Body the Task-3 stub. Render thread, called afterLevelRendering. Save/restore dance around a per-feed `renderLevel` into each feed's FBO. All real method names below confirmed against the 1.21.1 merged jar.

**Key 1.21.1 signature deltas (load-bearing):**
- `GameRenderer.renderLevel(DeltaTracker)` — takes a `DeltaTracker`, NOT a float partialTick. Pass `DeltaTracker.ONE` (constant) or the incoming `deltaTracker`.
- `GameRenderer.getProjectionMatrix(double fov)` — param is **double**, not float. Used in the BER blit, not here.
- `Camera.setup(BlockGetter, Entity, boolean detached, boolean thirdPersonReverse, float partialTick)`.

**`VideoCameraCapture.captureFeeds(deltaTracker)`:**

GUARDS (early-return, before any save):
```
val mc = Minecraft.getInstance()
if (CameraFeedRegistry.isEmpty()) return
if (VideoManager.isCapturing()) return            // re-entry guard
if (mc.level == null || mc.player == null) return
// (optional) config kill-switch check here
```

FPS GATE (24 fps, decoupled from client fps, before save-state):
```
val fpsCap = 24
val frameInterval = 1.0 / fpsCap
val now = org.lwjgl.glfw.GLFW.glfwGetTime()           // wall-clock, fps-independent
val all = CameraFeedRegistry.active().filter { !it.removed }
if (now < lastFrameRenderedSec + frameInterval / max(1, all.size)) return  // stagger across mc frames
val feedCount = all.size
var budget = Mth.ceil(fpsCap * (feedCount + 1).toDouble() / mc.fps.toDouble())  // per-mc-frame cap
val MAX_ACTIVE = 4                                     // hard cap on simultaneous feeds
val active = all.asSequence()
    .filter { now >= it.lastActiveTimeSec + frameInterval }
    .filter { /* hasFrameInFrustum — see Task 5; v1 always-true */ true }
    .take(MAX_ACTIVE)
    .filter { budget-- > 0 }
    .toList()
if (active.isEmpty()) return
lastFrameRenderedSec = now
```
(`lastFrameRenderedSec`: `@Volatile private var` static on the object. `Mth` = `net.minecraft.util.Mth`.)

SAVE STATE (once, before the per-feed loop):
```
val lr = mc.levelRenderer
val camera = mc.gameRenderer.mainCamera
val window = mc.window
val oldCamEntity = mc.cameraEntity
val oldWidth = window.width; val oldHeight = window.height
val oldVisible = ArrayList(lr.visibleSections)           // AT field — clone
val oldCameraType = mc.options.cameraType
val oldMain = mc.mainRenderTarget                         // AT public-f field read
val oldTranslucent = lr.translucentTarget                // getter exists; field nulled below
val oldItemEntity = lr.itemEntityTarget
val oldWeather = lr.weatherTarget
val oldTransparency = lr.transparencyChain               // AT field
val oldEyeH = camera.eyeHeight; val oldEyeHO = camera.eyeHeightOld   // AT fields
// player pose snapshot (restore after): x/xo, y/yo, z/zo, xRot/xRotO, yRot/yRotO
val playerFrustum = lr.frustum                            // snapshot ONCE (mutates per pass)
val markerEntity = net.minecraft.world.entity.Marker(net.minecraft.world.entity.EntityType.MARKER, mc.level)
```
(Use the AT'd fields directly: `lr.visibleSections`, `lr.renderBuffers`, `lr.transparencyChain`, `camera.eyeHeight/eyeHeightOld`, `mc.mainRenderTarget`. For the three fabulous targets use the getters when reading and the AT-free setters do not exist → assign via the public getter is read-only; **to NULL them**, the fields have getters but the writes need... note: nulling requires field write. The three fabulous targets do NOT have public setters; SecurityCraft writes the fields. Since the api-map says they have getters but the WRITE needs field access, and we deliberately did NOT AT them — instead **skip nulling the fabulous targets in v1** if not in Fabulous mode; if Fabulous-mode breakage appears in testing, add `public net.minecraft.client.renderer.LevelRenderer translucentTarget/itemEntityTarget/weatherTarget` to the AT cfg in a follow-up. v1: only null `transparencyChain` (AT'd) and accept degraded Fabulous translucency, per the documented SecurityCraft tradeoff.)

SETUP FLAGS (once):
```
mc.gameRenderer.setRenderBlockOutline(false)
mc.gameRenderer.setRenderHand(false)
mc.gameRenderer.setPanoramicMode(true)
window.setWidth(100); window.setHeight(100)              // 1:1 ratio to match the square FBO
mc.options.setCameraType(CameraType.FIRST_PERSON)
val standEye = mc.player!!.getDimensions(Pose.STANDING).eyeHeight()
camera.eyeHeight = standEye; camera.eyeHeightOld = standEye
lr.transparencyChain = null
mc.renderBuffers().bufferSource().endBatch()             // flush prior world render
```

PER-FEED LOOP (`for (feed in active)`), each in `try { … } catch (e: Throwable) { LOG.error; feed.markForRemoval() }`:
```
val target = feed.renderTarget() ?: continue
val pose = feed.worldPose(mc.level!!, deltaTracker) ?: continue   // (Vec3 pos, [yaw,pitch])
val (wpos, yawPitch) = pose
markerEntity.setPos(wpos.x, wpos.y - standEye + 0.5, wpos.z)       // FBO eye lands at block center
markerEntity.yRot = yawPitch[0]; markerEntity.xRot = yawPitch[1]
mc.cameraEntity = markerEntity                                    // DIRECT field write (no setter)
VideoManager.beginCapture()                                      // engage recursion guard
camera.setup(mc.level, markerEntity, /*detached=*/false, /*reversed=*/false, /*partialTick=*/deltaTracker.gameTimeDeltaPartialTick(false))
target.clear(Minecraft.ON_OSX)
target.bindWrite(true)
mc.mainRenderTarget = target                                      // DIRECT AT field write
mc.gameRenderer.renderLevel(DeltaTracker.ONE)                     // the high-level pass (1.21.1)
VideoManager.endCapture()
```
(`VideoManager.beginCapture()/endCapture()` from Task 2 — the loop must ensure `endCapture()` runs even on exception; simplest is `beginCapture()` once before the loop and `endCapture()` in the outer `finally` so a feed that throws never leaves the guard engaged. Choose ONE: per-feed begin/end inside the try/finally, OR a single begin before the loop + end in the RESTORE finally. Use the **single begin before loop / end in finally** form for robustness.)

RESTORE (once, in `finally` of the whole loop):
```
markerEntity.discard()
mc.cameraEntity = oldCamEntity                                    // DIRECT write
window.setWidth(oldWidth); window.setHeight(oldHeight)
lr.visibleSections.clear(); lr.visibleSections.addAll(oldVisible)
// restore player pose (setPosRaw + xo/yo/zo + setXRot/xRotO + setYRot/yRotO)
val thirdPerson = oldCameraType != CameraType.FIRST_PERSON
camera.setup(mc.level, oldCamEntity ?: mc.player, !thirdPerson, oldCameraType == CameraType.THIRD_PERSON_FRONT, deltaTracker.gameTimeDeltaPartialTick(false))
camera.eyeHeight = oldEyeH; camera.eyeHeightOld = oldEyeHO
mc.options.setCameraType(oldCameraType)
mc.gameRenderer.setRenderBlockOutline(true)
mc.gameRenderer.setRenderHand(true)
mc.gameRenderer.setPanoramicMode(false)
mc.mainRenderTarget = oldMain                                     // DIRECT write
oldMain.bindWrite(true)
lr.transparencyChain = oldTransparency
VideoManager.endCapture()                                        // release recursion guard (idempotent)
```
Stamp `feed.lastActiveTimeSec = now` for each rendered feed.

**`Minecraft.ON_OSX`** is the `clear(boolean)` arg (and the `RenderTarget.clear(boolean)` signature). `mc.fps` via `Minecraft.getInstance().fps`.

**Compile gate:** `./gradlew :compileKotlin :compileJava` (this is the dense one — `:test` won't exercise GL but must stay green).

---

## Task 7 — Channel-param producer wiring (FOV / on-off / yaw / pitch via the bind packet)

Params reach the Camera BE through the **existing** channel pipeline — no new node type. Copy the `BindAeroSourcePacket`/`BindChannelPacket` template so the Channel Link Tool can bind a `channel_output` (or a side/source) into the Camera's named channel slots (`fov`/`enable`/`yaw`/`pitch`), which `CameraBlockEntity.writeChannelInput` already consumes.

The Camera is already a `ChannelTargetRegistry` target (Task 4) exposing those four `TargetSlot.Channel` slots typed NUMBER/BOOL — so the **existing** `BindChannelPacket` + `ChannelLinkToolItem` flow already targets it with ZERO new packet code IF the tool's target-picker reads slots from `ChannelTargetRegistry`. Verify: `block/ChannelTargetProvider.kt` + `item/ChannelLinkToolItem.kt` resolve targets via `ChannelTargetRegistry`; if so, the Camera is bindable out of the box and Task 7 is just confirming the slot types compile and round-trip.

If a dedicated bind packet is wanted (Aero-style, to bind a producer source directly without a LogicBlock), copy `BindAeroSourcePacket.kt` → `BindCameraParamPacket.kt`:
- Fields `(targetPos: BlockPos, channel: String, value: ...)` or `(targetPos, nodeId, endpoint, channel)` per the param source model.
- `TYPE = CustomPacketPayload.Type(ResourceLocation(Nodewire.ID, "bind_camera_param"))`, `CODEC` via `RecordCodecBuilder`, `STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()`.
- `handle(packet, ctx)` validates reach + target BE is a `CameraBlockEntity`, then `be.writeChannelInput(packet.channel, value)` and `level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)`.
- Register in `NodewireNetwork.register`: `registrar.playToServer(BindCameraParamPacket.TYPE, BindCameraParamPacket.STREAM_CODEC, BindCameraParamPacket::handle)`.

**Recommendation:** ship v1 with the **existing channel pipeline only** (Camera as a `ChannelTargetRegistry` target — Task 4 already did this) and do NOT add a new packet. Params flow `channel_output(NUMBER/BOOL) → cross-block delivery → CameraBlockEntity.externalChannelInputs/writeChannelInput`. Add `BindCameraParamPacket` later only if direct (LogicBlock-free) binding proves more ergonomic.

**Camera output → graph:** the Camera publishes `PinValue.Video(handle)` so a Screen can consume it. Since the Camera is a BE producer (not a node), it publishes by being a **channel source**: expose a `TargetSlot`/source on its facing that emits `PinValue.Video(videoHandle())`, OR keep it consumer-only-param and let the player bind `camera → channel_output(VIDEO) → screen` via the tool where the Camera's handle is read from its BE. v1: the Camera writes its handle into a bound channel via the same `LogicBlockEntity` cross-block path the Screen reads from (mirror `ScreenBlockEntity` but as the emitting end). Confirm the emit path against `block/LogicBlockEntity.kt` `externalChannelInputs`/`nwWriteChannelInput` and wire the Camera handle as the value.

**Compile gate:** `./gradlew :compileKotlin :compileJava :test`.

---

## Cost-control summary (all in Task 6 unless noted)
- **256×256 square FBO** — `VideoManager.STANDARD_SIZE = 256`, surfaces already square (1:1), matches the `window.setWidth/Height(100)` 1:1 capture ratio.
- **24 fps decouple** — `GLFW.glfwGetTime()` wall-clock, per-feed `lastActiveTimeSec + frameInterval` + cross-mc-frame stagger via `lastFrameRenderedSec`.
- **Per-mc-frame budget** — `budget = Mth.ceil(fpsCap*(feedCount+1)/mc.fps)`, decrement per feed.
- **Capped active cameras** — `MAX_ACTIVE = 4` `.take(MAX_ACTIVE)`.
- **Frustum-visible-only** — `playerFrustum.isVisible(AABB(consumerScreenPos))` (Task 5; v1 always-true fallback, gated by the cap).
- **Short view distance** — NOT shipped in v1 (no flood-fill / LevelRendererMixin / `RenderSection.reset()` AT). Camera renders with the player's section set; add fog clamp / flood-fill later if range looks wrong.
- **Recursion guard** — real `VideoManager.isCapturing()` (Task 2); `beginCapture()` before the loop, `endCapture()` in `finally`; Screen BER + `VideoFrameRenderer` already gate on it.
- **GL-state self-heal** — full save/restore + per-feed `try/catch` → `feed.markForRemoval()` so a broken feed self-removes; `oldMain.bindWrite(true)` re-establishes the player frame.

## Sable / aircraft
`CameraFeed.worldPose` resolves position+look via `SableSubLevelBackend.worldCenter/worldDirection`, which on the client use `ClientSubLevelAccess.renderPose()` (partial-tick) — a camera on a moving Aeronautics aircraft tracks smoothly with no judder. Build `markerEntity.setPos / yRot / xRot` from that, exactly as `WireWorldRenderer` resolves moving endpoints.

## Final gate
`./gradlew :compileKotlin :compileJava :test` green = AT valid (validateAccessTransformers), mixin refmap resolves, all wiring compiles. User runtime-tests in-game (`runClient` is theirs).
