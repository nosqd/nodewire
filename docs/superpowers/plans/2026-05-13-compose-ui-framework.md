# Compose UI Framework Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement this plan task-by-task. Each Phase is one subagent dispatch. Two-stage review per phase.

**Goal:** Build a Jetpack-Compose-runtime-based UI framework for Minecraft client Screens with Yoga flexbox layout, a theme/styling system, and a base component library — meeting all AC1–AC12 from the spec.

**Architecture:** `compose-runtime` provides @Composable + recomposition; custom `NwApplier` mirrors composition tree to `UiNode` tree where each UiNode wraps an `org.appliedenergistics.yoga.YogaNode`; layout = one `calculateLayout()` call per frame; rendering walks the tree and draws via MC `GuiGraphics` through a `NwCanvas` abstraction; input bridged from MC `Screen` callbacks.

**Tech Stack:**
- Kotlin 2.0.20 (existing)
- Compose Compiler Plugin 2.0.20-1.0.x (new)
- `org.jetbrains.compose.runtime:runtime:1.7.0` (new)
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0` (new)
- `org.appliedenergistics.yoga:yoga:1.0.0` (new)
- Existing: ForgeGradle 6, KFF 4.11, Forge 47.4.10, MC 1.20.1

**Spec:** [`docs/superpowers/specs/2026-05-13-compose-ui-framework-design.md`](../specs/2026-05-13-compose-ui-framework-design.md)

**Package root:** `dev.nitka.nodewire.ui`

---

## Phase 1: Build setup + dependency spike

**Goal:** Build passes with Compose compiler plugin + runtime + coroutines + yoga deps. Verify all three deps resolve without classpath weirdness.

**Files:**
- Modify: `build.gradle.kts`
- Modify: `gradle.properties`

- [ ] **Step 1.1: Add Compose compiler plugin + Yoga repo**

Edit `build.gradle.kts`. In the `plugins { }` block, add:

```kotlin
id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
```

In `repositories { }`, add:

```kotlin
maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
```

- [ ] **Step 1.2: Add runtime + coroutines + yoga dependencies**

In `dependencies { }`, add after the existing JEI line:

```kotlin
// Compose runtime (no UI, no Skiko)
implementation("org.jetbrains.compose.runtime:runtime:1.7.0") {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
}
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

// Flexbox layout (pure Java)
implementation("org.appliedenergistics.yoga:yoga:1.0.0")
```

- [ ] **Step 1.3: Run dependency report; verify no Skia, AWT, or native bundles**

```
./gradlew :dependencies --configuration runtimeClasspath
```

Expected: Compose runtime present, yoga present, kotlinx-coroutines present. NO `skiko`, NO `compose.ui`, NO `awt-graphics`, NO `*natives*` jars from Compose or Yoga.

If skiko appears, replace the runtime artifact ID with whichever Compose JVM-only variant is correct (try `runtime-jvm`, then check Maven coordinates on https://maven.pkg.jetbrains.space/public/p/compose/dev/org/jetbrains/compose/runtime/).

- [ ] **Step 1.4: Yoga smoke test**

Create `src/test/kotlin/dev/nitka/nodewire/ui/YogaSmokeTest.kt`:

```kotlin
package dev.nitka.nodewire.ui

import org.appliedenergistics.yoga.YogaFlexDirection
import org.appliedenergistics.yoga.YogaNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YogaSmokeTest {
    @Test fun rowOfTwoChildrenLaysOutHorizontally() {
        val root = YogaNode().apply {
            setWidth(100f); setHeight(50f)
            setFlexDirection(YogaFlexDirection.ROW)
        }
        val a = YogaNode().apply { setWidth(30f); setHeight(50f) }
        val b = YogaNode().apply { setWidth(40f); setHeight(50f) }
        root.addChildAt(a, 0); root.addChildAt(b, 1)
        root.calculateLayout(100f, 50f)
        assertEquals(0f, a.layoutX); assertEquals(30f, b.layoutX)
        assertEquals(30f, a.layoutWidth); assertEquals(40f, b.layoutWidth)
    }
}
```

Add JUnit 5 + AssertJ to build.gradle.kts under test dependencies if not present:

```kotlin
testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
tasks.named<Test>("test") { useJUnitPlatform() }
```

- [ ] **Step 1.5: Build + test**

```
./gradlew build
```

Expected: BUILD SUCCESSFUL. `YogaSmokeTest.rowOfTwoChildrenLaysOutHorizontally` passes.

- [ ] **Step 1.6: Verify runClient still launches**

```
./gradlew runClient
```

Smoke test only — close after main menu loads. If KFF + Compose compiler plugin conflict (Kotlin version mismatch), the run will fail to start. Resolve before continuing.

- [ ] **Step 1.7: Commit**

```bash
git add build.gradle.kts gradle.properties src/test
git commit -m "feat(ui): add compose-runtime + yoga + compose compiler plugin"
```

---

## Phase 2: Core value types + Modifier

**Goal:** Pure-data primitives the rest of the framework consumes — no Yoga or Compose runtime dependencies yet.

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/Color.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/Shape.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/BorderStroke.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/IntSize.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/IntOffset.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/PaddingValues.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/Arrangement.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/Alignment.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/core/Modifier.kt`

