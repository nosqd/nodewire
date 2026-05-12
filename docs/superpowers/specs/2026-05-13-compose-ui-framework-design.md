# Nodewire — Compose UI Framework Design

**Date:** 2026-05-13
**Status:** Draft (v2 — Yoga layout)
**Scope:** Custom Jetpack Compose **runtime-only** integration for Minecraft client Screens. Provides `@Composable` API, state/recomposition, layout via **AppliedEnergistics/yoga** (pure-Java flexbox), modifiers, theming, and rendering through Minecraft's `GuiGraphics`. **No Skiko, no AWT, no Compose UI module.**

Reference architectures:
- [MineInAbyss/guiy-compose](https://github.com/MineInAbyss/guiy-compose) — for Compose runtime + Applier pattern
- [AppliedEnergistics/yoga](https://github.com/AppliedEnergistics/yoga) — for flexbox layout (replaces custom MeasurePolicy)

This spec is a **prerequisite** for the node editor MVP.

---

## Why this approach (not Skiko)

| Concern | `compose-runtime` only | Compose Multiplatform (Skiko) |
|---|---|---|
| Native deps | Pure JVM (~2 MB) | Skia native binaries (~50 MB, per-OS) |
| GL context | Reuses Minecraft's | Needs own context, blit / share is fragile |
| Mod compatibility | Drawing through MC primitives → no conflicts | GL state collisions w/ Create/Flywheel/Iris/Oculus |
| Maturity for MC | guiy-compose proven on Paper, pattern transfers | VexorMC/compose: 4 commits, experimental |
| Build complexity | Just compiler plugin + 1 runtime dep | Forking + repackaging Skiko per OS |

We use **real Jetpack Compose Runtime + Compiler** (Google's, via `org.jetbrains.compose.runtime`). What we write ourselves: the `Applier`, layout, modifier system, rendering, input. That's the boundary.

---

## What Compose provides for us (free)

- `@Composable` annotation + compiler plugin transforms
- `Composition` lifecycle
- `Recomposer` (smart recomposition based on snapshot reads)
- `mutableStateOf`, `remember`, `derivedStateOf`
- `CompositionLocal` (DI/scoping)
- `LaunchedEffect`, `DisposableEffect`, `SideEffect`
- `BroadcastFrameClock` (drives `withFrameNanos`, animations)
- `AbstractApplier<T>` — base class for our applier
- `ComposeNode<T, A>` — primitive for emitting nodes

---

## What we write

```
┌─────────────────────────────────────────────────────────────┐
│ User code: @Composable fun MyScreen() { Column { ... } }    │
└─────────────────────────────────────────────────────────────┘
                          │ compose compiler
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ Composition + Recomposer  (provided by compose.runtime)     │
└─────────────────────────────────────────────────────────────┘
                          │ tree mutations
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ NwApplier : AbstractApplier<UiNode>   ◀── we write           │
│   insertBottomUp / remove / move / onClear                  │
└─────────────────────────────────────────────────────────────┘
                          │ owns
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ UiNode tree   ◀── we write                                   │
│   modifier, measurePolicy, renderer, children                │
└─────────────────────────────────────────────────────────────┘
            ▲                          │
            │ measure/place            │ render
            │                          ▼
┌──────────────────────┐    ┌─────────────────────────────────┐
│ Layout system        │    │ Renderer → GuiGraphics          │
│   Modifier, Measure  │    │   (text, rect, texture, line)   │
└──────────────────────┘    └─────────────────────────────────┘
            ▲                          ▲
            │ input events             │ frame tick
            │                          │
┌─────────────────────────────────────────────────────────────┐
│ NwComposeScreen extends MC Screen       ◀── we write         │
│   bridges MC lifecycle/input/render to NwUiOwner            │
└─────────────────────────────────────────────────────────────┘
```

---

## Out of scope

- Animations (will work if user uses `animate*AsState` since runtime supports it, but we don't expose extra animation APIs in MVP)
- Text input fields (deferred — needs keyboard composition, IME etc.)
- Focus/keyboard navigation (deferred)
- Accessibility
- RTL layout direction (LTR only)
- Compose UI's `Modifier.Node` system (we use simple element list like guiy-compose; can refactor later)
- Save/restore composition state across screen close (each open = fresh composition)

---

## Module structure

```
src/main/kotlin/dev/nitka/nodewire/ui/
  core/
    Modifier.kt              # interface + CombinedModifier (Apache 2 copy)
    UiNode.kt                # wraps YogaNode, holds modifier/renderer/measureFn
    NwApplier.kt             # AbstractApplier<UiNode>; mirrors to Yoga tree
    NwUiOwner.kt             # Composition + Recomposer + frame loop
    NwComposeScreen.kt       # MC Screen → NwUiOwner bridge
    NwClientDispatcher.kt    # CoroutineDispatcher posting to MC client thread
  layout/
    Layout.kt                # @Composable Layout(modifier, yogaConfig, measureFn, renderer, content)
    Box.kt, Row.kt, Column.kt, Spacer.kt
    Arrangement.kt           # Arrangement.{Start,Center,End,SpaceBetween,...} → YogaJustify
    Alignment.kt             # Alignment.{Start,Center,End} → YogaAlign
    PaddingValues.kt
  modifier/
    layout/
      SizeModifier.kt        # .size/.width/.height/.fillMaxSize/.widthIn/.heightIn
      PaddingModifier.kt     # .padding(...)
      MarginModifier.kt      # .margin(...)
      OffsetModifier.kt      # .offset/.absolutePosition
      FlexModifier.kt        # .flex/.weight/.flexGrow/.flexShrink
      AspectRatioModifier.kt
    style/
      BackgroundModifier.kt  # .background(color, shape)
      BorderModifier.kt      # .border(stroke, shape)
      ShadowModifier.kt      # .shadow(elevation)  (drop-shadow rect)
    input/
      ClickModifier.kt       # .clickable { }
      PointerInputModifier.kt # .pointerInput { drag/move/scroll }
      OnSizeChangedModifier.kt
      OnHoverModifier.kt
  render/
    Renderer.kt              # interface
    NwCanvas.kt              # offset/clip stack over GuiGraphics
    Color.kt                 # ARGB value class
    Shape.kt                 # RectangleShape, RoundedCornerShape(radius)
    BorderStroke.kt
    TextRenderer.kt
    SurfaceRenderer.kt       # bg + border + shape (most common)
  input/
    PointerEvent.kt          # press/release/move/drag/scroll
    KeyEvent.kt
    HitTester.kt
  theme/
    NwColors.kt, NwDimens.kt, NwShapes.kt, NwTypography.kt, TextStyle.kt
    NwTheme.kt               # CompositionLocals + access object
    NwThemeProvider.kt
  components/
    Text.kt
    Icon.kt
    Button.kt + ButtonStyle.kt + ButtonDefaults.kt
    Surface.kt + SurfaceStyle.kt + SurfaceDefaults.kt
    Divider.kt
    Tooltip.kt
```

---

## Core types

### `UiNode`

```kotlin
class UiNode {
    var modifier: Modifier = Modifier
        set(value) {
            field = value
            // pre-compute modifier maps for quick lookup
        }
    var measurePolicy: MeasurePolicy = DefaultMeasurePolicy
    var renderer: Renderer = EmptyRenderer

    var parent: UiNode? = null
    val children = mutableListOf<UiNode>()

    var x = 0; var y = 0
    var width = 0; var height = 0

    fun measure(constraints: Constraints): Placeable { /* ... */ }
    fun placeAt(x: Int, y: Int) { /* ... */ }
    fun renderTo(canvas: NwCanvas) { /* ... */ }
    fun hitTest(scope: PointerEvent): Boolean { /* ... */ }
}
```

Pattern lifted directly from guiy-compose `LayoutNode`. Comments in guiy admit the structure isn't final — we adopt the same simplification (single node class, configurable via modifier/policy/renderer) because the alternative (separate Layout/Render nodes) doesn't pay for itself at MVP scale.

### `NwApplier`

```kotlin
internal class NwApplier(root: UiNode) : AbstractApplier<UiNode>(root) {
    override fun insertTopDown(index: Int, instance: UiNode) {} // unused
    override fun insertBottomUp(index: Int, instance: UiNode) {
        current.children.add(index, instance)
        instance.parent = current
    }
    override fun remove(index: Int, count: Int) {
        repeat(count) { current.children.removeAt(index) }
    }
    override fun move(from: Int, to: Int, count: Int) { /* list move helper */ }
    override fun onClear() { current.children.clear() }
}
```

### `NwUiOwner`

Owns: a root `UiNode`, the `Composition`, `Recomposer`, `BroadcastFrameClock`, a `CoroutineScope`.

Differences from guiy-compose:
- **Dispatcher**: `Dispatchers.Main.immediate` equivalent for MC's client thread. MC has no built-in "main dispatcher", so we implement `NwClientDispatcher` that posts to `Minecraft.getInstance().execute { }` (which queues to client tick).
- **Frame loop**: Driven by MC's render tick (`Screen.render` called every frame). On each MC frame: if `hasFrameWaiters`, call `clock.sendFrame(System.nanoTime())`, measure root, render to `GuiGraphics`. No 10ms `delay()` polling — MC drives the frame rate.
- **Snapshot writes**: `Snapshot.registerGlobalWriteObserver { schedule applyNotifications on next tick }` (same pattern).
- **Lifecycle**: tied to Screen, not Player. `dispose()` called from `Screen.removed()`.

### `NwComposeScreen`

```kotlin
abstract class NwComposeScreen(title: Component) : Screen(title) {
    private val owner = NwUiOwner()

    @Composable abstract fun Content()

    override fun init() {
        super.init()
        owner.start(Content = { Content() })  // begins composition
    }

    override fun render(gfx: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(gfx)
        owner.frame(NwCanvas(gfx), Constraints(maxWidth = width, maxHeight = height))
        super.render(gfx, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(x: Double, y: Double, btn: Int): Boolean {
        return owner.dispatchPointer(PointerEvent.Press(x.toInt(), y.toInt(), btn)) || super.mouseClicked(x, y, btn)
    }
    // ...mouseReleased, mouseDragged, mouseScrolled, keyPressed, charTyped...

    override fun removed() {
        owner.dispose()
        super.removed()
    }

    override fun isPauseScreen() = false
}
```

User subclasses this and overrides `Content()` with `@Composable` body. That's the entire integration surface.

---

## Modifier API

Copied from Compose UI (Apache 2). Same `foldIn`/`foldOut`/`then`/`Element<Self>` pattern. Each modifier is a class implementing `Modifier.Element<Self>`. UiNode pre-processes the modifier chain into:
- `processedModifier: Map<KClass, Element>` — for O(1) lookup by type
- `layoutChangingModifiers: List<LayoutChangingModifier>` — ordered chain for measure/place transformations

```kotlin
interface LayoutChangingModifier : Modifier.Element<*> {
    fun modifyInnerConstraints(constraints: Constraints): Constraints = constraints
    fun modifyLayoutConstraints(size: IntSize, outerConstraints: Constraints): Constraints = outerConstraints
    fun modifyPosition(offset: IntOffset): IntOffset = offset
}
```

Padding, size, offset are `LayoutChangingModifier`s. Click, background, border are not (they don't change measurement).

### MVP modifiers

| API | Behavior |
|---|---|
| `.size(w, h)` / `.width(w)` / `.height(h)` | Fixed size |
| `.fillMaxSize()` / `.fillMaxWidth()` / `.fillMaxHeight()` | Expand to constraints |
| `.padding(all)` / `.padding(h, v)` / `.padding(start, top, end, bottom)` | Inner padding |
| `.offset(x, y)` | Translate placement |
| `.background(Color)` / `.background(Color, Shape)` | Solid fill |
| `.border(width, Color)` / `.border(width, Color, Shape)` | Stroke |
| `.clickable { onClick() }` | Mouse press handler |
| `.pointerInput { /* drag/move/scroll */ }` | Lower-level pointer access for canvas/wires |
| `.onSizeChanged { size -> }` | Callback when measured size changes |

---

## Layout: flexbox via Yoga

We use `org.appliedenergistics.yoga:yoga:1.0.0` — a pure-Java port of Facebook Yoga (same flexbox engine that powers React Native, Litho, Compose for native). MIT licensed, no native deps, on Maven Central.

**Why Yoga, not custom MeasurePolicy:**
- Standard flexbox semantics — no surprises, no reinventing wheels
- Test-covered by hundreds of upstream Yoga conformance tests
- Performant (incremental layout: only dirty subtrees recalculate)
- Direct mapping for what we need: `flex-direction`, `justify-content`, `align-items`, `gap`, `padding`, `margin`, `position: absolute` (for node canvas), `flex-grow/shrink/basis`
- AE2 maintains this fork actively (v1.0.0 March 2025) for their own UI framework

### How it integrates with our `UiNode`

Each `UiNode` owns a `YogaNode`. Compose Applier mirrors child structure to Yoga children. Modifiers translate to YogaNode setters. Leaf nodes with intrinsic size (Text, Icon) set `measureFunction`.

```kotlin
class UiNode {
    val yoga: YogaNode = YogaNode()  // 1:1 with this node
    var modifier: Modifier = Modifier
        set(value) {
            field = value
            applyModifierToYoga()       // re-applies layout modifiers
            applyModifierForRenderer()  // updates style cache for paint
        }
    var renderer: Renderer = EmptyRenderer
    var measureFn: ((maxW: Float, maxH: Float) -> Size)? = null
        set(value) {
            field = value
            yoga.setMeasureFunction(value?.let { fn -> YogaMeasureFunction { _, w, _, h, _ -> fn(w, h).toYogaSize() } })
        }

    var parent: UiNode? = null
    val children = mutableListOf<UiNode>()

    // Layout results read after parent calculateLayout
    val layoutX get() = yoga.layoutX.toInt()
    val layoutY get() = yoga.layoutY.toInt()
    val layoutWidth get() = yoga.layoutWidth.toInt()
    val layoutHeight get() = yoga.layoutHeight.toInt()
}
```

### Applier integration

```kotlin
internal class NwApplier(root: UiNode) : AbstractApplier<UiNode>(root) {
    override fun insertBottomUp(index: Int, instance: UiNode) {
        current.children.add(index, instance)
        instance.parent = current
        current.yoga.addChildAt(instance.yoga, index)   // mirror to Yoga
    }
    override fun remove(index: Int, count: Int) {
        repeat(count) {
            val child = current.children.removeAt(index)
            current.yoga.removeChildAt(index)
            child.parent = null
        }
    }
    // move, onClear similarly mirror to yoga
}
```

### Frame pass (once per render)

```kotlin
fun NwUiOwner.frame(canvas: NwCanvas, screenW: Int, screenH: Int) {
    root.yoga.calculateLayout(screenW.toFloat(), screenH.toFloat())  // single call lays out entire tree
    paintWalk(root, canvas)
}

private fun paintWalk(node: UiNode, canvas: NwCanvas) {
    val sub = canvas.translated(node.layoutX, node.layoutY)
    node.renderer.run { sub.render(node) }
    node.children.forEach { paintWalk(it, sub) }
    node.renderer.run { sub.renderAfterChildren(node) }
}
```

That's the entire layout pipeline. No measure/place recursion of our own.

### Layout-touching modifiers (translate to YogaNode setters)

| Kotlin API | Yoga call |
|---|---|
| `Modifier.size(w, h)` | `setWidth(w); setHeight(h)` |
| `Modifier.width(w)` / `Modifier.height(h)` | `setWidth/Height(...)` |
| `Modifier.widthIn(min, max)` | `setMinWidth/setMaxWidth(...)` |
| `Modifier.fillMaxSize()` | `setWidthPercent(100); setHeightPercent(100)` |
| `Modifier.padding(all)` | `setPadding(ALL, all)` |
| `Modifier.padding(h, v)` / `padding(start, top, end, bottom)` | `setPadding` per edge |
| `Modifier.margin(...)` | `setMargin(...)` (same per-edge pattern) |
| `Modifier.gap(value)` | `setGap(ALL, value)` (on container) |
| `Modifier.flex(grow, shrink, basis)` | `setFlexGrow/Shrink/Basis(...)` |
| `Modifier.weight(w)` | `setFlexGrow(w); setFlexBasis(0)` (Compose-style shorthand) |
| `Modifier.absolutePosition(x, y)` | `setPositionType(ABSOLUTE); setPosition(LEFT, x); setPosition(TOP, y)` |
| `Modifier.aspectRatio(ratio)` | `setAspectRatio(ratio)` |

**Style-only modifiers** (don't affect layout — collected for renderer): `background`, `border`, `clickable`, `pointerInput`, `onSizeChanged`, `shadow`.

### Container components

Each is a one-liner using `Layout` + Yoga config:

```kotlin
@Composable inline fun Box(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) =
    Layout(modifier = modifier, yogaConfig = {}, content = content)  // default: flex-direction=COLUMN, align=STRETCH

@Composable inline fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable () -> Unit,
) = Layout(modifier, yogaConfig = {
    setFlexDirection(YogaFlexDirection.ROW)
    setJustifyContent(horizontalArrangement.toYogaJustify())
    setAlignItems(verticalAlignment.toYogaAlign())
}, content = content)

@Composable inline fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) = Layout(modifier, yogaConfig = {
    setFlexDirection(YogaFlexDirection.COLUMN)
    setJustifyContent(verticalArrangement.toYogaJustify())
    setAlignItems(horizontalAlignment.toYogaAlign())
}, content = content)
```

`Arrangement.{Start,Center,End,SpaceBetween,SpaceAround,SpaceEvenly}` map directly to `YogaJustify.{FLEX_START,CENTER,FLEX_END,SPACE_BETWEEN,SPACE_AROUND,SPACE_EVENLY}`. `Alignment.{Start,Center,End}` → `YogaAlign.{FLEX_START,CENTER,FLEX_END}`. One-to-one. No translation logic worth bug-fixing.

### Absolute positioning for node-editor canvas

Node editor's canvas is a `Box` (relative parent). Each draggable Node is a child with `Modifier.absolutePosition(x, y)` where `(x,y)` are mutable state, recomposed when dragged. Yoga handles overlap/z-order via insertion order.

### Leaf nodes with intrinsic size

Text/Icon set `measureFn`:

```kotlin
@Composable
fun Text(text: String, modifier: Modifier = Modifier, style: TextStyle = NwTheme.typo.body) {
    val font = LocalFont.current
    Layout(
        modifier = modifier,
        measureFn = { maxW, _ ->
            val w = font.width(text).toFloat().coerceAtMost(maxW)
            val h = font.lineHeight.toFloat()
            Size(w, h)
        },
        renderer = TextRenderer(text, style),
    )
}
```

Yoga calls `measureFn` during `calculateLayout` for sizing; renderer uses the laid-out size to draw.

---

## Renderer / canvas

`Renderer`:
```kotlin
interface Renderer {
    fun NwCanvas.render(node: UiNode) {}
    fun NwCanvas.renderAfterChildren(node: UiNode) {}
}
val EmptyRenderer = object : Renderer {}
```

`NwCanvas`:
```kotlin
class NwCanvas(private val gfx: GuiGraphics) {
    private var offX = 0; private var offY = 0

    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Color)
    fun drawBorder(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Color)
    fun drawText(text: Component, x: Int, y: Int, color: Color = NwColors.text)
    fun measureText(text: Component): IntSize
    fun drawTexture(loc: ResourceLocation, x: Int, y: Int, w: Int, h: Int, u: Int = 0, v: Int = 0)
    fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int, color: Color, thickness: Int = 1)
    fun pushOffset(dx: Int, dy: Int): NwCanvas  // returns same canvas with offset baked in
    fun popOffset()
    fun pushClip(x: Int, y: Int, w: Int, h: Int)
    fun popClip()
}
```

Renderers are stateless objects attached to nodes via `Layout(renderer = MyRenderer)`. Standard composables instantiate their own renderer (e.g. `Box(background = Red)` → renderer that fills the node's rect).

### Why route through NwCanvas (not raw GuiGraphics)

- Single place to handle offset stack (recursive children with translated coords)
- Future-proof: can be swapped for buffered/cached rendering if perf matters
- Easier to test in isolation
- Clip support built-in (`GuiGraphics.enableScissor`)

---

## Input system

`NwComposeScreen` overrides MC `Screen` mouse/key methods, converts to our `PointerEvent` / `KeyEvent`, dispatches via `owner.dispatchPointer(event)`.

Dispatch walks the UI tree top-down (front-most child first) doing hit-testing. First node whose bounds contain the pointer AND has a matching modifier (Clickable, PointerInput) receives the event. If it returns `consumed=true`, propagation stops. Otherwise event bubbles to parent.

For drag, we track "active pointer owner" per button: on press, the consuming node becomes owner; subsequent move/release events route directly to it until release (so drag-out-of-bounds works for connecting wires, dragging nodes, etc.).

---

## Styling system

Three-layer architecture, same as Material 3 / shadcn / Tailwind component libraries:

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Design tokens   (theme values, in CompositionLocal)│
│   NwColors, NwDimens, NwTypography, NwShapes                │
└─────────────────────────────────────────────────────────────┘
                          ▲ read by
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Component defaults  (per-component data classes)   │
│   ButtonStyle, SurfaceStyle, TextFieldStyle, …              │
│   Built from tokens; each component has a `Defaults` object │
└─────────────────────────────────────────────────────────────┘
                          ▲ accepted by
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Call site     (per-instance override)              │
│   Button(style = ButtonDefaults.outlined()) { ... }         │
│   Modifier.background(...)  // raw override on top          │
└─────────────────────────────────────────────────────────────┘
```

### Layer 1: design tokens

```kotlin
@Immutable
data class NwColors(
    // Surfaces
    val background: Color,           // screen bg behind everything
    val surface: Color,              // panel/card bg
    val surfaceHover: Color,
    val surfacePressed: Color,
    val overlay: Color,              // semi-transparent for modals/tooltips
    // Borders / dividers
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    // Text
    val onSurface: Color,            // primary text on surface
    val onSurfaceMuted: Color,       // secondary text
    val onSurfaceDisabled: Color,
    // Brand / state
    val accent: Color,               // primary CTA color
    val accentHover: Color,
    val accentPressed: Color,
    val onAccent: Color,             // text/icon on accent bg
    val danger: Color,
    val warning: Color,
    val success: Color,
    // Domain-specific (pin types)
    val pinBool: Color,
    val pinInt: Color,
    val pinFloat: Color,
    val pinVec2: Color,
    val pinVec3: Color,
    val pinQuat: Color,
) {
    companion object {
        val Dark = NwColors(
            background = Color(0xE0_10_10_14),
            surface    = Color(0xFF_1C_1C_22),
            surfaceHover   = Color(0xFF_24_24_2C),
            surfacePressed = Color(0xFF_18_18_1E),
            overlay = Color(0xCC_00_00_00),
            border         = Color(0xFF_30_30_38),
            borderStrong   = Color(0xFF_50_50_5C),
            divider        = Color(0xFF_28_28_30),
            onSurface         = Color(0xFF_E8_E8_EC),
            onSurfaceMuted    = Color(0xFF_94_94_A0),
            onSurfaceDisabled = Color(0xFF_54_54_60),
            accent         = Color(0xFF_4A_9E_FF),
            accentHover    = Color(0xFF_6A_B0_FF),
            accentPressed  = Color(0xFF_30_88_E8),
            onAccent       = Color(0xFF_FF_FF_FF),
            danger  = Color(0xFF_E8_5C_5C),
            warning = Color(0xFF_E8_B0_4A),
            success = Color(0xFF_5C_C8_7A),
            pinBool  = Color(0xFF_E8_5C_5C),
            pinInt   = Color(0xFF_5C_C8_E8),
            pinFloat = Color(0xFF_E8_C8_5C),
            pinVec2  = Color(0xFF_7C_E8_5C),
            pinVec3  = Color(0xFF_AC_E8_5C),
            pinQuat  = Color(0xFF_C8_7C_E8),
        )
    }
}

@Immutable
data class NwDimens(
    val space2: Int = 2, val space4: Int = 4, val space6: Int = 6,
    val space8: Int = 8, val space12: Int = 12, val space16: Int = 16,
    val space24: Int = 24, val space32: Int = 32,
    val cornerSmall: Int = 2,
    val cornerMedium: Int = 4,
    val cornerLarge: Int = 6,
    val borderThin: Int = 1,
    val borderThick: Int = 2,
    val iconSmall: Int = 12, val iconMedium: Int = 16, val iconLarge: Int = 24,
)

@Immutable
data class NwShapes(
    val rect: Shape = RectangleShape,
    val small: Shape = RoundedCornerShape(2),
    val medium: Shape = RoundedCornerShape(4),
    val large: Shape = RoundedCornerShape(6),
    val pill: Shape = RoundedCornerShape(999),
)

@Immutable
data class TextStyle(
    val color: Color? = null,           // null → inherit NwTheme.colors.onSurface
    val shadow: Boolean = true,         // MC text drop shadow
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val scale: Float = 1f,              // font scale; 1.0 = MC default 9px line height
)

@Immutable
data class NwTypography(
    val title: TextStyle = TextStyle(scale = 1.5f, bold = true),
    val subtitle: TextStyle = TextStyle(scale = 1.2f),
    val body: TextStyle = TextStyle(),
    val caption: TextStyle = TextStyle(scale = 0.85f, color = null),
    val mono: TextStyle = TextStyle(),  // future: separate font
)
```

### Layer 1: access via NwTheme + CompositionLocal

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

@Composable
fun NwThemeProvider(
    colors: NwColors = NwColors.Dark,
    dimens: NwDimens = NwDimens(),
    shapes: NwShapes = NwShapes(),
    typography: NwTypography = NwTypography(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNwColors provides colors,
        LocalNwDimens provides dimens,
        LocalNwShapes provides shapes,
        LocalNwTypography provides typography,
        LocalFont provides Minecraft.getInstance().font,
        content = content,
    )
}
```

`staticCompositionLocalOf` (not `compositionLocalOf`) because tokens almost never change at runtime — using static makes recomposition cheaper.

### Layer 2: per-component style classes

Each visual component gets a `Defaults` object that builds style data classes from tokens. Pattern lifted from Material 3:

```kotlin
@Immutable
data class ButtonStyle(
    val container: Color,
    val containerHover: Color,
    val containerPressed: Color,
    val containerDisabled: Color,
    val content: Color,
    val contentDisabled: Color,
    val border: BorderStroke?,
    val shape: Shape,
    val padding: PaddingValues,
    val textStyle: TextStyle,
)

object ButtonDefaults {
    @Composable fun filled(
        container: Color = NwTheme.colors.accent,
        content: Color = NwTheme.colors.onAccent,
    ) = ButtonStyle(
        container = container,
        containerHover = container.shiftLightness(+0.06f),
        containerPressed = container.shiftLightness(-0.08f),
        containerDisabled = container.copy(alpha = 0.4f),
        content = content,
        contentDisabled = content.copy(alpha = 0.5f),
        border = null,
        shape = NwTheme.shapes.medium,
        padding = PaddingValues(h = NwTheme.dimens.space8, v = NwTheme.dimens.space4),
        textStyle = NwTheme.typography.body,
    )

    @Composable fun outlined() = filled(
        container = Color.Transparent,
        content = NwTheme.colors.onSurface,
    ).copy(border = BorderStroke(NwTheme.dimens.borderThin, NwTheme.colors.border))

    @Composable fun ghost() = filled(
        container = Color.Transparent,
        content = NwTheme.colors.onSurface,
    )

    @Composable fun danger() = filled(container = NwTheme.colors.danger)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonDefaults.filled(),
    content: @Composable RowScope.() -> Unit,
) { /* impl */ }
```

Usage:
```kotlin
Button(onClick = { save() }) { Text("Save") }                          // default = filled accent
Button(onClick = { ... }, style = ButtonDefaults.outlined()) { ... }   // outlined variant
Button(onClick = { ... }, style = ButtonDefaults.danger()) { ... }     // danger
Button(onClick = { ... }, style = ButtonDefaults.filled(
    container = NwTheme.colors.success                                  // ad-hoc tweak
)) { ... }
```

### Layer 3: per-call-site override via Modifier

Any visual property a style sets can also be overridden by chained modifiers:

```kotlin
Button(onClick = ...,
    modifier = Modifier
        .background(Color.Red)         // overrides style.container at render time
        .border(2, Color.Yellow)       // overrides style.border
) { ... }
```

Order: `Modifier.background` always wins over `style.container`. Pattern: component renderer reads modifier-elements first, falls back to style fields. Documented per component.

### Why three layers (not just CompositionLocal + Modifier)

- **Tokens alone (Material 1 era)** force every component to read 5+ locals and re-derive its colors. Lots of duplication, easy to mis-style.
- **Modifier-only** (no styles) means every Button instance has to manually wire 8 properties. Painful and inconsistent.
- **Three layers** = tokens drive defaults; per-component data classes give named, type-safe knobs; modifiers handle one-off escapes. Same pattern shipping in production by Material 3, shadcn/ui, Mantine.

### Pre-defined component variants in MVP

Each component ships these `Defaults`:
- **Button**: `filled` (default), `outlined`, `ghost`, `danger`
- **Surface**: `default` (surface bg), `elevated` (surfaceHover bg), `outlined`, `transparent`
- **Text**: implicit via `style` param consuming `NwTypography.{title, subtitle, body, caption, mono}`
- **Divider**: `default`, `strong`

User can define their own: `val MyButtonStyle = ButtonDefaults.filled(container = ...)` then reuse.

### Theme switching

A whole-screen theme override:

```kotlin
NwThemeProvider(colors = NwColors.Dark.copy(accent = Color(0xFF_FF_88_22))) {
    NodeEditorScreen(...)
}
```

For MVP we ship `NwColors.Dark` only. `NwColors.Light` deferred.

### Color helpers

```kotlin
@JvmInline value class Color(val argb: Int) {
    val a: Int; val r: Int; val g: Int; val b: Int
    fun copy(a: Int = this.a, r: ..., g: ..., b: ...): Color
    fun copy(alpha: Float): Color
    fun shiftLightness(delta: Float): Color  // -0.1 darker, +0.1 lighter
    fun blend(other: Color, t: Float): Color
    companion object {
        val Transparent = Color(0)
        val Black = Color(0xFF_00_00_00.toInt())
        val White = Color(0xFF_FF_FF_FF.toInt())
        // ...
    }
}
```

### Shape rendering

`Shape` is an interface; `NwCanvas.fillShape(rect, shape, color)` dispatches:
- `RectangleShape` → `fillRect`
- `RoundedCornerShape(r)` → composed of 4 corner arcs + 5 rects (textured circle quadrant from a 16×16 atlas for crisp corners; MC GuiGraphics has no native arc)

Stroke rendering similar. MVP supports rect + rounded rect; circle/path deferred.

**Default theme: dark.** Light theme not in MVP.

---

## MVP components (built on top of layout + modifiers)

Provided by the framework so users can build a node editor:

- `Box(modifier, content)` — alignment via `Modifier.align(...)` deferred; MVP positions children at (0,0)
- `Row(modifier, horizontalArrangement, verticalAlignment, content)`
- `Column(modifier, verticalArrangement, horizontalAlignment, content)`
- `Spacer(modifier)` — uses its modifier's size constraint
- `Text(text: String, modifier, style)` — measures + renders MC text
- `Icon(loc: ResourceLocation, modifier, tint)` — renders a 16×16 (configurable) texture
- `Button(onClick, modifier, content)` — styled clickable surface with hover/pressed states (state via `pointerInput`)
- `Surface(modifier, color, shape, border, content)` — Material-like wrapper, primary styling primitive
- `Divider(modifier, color, thickness)`
- `Tooltip(text, content)` — appears after hover delay, rendered last-pass at cursor

That's 9 components. Enough to build the node editor (palette = `Column` of `Buttons`; node = `Surface` with `Column` of pin rows; pin = `Row { Icon; Text }`).

---

## Lifecycle / Composition flow

1. Player right-clicks `logic_block` → server opens screen
2. Client receives open packet → instantiates concrete `NwComposeScreen`
3. `Screen.init()` → `NwUiOwner.start(Content)` → starts a coroutine on `NwClientDispatcher` that runs `recomposer.runRecomposeAndApplyChanges()`, then `composition.setContent { CompositionLocalProvider(LocalNwOwner provides this) { Content() } }`
4. First measure happens immediately (snapshot has writes); applier builds UiNode tree
5. MC calls `Screen.render(gfx, ...)` every frame:
   - `clock.sendFrame(now)` if frame waiters
   - `rootNode.measure(Constraints(maxWidth=screen.width, maxHeight=screen.height))`
   - `rootNode.renderTo(NwCanvas(gfx))`
6. MC delivers input events → `Screen.mouseClicked/...` → `owner.dispatchPointer(...)` → hit-test walks tree
7. Snapshot writes (state changes) → recomposer schedules recomposition next frame → tree mutates → next render shows new state
8. Player closes screen → `Screen.removed()` → `owner.dispose()` → cancels coroutine, disposes composition, unregisters snapshot observer

---

## Gradle integration

```kotlin
plugins {
    id("net.minecraftforge.gradle") version "[6.0.16,6.2)"
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"  // NEW
    `java-library`
    idea
}

repositories {
    // ...
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")  // NEW: compose-runtime
}

dependencies {
    // ...
    // Compose runtime (no UI, no Skiko)
    implementation("org.jetbrains.compose.runtime:runtime:1.7.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Flexbox layout (pure Java, no native deps)
    implementation("org.appliedenergistics.yoga:yoga:1.0.0")
}

composeCompiler {
    // featureFlag for Strong Skipping if available
}
```

**Kotlin 2.0.20** + **Compose Compiler 2.0.20-1.0.x** is a known-good pair. Compose runtime 1.7+ works with Kotlin 2.0.

**JarInJar packaging:** Compose runtime needs to be shipped with the mod. Use ForgeGradle's JarJar to bundle `runtime-desktop` + `kotlinx-coroutines-core` if not already provided by KFF. Test in dev: classpath has them; in production: JarJar pulls them in.

---

## Risks + mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| `composeCompiler` plugin conflicts with KFF | Med | Both plugin versions need to align with Kotlin 2.0.20. If conflict, drop KFF and load Kotlin manually (KFF's only useful service is the language loader; we can write a `<modlang>` ourselves or pick a different KFF version) |
| AE2/yoga incompatible with Java 17 / has unwanted deps | Low | Spike: add dep, write 5-line "create node, calculateLayout, read X/Y" test, run. If broken — fall back to writing thin flexbox impl ourselves (still better than custom non-flexbox MeasurePolicy). |
| Yoga's float-precision rounding behaviour off-by-1 in MC pixel grid | Med | UiNode reads `yoga.layoutX` etc. and rounds via `.toInt()`. Use AE2/yoga's `roundToPixelGrid` config knob if present (Yoga has `pointScaleFactor`). |
| Threading mismatch (MC client thread vs recomposer dispatcher) | High | Single-threaded `NwClientDispatcher` posting to MC's executor. All recomposition happens on client thread |
| BroadcastFrameClock with no `withFrameNanos` callers idles forever | Low | Force frame on snapshot writes (same as guiy-compose pattern) |
| `compose-runtime-desktop` pulls in JVM Skia somehow | Low | Audit deps with `./gradlew dependencies`. `runtime` is pure JVM. Use `runtime` artifact, not `runtime-desktop`, if `-desktop` brings unwanted transitives |
| Performance: re-measuring every frame is wasteful | Med | OK for MVP. Future: skip measure if no invalidation flag set; cache placed positions |
| Mod conflicts via shared GL state | None | We only call `GuiGraphics` methods, same as any Screen. No GL state we touch |

**On `runtime` vs `runtime-desktop`:** Compose runtime publishes `runtime` (KMP root, includes `commonMain`) and per-platform artifacts. For pure-JVM Forge mod, we want the JVM platform artifact. Need to verify whether that's `runtime` (with JVM target) or `runtime-jvm` or `runtime-desktop`. Implementation task includes a 30-min spike to confirm by inspecting transitive deps.

---

## Acceptance criteria

- **AC1**: `./gradlew build` succeeds with Compose compiler plugin enabled
- **AC2**: A demo screen with `@Composable fun Demo() { Text("hello") }` opens and shows "hello" centered
- **AC3**: `var n by remember { mutableStateOf(0) }; Text("$n"); Button(onClick = { n++ }) { Text("+") }` works — clicking increments and rerenders
- **AC4**: `Column { repeat(5) { Text("$it") } }` stacks 5 rows vertically with default spacing
- **AC5**: `Row { Box(Modifier.size(20,20).background(Red)); Box(Modifier.size(20,20).background(Blue)) }` shows two adjacent colored squares
- **AC6**: `Modifier.padding(10).background(Gray).padding(5).background(White)` shows correct nesting (gray ring around white inner box)
- **AC7**: `Modifier.clickable { count++ }` fires on click within bounds, not outside
- **AC8**: Theme: `NwThemeProvider(colors = customColors) { ... }` propagates colors via `NwTheme.colors` in nested composables
- **AC9**: Closing screen disposes composition (no leaked coroutines — verify with a `DisposableEffect { onDispose { println("disposed") } }` in demo)
- **AC10**: Two screens in sequence (open A, close, open B) each have independent state — no cross-contamination
- **AC11**: Compose runtime + coroutines bundled in production jar via JarJar (verified by inspecting built jar)
- **AC12**: No mixin/registry warnings; no GL state errors in logs

---

## Open questions resolved by this spec

- **Q: Real Compose or copy the patterns?** → Real Compose runtime + compiler. Custom Applier/layout/render only.
- **Q: Skiko? AWT?** → No. Pure JVM.
- **Q: Custom layout algorithm?** → No. **AE2/yoga flexbox** (pure-Java port of Facebook Yoga). Industry-standard, fully tested, MIT.
- **Q: Bundled with KFF or independent?** → Independent. compose-runtime + kotlinx-coroutines + yoga, bundled via JarJar.
- **Q: How styled?** → 3-layer system: design tokens (CompositionLocal) → per-component style data classes with `Defaults` factories → Modifier overrides at call site.
- **Q: How does this interact with node editor?** → Node editor MVP spec gets revised to use these composables. Pin types map to `NwColors.pinBool` etc.

---

## Next step

After this is implemented and AC1–AC12 pass, the node editor MVP spec ([2026-05-13-node-editor-mvp-design.md](./2026-05-13-node-editor-mvp-design.md)) gets a small revision: the Screen subclasses `NwComposeScreen`, palette is a `Column` of `Buttons`, canvas is a `Box` with absolute-positioned node `Surface`s, wires are drawn by a custom `Renderer` attached to the canvas Box.