- [ ] **Step 2.1: `Color` value class**

ARGB-packed Int wrapper. API: `Color(argb)`, `Color.argb(a,r,g,b)`, `Color.rgb(r,g,b)` (alpha=255), `.a/.r/.g/.b` accessors, `.copy(alpha: Float)`, `.shiftLightness(delta: Float)`, `.blend(other, t)`, companion `Transparent/Black/White`. Use `@JvmInline value class`. ~80 lines.

- [ ] **Step 2.2: `Shape` interface + RectangleShape + RoundedCornerShape**

```kotlin
sealed interface Shape
data object RectangleShape : Shape
data class RoundedCornerShape(val radiusTL: Int, val radiusTR: Int, val radiusBR: Int, val radiusBL: Int) : Shape {
    companion object {
        operator fun invoke(radius: Int) = RoundedCornerShape(radius, radius, radius, radius)
    }
}
```

- [ ] **Step 2.3: `BorderStroke`**

```kotlin
data class BorderStroke(val width: Int, val color: Color)
```

- [ ] **Step 2.4: `IntSize`, `IntOffset`**

Each is a `@JvmInline value class` over a packed `Long` (high 32 = first, low 32 = second). Constructors + `.width/.height` or `.x/.y` accessors, equality, `toString`. Companion `Zero`.

- [ ] **Step 2.5: `PaddingValues`**

```kotlin
data class PaddingValues(val start: Int, val top: Int, val end: Int, val bottom: Int) {
    companion object {
        operator fun invoke(all: Int) = PaddingValues(all, all, all, all)
        operator fun invoke(horizontal: Int, vertical: Int) = PaddingValues(horizontal, vertical, horizontal, vertical)
    }
}
```

- [ ] **Step 2.6: `Arrangement` + `Alignment`**

```kotlin
sealed interface Arrangement {
    sealed interface Horizontal : Arrangement
    sealed interface Vertical : Arrangement
    data object Start : Horizontal, Vertical
    data object Center : Horizontal, Vertical
    data object End : Horizontal, Vertical
    data object SpaceBetween : Horizontal, Vertical
    data object SpaceAround : Horizontal, Vertical
    data object SpaceEvenly : Horizontal, Vertical
}

sealed interface Alignment {
    sealed interface Horizontal : Alignment
    sealed interface Vertical : Alignment
    data object Start : Horizontal
    data object Top : Vertical
    data object Center : Horizontal, Vertical
    data object End : Horizontal
    data object Bottom : Vertical
}
```

- [ ] **Step 2.7: `Modifier` interface**

Copy the file from `/tmp/guiy/guiy-compose-master/src/main/kotlin/com/mineinabyss/guiy/modifiers/Modifier.kt` verbatim, change package to `dev.nitka.nodewire.ui.core`. Apache 2 header stays.

- [ ] **Step 2.8: Tests for Color and PaddingValues**

`ColorTest.kt`: verify packing/unpacking, blend, shiftLightness. `PaddingValuesTest.kt`: verify factory variants. Use JUnit 5.

- [ ] **Step 2.9: Build + test + commit**

```
./gradlew build
git add src/main/kotlin/dev/nitka/nodewire/ui src/test
git commit -m "feat(ui): core value types (Color, Shape, IntSize, Modifier)"
```

---

## Phase 3: UiNode + Yoga adapter + NwApplier

**Goal:** Tree data structure where Compose mutations propagate to a Yoga tree. Smoke-tested without rendering.

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/core/UiNode.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/core/NwApplier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/core/YogaAdapter.kt` — utility object: `applyLayoutModifiersToYoga(yoga: YogaNode, modifier: Modifier)`

- [ ] **Step 3.1: Empty modifier-element interfaces (to be filled later)**

In `core/Modifier.kt` add a sealed companion type:

```kotlin
interface LayoutModifier : Modifier.Element<*> {
    fun applyTo(yoga: YogaNode)
}
```

Add `core/StyleModifierElement.kt` interface (marker for non-layout modifiers — used by renderer):

```kotlin
interface StyleModifierElement<Self : StyleModifierElement<Self>> : Modifier.Element<Self>
```

- [ ] **Step 3.2: `UiNode`**

```kotlin
class UiNode {
    val yoga: YogaNode = YogaNode()
    var modifier: Modifier = Modifier
        set(value) {
            field = value
            // Reset Yoga style to defaults, then re-apply layout modifiers in order
            resetYogaStyle(yoga)
            value.foldIn(Unit) { _, el -> if (el is LayoutModifier) el.applyTo(yoga); Unit }
            // Cache style modifiers for renderer
            styleModifiers = value.foldIn(mutableListOf<StyleModifierElement<*>>()) { acc, el ->
                if (el is StyleModifierElement<*>) acc.add(el)
                acc
            }
        }
    var styleModifiers: List<StyleModifierElement<*>> = emptyList()
    var renderer: Renderer = EmptyRenderer  // forward-declared, filled in Phase 4
    var parent: UiNode? = null
    val children = mutableListOf<UiNode>()

    val layoutX get() = yoga.layoutX.toInt()
    val layoutY get() = yoga.layoutY.toInt()
    val layoutWidth get() = yoga.layoutWidth.toInt()
    val layoutHeight get() = yoga.layoutHeight.toInt()
}

private fun resetYogaStyle(yoga: YogaNode) {
    // Set everything to defaults — width/height undefined, padding 0, etc.
    // Use YogaConstants.UNDEFINED for dimensions
    yoga.setWidth(Float.NaN); yoga.setHeight(Float.NaN)
    // Repeat for padding/margin/position/flex/...
}
```

Place a stub `Renderer` interface + `EmptyRenderer` in `core/Renderer.kt`:

```kotlin
interface Renderer { /* methods filled in Phase 4 */ }
val EmptyRenderer = object : Renderer {}
```

- [ ] **Step 3.3: `NwApplier`**

```kotlin
internal class NwApplier(root: UiNode) : AbstractApplier<UiNode>(root) {
    override fun insertTopDown(index: Int, instance: UiNode) {}
    override fun insertBottomUp(index: Int, instance: UiNode) {
        current.children.add(index, instance)
        current.yoga.addChildAt(instance.yoga, index)
        instance.parent = current
    }
    override fun remove(index: Int, count: Int) {
        repeat(count) {
            val child = current.children.removeAt(index)
            current.yoga.removeChildAt(index)
            child.parent = null
        }
    }
    override fun move(from: Int, to: Int, count: Int) {
        // List move helper
        val items = (0 until count).map { current.children[from + it] }
        repeat(count) {
            current.children.removeAt(from)
            current.yoga.removeChildAt(from)
        }
        items.forEachIndexed { i, node ->
            current.children.add(to + i, node)
            current.yoga.addChildAt(node.yoga, to + i)
        }
    }
    override fun onClear() {
        current.children.clear()
        while (current.yoga.childCount > 0) current.yoga.removeChildAt(0)
    }
}
```

- [ ] **Step 3.4: Unit test — applier tree mutations mirror to yoga**

```kotlin
@Test fun applierMirrorsTreeToYoga() {
    val root = UiNode()
    val applier = NwApplier(root)
    val a = UiNode(); val b = UiNode()
    applier.down(root)
    applier.insertBottomUp(0, a)
    applier.insertBottomUp(1, b)
    applier.up()
    assertEquals(2, root.yoga.childCount)
    assertSame(a.yoga, root.yoga.getChildAt(0))
    assertSame(b.yoga, root.yoga.getChildAt(1))

    applier.down(root)
    applier.remove(0, 1)
    applier.up()
    assertEquals(1, root.yoga.childCount)
    assertSame(b.yoga, root.yoga.getChildAt(0))
}
```

- [ ] **Step 3.5: Build + test + commit**

```
./gradlew test
git commit -m "feat(ui): UiNode + NwApplier wired to Yoga"
```

---

## Phase 4: Render core — Renderer interface + NwCanvas

**Goal:** Renderer can draw to MC `GuiGraphics` via `NwCanvas`. Paint walk works.

**Files:**
- Update: `src/main/kotlin/dev/nitka/nodewire/ui/core/Renderer.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/NwCanvas.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/ShapeRenderer.kt`

- [ ] **Step 4.1: Flesh out `Renderer`**

```kotlin
interface Renderer {
    fun NwCanvas.render(node: UiNode) {}
    fun NwCanvas.renderAfterChildren(node: UiNode) {}
}
```

- [ ] **Step 4.2: `NwCanvas`**

```kotlin
class NwCanvas(val gfx: GuiGraphics) {
    private val offsetStack = ArrayDeque<IntOffset>().apply { addLast(IntOffset(0, 0)) }
    val offset get() = offsetStack.last()

    fun pushOffset(dx: Int, dy: Int) {
        val curr = offset
        offsetStack.addLast(IntOffset(curr.x + dx, curr.y + dy))
    }
    fun popOffset() { offsetStack.removeLast() }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Color) {
        gfx.fill(offset.x + x, offset.y + y, offset.x + x + w, offset.y + y + h, color.argb)
    }

    fun drawBorder(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Color) {
        fillRect(x, y, w, thickness, color)
        fillRect(x, y + h - thickness, w, thickness, color)
        fillRect(x, y, thickness, h, color)
        fillRect(x + w - thickness, y, thickness, h, color)
    }

    fun drawText(text: Component, x: Int, y: Int, color: Color, shadow: Boolean = true) {
        gfx.drawString(Minecraft.getInstance().font, text, offset.x + x, offset.y + y, color.argb, shadow)
    }

    fun measureText(text: Component): IntSize {
        val font = Minecraft.getInstance().font
        return IntSize(font.width(text), font.lineHeight)
    }

    fun pushClip(x: Int, y: Int, w: Int, h: Int) {
        gfx.enableScissor(offset.x + x, offset.y + y, offset.x + x + w, offset.y + y + h)
    }
    fun popClip() { gfx.disableScissor() }
}
```

- [ ] **Step 4.3: `ShapeRenderer` — fills + strokes shapes**

`ShapeRenderer.fill(canvas, x, y, w, h, shape, color)` dispatches:
- RectangleShape → `canvas.fillRect`
- RoundedCornerShape → composed of 5 rectangles for body + 4 corner triangles drawn from a static texture atlas at `nodewire:textures/gui/corner_atlas.png`

Generate `corner_atlas.png`: 16×16 PNG with one quadrant of a filled circle, 4-bit antialiasing baked in. Place in `src/main/resources/assets/nodewire/textures/gui/`. The image will be tinted via texture color modulation when drawn.

Stroke similarly: 4 edges + 4 corner arcs. Defer to MVP-shipping if rounded corners are needed; for first deliverable, use rectangle only and skip corner atlas.

- [ ] **Step 4.4: Paint walk in `UiNode`**

```kotlin
fun UiNode.renderWalk(canvas: NwCanvas) {
    canvas.pushOffset(layoutX, layoutY)
    renderer.run { canvas.render(this@renderWalk) }
    children.forEach { it.renderWalk(canvas) }
    renderer.run { canvas.renderAfterChildren(this@renderWalk) }
    canvas.popOffset()
}
```

Place this as an extension in `core/UiNode.kt` (or inside the class).

- [ ] **Step 4.5: Compile-only check (NwCanvas can't be unit-tested without MC)**

Build only. Visual test happens at end of Phase 4 / start of Phase 5.

- [ ] **Step 4.6: Commit**

```
git commit -m "feat(ui): NwCanvas + Renderer interface + paint walk"
```

---

## Phase 5: Coroutine plumbing + NwUiOwner + NwComposeScreen

**Goal:** Smallest end-to-end pipeline — composition runs, draws nothing visible yet (no components), but Screen opens and closes cleanly. We add ONE handcrafted `UiNode` in test code to verify render path.

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/core/NwClientDispatcher.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/core/NwUiOwner.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/core/NwComposeScreen.kt`

- [ ] **Step 5.1: `NwClientDispatcher`**

```kotlin
class NwClientDispatcher : CoroutineDispatcher() {
    private val mc = Minecraft.getInstance()
    override fun isDispatchNeeded(context: CoroutineContext) =
        !mc.isSameThread
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        mc.execute(block)
    }
}
```

- [ ] **Step 5.2: `NwUiOwner`**

```kotlin
class NwUiOwner {
    val root = UiNode()
    private val clock = BroadcastFrameClock { hasFrameWaiters = true }
    private val dispatcher = NwClientDispatcher()
    private val scope = CoroutineScope(dispatcher + clock + SupervisorJob())
    private val recomposer = Recomposer(scope.coroutineContext)
    private val composition = Composition(NwApplier(root), recomposer)
    private var running = false
    private var hasFrameWaiters = false
    private var applyScheduled = false
    private val snapshotHandle = Snapshot.registerGlobalWriteObserver {
        if (!applyScheduled) {
            applyScheduled = true
            scope.launch {
                applyScheduled = false
                Snapshot.sendApplyNotifications()
            }
        }
    }

    fun start(content: @Composable () -> Unit) {
        if (running) return
        running = true
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        composition.setContent { content() }
        hasFrameWaiters = true
    }

    /** Called from Screen.render() each frame. */
    fun frame(canvas: NwCanvas, w: Int, h: Int) {
        if (hasFrameWaiters) {
            hasFrameWaiters = false
            clock.sendFrame(System.nanoTime())
        }
        root.yoga.calculateLayout(w.toFloat(), h.toFloat())
        root.renderWalk(canvas)
    }

    fun dispose() {
        snapshotHandle.dispose()
        composition.dispose()
        recomposer.close()
        scope.cancel()
        running = false
    }
}
```

- [ ] **Step 5.3: `NwComposeScreen`**

```kotlin
abstract class NwComposeScreen(title: Component) : Screen(title) {
    private val owner = NwUiOwner()

    @Composable abstract fun Content()

    override fun init() {
        super.init()
        owner.start { Content() }
    }

    override fun render(gfx: GuiGraphics, mx: Int, my: Int, partial: Float) {
        renderBackground(gfx)
        val canvas = NwCanvas(gfx)
        owner.frame(canvas, width, height)
        super.render(gfx, mx, my, partial)
    }

    override fun removed() {
        owner.dispose()
        super.removed()
    }

    override fun isPauseScreen() = false
}
```

- [ ] **Step 5.4: Smoke test composable**

Create `src/main/kotlin/dev/nitka/nodewire/ui/dev/DemoScreen.kt`:

```kotlin
class DemoScreen : NwComposeScreen(Component.literal("Nodewire Demo")) {
    @Composable override fun Content() {
        // Empty for Phase 5 — we just want it to open without crashing.
    }
}
```

Add a temporary key binding or a chat command-style trigger. Easiest: in `Nodewire.kt`, add a client-side `ClientChatEvent` listener that opens DemoScreen when user types `/nw-demo` in chat. Even simpler for dev: override `LogicBlock.use()` to open `DemoScreen` for now.

Actually simplest: register a Forge key binding. Steps:
1. In `client/NodewireClient.kt` (new file, `@Mod.EventBusSubscriber(modid = Nodewire.ID, value = [Dist.CLIENT], bus = MOD)`), register `KeyMapping("key.nodewire.demo", GLFW.GLFW_KEY_N, "key.categories.nodewire")` via `RegisterKeyMappingsEvent`.
2. In a `@SubscribeEvent fun onClientTick(event: ClientTickEvent)` on FORGE bus, check `mapping.consumeClick()` → `Minecraft.getInstance().setScreen(DemoScreen())`.

- [ ] **Step 5.5: runClient + visual test**

```
./gradlew runClient
```

Enter world → press N → DemoScreen opens (blank). No crash. Press ESC to close. No error in logs.

- [ ] **Step 5.6: Commit**

```
git commit -m "feat(ui): NwUiOwner + NwComposeScreen + demo key binding"
```

---

## Phase 6: Layout primitive + first composables (Box, Spacer)

**Goal:** First @Composable that emits a `UiNode`. Verify Compose-runtime → Applier → Yoga → render pipeline end-to-end.

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/Layout.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/Box.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/Spacer.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/SizeModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/style/BackgroundModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/SurfaceRenderer.kt`

- [ ] **Step 6.1: `Layout` primitive**

```kotlin
@Composable
inline fun Layout(
    modifier: Modifier = Modifier,
    renderer: Renderer = EmptyRenderer,
    noinline yogaConfig: YogaNode.() -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    ComposeNode<UiNode, NwApplier>(
        factory = ::UiNode,
        update = {
            set(modifier) { this.modifier = it }
            set(renderer) { this.renderer = it }
            set(yogaConfig) { yoga.apply(it) }
        },
        content = content,
    )
}
```

- [ ] **Step 6.2: `SizeModifier` (LayoutModifier)**

```kotlin
data class SizeModifier(val width: Int? = null, val height: Int? = null) : Modifier.Element<SizeModifier>, LayoutModifier {
    override fun mergeWith(other: SizeModifier) = SizeModifier(other.width ?: width, other.height ?: height)
    override fun applyTo(yoga: YogaNode) {
        width?.let { yoga.setWidth(it.toFloat()) }
        height?.let { yoga.setHeight(it.toFloat()) }
    }
}

fun Modifier.size(w: Int, h: Int) = this then SizeModifier(w, h)
fun Modifier.size(s: Int) = this then SizeModifier(s, s)
fun Modifier.width(w: Int) = this then SizeModifier(width = w)
fun Modifier.height(h: Int) = this then SizeModifier(height = h)
fun Modifier.fillMaxSize() = this then FillModifier(true, true)
fun Modifier.fillMaxWidth() = this then FillModifier(width = true)
fun Modifier.fillMaxHeight() = this then FillModifier(height = true)

data class FillModifier(val width: Boolean = false, val height: Boolean = false) : Modifier.Element<FillModifier>, LayoutModifier {
    override fun mergeWith(other: FillModifier) = FillModifier(width || other.width, height || other.height)
    override fun applyTo(yoga: YogaNode) {
        if (width) yoga.setWidthPercent(100f)
        if (height) yoga.setHeightPercent(100f)
    }
}
```

- [ ] **Step 6.3: `BackgroundModifier` (style)**

```kotlin
data class BackgroundModifier(val color: Color, val shape: Shape = RectangleShape) : StyleModifierElement<BackgroundModifier> {
    override fun mergeWith(other: BackgroundModifier) = other  // last wins
}

fun Modifier.background(color: Color, shape: Shape = RectangleShape) = this then BackgroundModifier(color, shape)
```

- [ ] **Step 6.4: `SurfaceRenderer`**

Renderer that paints node's bg + border based on its modifier chain:

```kotlin
object SurfaceRenderer : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        node.styleModifiers.filterIsInstance<BackgroundModifier>().lastOrNull()?.let { bg ->
            ShapeRenderer.fill(this, 0, 0, node.layoutWidth, node.layoutHeight, bg.shape, bg.color)
        }
        // border later
    }
}
```

- [ ] **Step 6.5: `Box` + `Spacer`**

```kotlin
@Composable
fun Box(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Layout(modifier = modifier, renderer = SurfaceRenderer, content = content)
}

@Composable
fun Spacer(modifier: Modifier = Modifier) {
    Layout(modifier = modifier, renderer = EmptyRenderer)
}
```

- [ ] **Step 6.6: Update DemoScreen**

```kotlin
@Composable override fun Content() {
    Box(Modifier.size(100, 50).background(Color(0xFF_FF_00_00.toInt()))) {
        Box(Modifier.size(40, 20).background(Color(0xFF_00_FF_00.toInt())))
    }
}
```

- [ ] **Step 6.7: runClient + visual verify**

Press N → see a red 100×50 box at (0,0) with a green 40×20 box inside at (0,0).

- [ ] **Step 6.8: Commit**

```
git commit -m "feat(ui): Layout primitive + Box + Spacer + size/background modifiers"
```

---

## Phase 7: Row + Column + remaining layout modifiers

**Goal:** Full layout-mod surface ready. Row/Column with arrangement/alignment. Padding/margin/offset/flex/absolutePosition/aspectRatio.

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/Row.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/Column.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/PaddingModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/MarginModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/OffsetModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/FlexModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/layout/AspectRatioModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/layout/internal/YogaMapping.kt` (Arrangement→YogaJustify, Alignment→YogaAlign)

- [ ] **Step 7.1: `Arrangement → YogaJustify` mapping**

Trivial when/else mapping. Same for `Alignment → YogaAlign`. Place in `layout/internal/YogaMapping.kt`.

- [ ] **Step 7.2: `Row` + `Column`**

Per spec (see "Container components" section). Both call `Layout(modifier, yogaConfig = { setFlexDirection(ROW/COLUMN); setJustifyContent(...); setAlignItems(...) }, renderer = SurfaceRenderer, content)`.

- [ ] **Step 7.3: Remaining layout modifiers**

Pattern same as `SizeModifier`. Each:
- `PaddingModifier(start, top, end, bottom)` → `yoga.setPadding(LEFT, start.toFloat()); ...`
- `MarginModifier(...)` → analog
- `OffsetModifier(x, y)` → `yoga.setPosition(LEFT, x); setPosition(TOP, y)` (relative position; doesn't change positionType)
- `AbsolutePositionModifier(x, y)` → `setPositionType(ABSOLUTE); setPosition(LEFT, x); setPosition(TOP, y)`
- `FlexModifier(grow, shrink, basis)` → analog
- `WeightModifier(weight)` → `setFlexGrow(weight); setFlexBasis(0)` (shortcut)
- `AspectRatioModifier(ratio)` → `setAspectRatio(ratio)`

Each with `mergeWith` (last-wins) and an extension factory function (`Modifier.padding(...)` etc.).

- [ ] **Step 7.4: Update DemoScreen with rich layout**

```kotlin
@Composable override fun Content() {
    Row(
        modifier = Modifier.fillMaxSize().padding(16).background(Color(0xFF_22_22_28.toInt())),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30).background(Color(0xFF_E8_5C_5C.toInt())))
        Column(verticalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.size(20).background(Color(0xFF_5C_C8_E8.toInt())))
            Box(Modifier.size(20).background(Color(0xFF_E8_C8_5C.toInt())))
            Box(Modifier.size(20).background(Color(0xFF_7C_E8_5C.toInt())))
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF_4A_9E_FF.toInt())))
    }
}
```

- [ ] **Step 7.5: runClient + verify**

Press N → three regions: red 30×30 left, column of three small squares center, blue stretches to fill remaining horizontal width. SpaceBetween puts gaps appropriately.

- [ ] **Step 7.6: Commit**

```
git commit -m "feat(ui): Row/Column + padding/margin/offset/flex/aspectRatio modifiers"
```

---

## Phase 8: Theme system

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwColors.kt` (with `Dark` companion)
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwDimens.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwShapes.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/theme/TextStyle.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwTypography.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwTheme.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwThemeProvider.kt`

- [ ] **Step 8.1: All token data classes**

Verbatim from spec. `@Immutable` from `androidx.compose.runtime.Immutable`. `NwColors.Dark` companion has the full hex palette.

- [ ] **Step 8.2: CompositionLocals + accessor**

```kotlin
val LocalNwColors = staticCompositionLocalOf { NwColors.Dark }
val LocalNwDimens = staticCompositionLocalOf { NwDimens() }
val LocalNwShapes = staticCompositionLocalOf { NwShapes() }
val LocalNwTypography = staticCompositionLocalOf { NwTypography() }

object NwTheme {
    val colors: NwColors @Composable @ReadOnlyComposable get() = LocalNwColors.current
    val dimens: NwDimens @Composable @ReadOnlyComposable get() = LocalNwDimens.current
    val shapes: NwShapes @Composable @ReadOnlyComposable get() = LocalNwShapes.current
    val typography: NwTypography @Composable @ReadOnlyComposable get() = LocalNwTypography.current
}
```

- [ ] **Step 8.3: `NwThemeProvider`**

Per spec.

- [ ] **Step 8.4: Update DemoScreen to wrap content in NwThemeProvider**

```kotlin
@Composable override fun Content() {
    NwThemeProvider {
        Row(Modifier.fillMaxSize().padding(NwTheme.dimens.space16).background(NwTheme.colors.background)) {
            Box(Modifier.size(30).background(NwTheme.colors.accent))
            // ...
        }
    }
}
```

- [ ] **Step 8.5: Commit**

```
git commit -m "feat(ui): theme tokens + CompositionLocals + NwThemeProvider"
```

---

## Phase 9: Text + Icon components

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/TextRenderer.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/components/Text.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/render/IconRenderer.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/components/Icon.kt`

- [ ] **Step 9.1: `TextRenderer`**

Class capturing `text: Component`, `style: TextStyle`. `render(node)` calls `canvas.drawText(text, 0, 0, style.color ?: NwTheme.colors.onSurface, style.shadow)`.

Subtle: we need `NwTheme.colors.onSurface` at composition time, not render time, because Composable reads can't happen inside renderer. So the Text composable resolves the color and bakes it into TextRenderer.

- [ ] **Step 9.2: `Text` composable**

```kotlin
@Composable
fun Text(text: String, modifier: Modifier = Modifier, style: TextStyle = NwTheme.typography.body) {
    val component = remember(text) { Component.literal(text) }
    val color = style.color ?: NwTheme.colors.onSurface
    val font = Minecraft.getInstance().font
    Layout(
        modifier = modifier,
        renderer = TextRenderer(component, style, color),
        yogaConfig = {
            setMeasureFunction { _, w, _, _, _ ->
                YogaSize(font.width(component).toFloat().coerceAtMost(w), font.lineHeight.toFloat())
            }
        },
    )
}
```

- [ ] **Step 9.3: `Icon`**

```kotlin
@Composable
fun Icon(loc: ResourceLocation, modifier: Modifier = Modifier.size(NwTheme.dimens.iconMedium), tint: Color? = null) {
    val resolvedTint = tint ?: NwTheme.colors.onSurface
    Layout(
        modifier = modifier,
        renderer = IconRenderer(loc, resolvedTint),
    )
}
```

`IconRenderer` calls `gfx.blit(loc, x, y, 0, 0, w, h)` with tint via shader color.

- [ ] **Step 9.4: Update DemoScreen with Text**

```kotlin
Column {
    Text("Title", style = NwTheme.typography.title)
    Text("Subtitle", style = NwTheme.typography.subtitle)
    Text("Body text here")
    Text("Caption", style = NwTheme.typography.caption)
}
```

- [ ] **Step 9.5: Verify in runClient + commit**

```
git commit -m "feat(ui): Text + Icon components"
```

---

## Phase 10: Input — pointer events + clickable + onSizeChanged

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/PointerEvent.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/KeyEvent.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/HitTester.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/input/ClickModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/input/PointerInputModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/input/OnSizeChangedModifier.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/modifier/input/OnHoverModifier.kt`
- Update: `NwComposeScreen` to forward mouse/key
- Update: `NwUiOwner` with `dispatchPointer` etc.

- [ ] **Step 10.1: Pointer/Key event types** — sealed classes (Press, Release, Move, Drag, Scroll, KeyPress, KeyRelease, Char). Each carries position (in screen space), button/scroll/key data.

- [ ] **Step 10.2: `ClickModifier`** — fires `onClick` when pointer Press lands inside node bounds.

```kotlin
data class ClickModifier(val onClick: () -> Unit, val consume: Boolean = true) : StyleModifierElement<ClickModifier>, PointerHandler {
    override fun mergeWith(other: ClickModifier) = other
    override fun handle(event: PointerEvent, localX: Int, localY: Int): Boolean {
        if (event is PointerEvent.Press) { onClick(); return consume }
        return false
    }
}
fun Modifier.clickable(onClick: () -> Unit) = this then ClickModifier(onClick)
```

- [ ] **Step 10.3: `HitTester`**

```kotlin
fun UiNode.hitTest(event: PointerEvent, parentOffsetX: Int = 0, parentOffsetY: Int = 0): Boolean {
    val absX = parentOffsetX + layoutX
    val absY = parentOffsetY + layoutY
    if (event.x !in absX until (absX + layoutWidth)) return false
    if (event.y !in absY until (absY + layoutHeight)) return false
    // children first (front-most wins)
    for (child in children.reversed()) {
        if (child.hitTest(event, absX, absY)) return true
    }
    // self
    val handlers = styleModifiers.filterIsInstance<PointerHandler>()
    for (h in handlers) {
        if (h.handle(event, event.x - absX, event.y - absY)) return true
    }
    return false
}
```

- [ ] **Step 10.4: `OnSizeChangedModifier`**

Stores last-known size; in `UiNode.measure` (or post-yoga-calculate hook), compares; if changed, invokes callback.

Implementation: add a post-frame walk in NwUiOwner that fires size-change callbacks. Or: track per UiNode `lastSize` and check inside renderer (since renderer runs after layout).

- [ ] **Step 10.5: `PointerInputModifier`**

Lower-level — handler receives all pointer events while focus owner. Used for canvas-style drag.

```kotlin
class PointerInputModifier(val handler: (PointerEvent) -> Boolean) : StyleModifierElement<PointerInputModifier>, PointerHandler {
    override fun mergeWith(other: PointerInputModifier) = other
    override fun handle(event: PointerEvent, localX: Int, localY: Int) = handler(event)
}
```

- [ ] **Step 10.6: Drag focus owner**

`NwUiOwner` tracks `pointerFocus: UiNode?`. On Press → first node consuming Press becomes focus. On Move/Drag/Release → routed directly to focus owner (don't re-hit-test). On Release → focus cleared.

- [ ] **Step 10.7: Wire `NwComposeScreen` input**

```kotlin
override fun mouseClicked(x: Double, y: Double, btn: Int): Boolean {
    return owner.dispatchPointer(PointerEvent.Press(x.toInt(), y.toInt(), btn)) || super.mouseClicked(x, y, btn)
}
override fun mouseReleased(x, y, btn) = owner.dispatchPointer(PointerEvent.Release(...)) || super...
override fun mouseDragged(x, y, btn, dx, dy) = owner.dispatchPointer(PointerEvent.Drag(...)) || super...
override fun mouseMoved(x, y) { owner.dispatchPointer(PointerEvent.Move(...)) }
override fun mouseScrolled(x, y, dx, dy) = owner.dispatchPointer(PointerEvent.Scroll(...)) || super...
```

- [ ] **Step 10.8: Update DemoScreen with clickable**

```kotlin
var count by remember { mutableStateOf(0) }
Box(Modifier.size(60).background(NwTheme.colors.accent).clickable { count++ }) {
    Text("$count")
}
```

- [ ] **Step 10.9: Verify in runClient + commit**

Click increments. Commit.

---

## Phase 11: Button + Surface components with styling layer 2

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/components/Surface.kt` (+ SurfaceStyle, SurfaceDefaults)
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/components/Button.kt` (+ ButtonStyle, ButtonDefaults)
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/components/Divider.kt`
- Update: `OnHoverModifier` if not in Phase 10

- [ ] **Step 11.1: `SurfaceStyle` + `SurfaceDefaults` + `Surface`** per spec

```kotlin
data class SurfaceStyle(val color: Color, val shape: Shape, val border: BorderStroke?, val padding: PaddingValues)
object SurfaceDefaults {
    @Composable fun default() = SurfaceStyle(NwTheme.colors.surface, NwTheme.shapes.medium, null, PaddingValues(0))
    @Composable fun elevated() = SurfaceStyle(NwTheme.colors.surfaceHover, NwTheme.shapes.medium, null, PaddingValues(0))
    @Composable fun outlined() = SurfaceStyle(NwTheme.colors.surface, NwTheme.shapes.medium, BorderStroke(1, NwTheme.colors.border), PaddingValues(0))
    @Composable fun transparent() = SurfaceStyle(Color.Transparent, NwTheme.shapes.medium, null, PaddingValues(0))
}

@Composable
fun Surface(modifier: Modifier = Modifier, style: SurfaceStyle = SurfaceDefaults.default(), content: @Composable () -> Unit) {
    Box(modifier
        .background(style.color, style.shape)
        .let { style.border?.let { b -> it.border(b, style.shape) } ?: it }
        .padding(style.padding)
    ) { content() }
}
```

`Modifier.border(BorderStroke, Shape)` and `Modifier.padding(PaddingValues)` need to exist — add minimal overloads.

- [ ] **Step 11.2: `Button`**

Tracks hover/pressed state via `pointerInput`. Renders `Surface` with style fields varying by state.

```kotlin
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonDefaults.filled(),
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> style.containerDisabled
        pressed -> style.containerPressed
        hovered -> style.containerHover
        else -> style.container
    }
    val content_color = if (enabled) style.content else style.contentDisabled
    Surface(
        modifier = modifier
            .pointerInput { ev ->
                when (ev) {
                    is PointerEvent.Move -> { hovered = true; false }
                    is PointerEvent.Exit -> { hovered = false; false }
                    is PointerEvent.Press -> { pressed = true; false }
                    is PointerEvent.Release -> { pressed = false; if (enabled) onClick(); true }
                    else -> false
                }
            },
        style = SurfaceStyle(bg, style.shape, style.border, style.padding),
    ) {
        CompositionLocalProvider(LocalContentColor provides content_color) {
            content()
        }
    }
}
```

`LocalContentColor` is a new `compositionLocalOf` so `Text` inside Button uses the proper foreground. `Text` reads `style.color ?: LocalContentColor.current ?: NwTheme.colors.onSurface`.

- [ ] **Step 11.3: `Divider`** — simple `Box` filled with `NwTheme.colors.divider`, default thickness 1, fillMaxWidth or fillMaxHeight based on orientation.

- [ ] **Step 11.4: Update DemoScreen** to showcase all button variants

```kotlin
Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.padding(16)) {
    Text("Buttons", style = NwTheme.typography.title)
    Row(horizontalArrangement = Arrangement.spacedBy(8)) {
        Button(onClick = {}) { Text("Filled") }
        Button(onClick = {}, style = ButtonDefaults.outlined()) { Text("Outlined") }
        Button(onClick = {}, style = ButtonDefaults.ghost()) { Text("Ghost") }
        Button(onClick = {}, style = ButtonDefaults.danger()) { Text("Danger") }
    }
}
```

Note: `Arrangement.spacedBy(8)` needs a small addition — add it to `Arrangement` as a data class case mapping to YogaGap (`yoga.setGap(ALL, n.toFloat())`). Implement.

- [ ] **Step 11.5: Verify + commit**

---

## Phase 12: Tooltip + cleanup + AC verification

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/components/Tooltip.kt`
- Update: DemoScreen with all components for visual sanity check

- [ ] **Step 12.1: `Tooltip`**

Tracks `hovered` + delay via `LaunchedEffect`. When timer expires, renders content at cursor in an `overlay` render pass.

Overlay rendering: NwUiOwner needs a second pass for "popup" nodes that render last (z-order top). Simple approach: a `CompositionLocal<MutableList<TooltipContent>>` collected during composition; rendered after `root.renderWalk` in `frame()`.

- [ ] **Step 12.2: Comprehensive DemoScreen**

All components + all ButtonStyles + theme switching button + counter + tooltip on hover.

- [ ] **Step 12.3: Run all AC tests manually in dev**

Walk through AC1–AC12 from spec, document results in `docs/superpowers/notes/2026-05-13-ac-results.md`.

- [ ] **Step 12.4: Final commit**

```
git commit -m "feat(ui): Tooltip + complete demo screen; AC1-AC12 verified"
```

---

## Total estimate

12 phases × 1–2 days each = 2–3 weeks of focused work. Subagent dispatch model means each phase is one self-contained subagent invocation with spec link, file list, and step list.

After this plan is complete, Phase 13 onward = node editor MVP, built on this framework.
