# Comments, Wire Labels, and Proxy Naming Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task-by-task. Steps use `- [ ]` checkboxes.

**Goal:** Add three intertwined editor features in one pass — multi-line
plain-text comments on the canvas (alongside Groups), wire labels on
edges, and use those labels to make collapsed-group proxy pins readable.

**Architecture:**
- `Comment` becomes a new top-level entity in `NodeGraph` alongside `nodes`/`edges`/`groups` — pure visual metadata, evaluator ignores it.
- `Edge` gains an optional `label: String?`. WireLayer renders it midway along the curve when non-empty. Click on label / on wire midpoint → inline rename via TextInput overlay.
- `GroupProxyPins.compute` prefers the touching edge's label; if absent, uses `pin.name` with disambiguation (`A`, `Constant.Out` when duplicated, `And.A 2` on further collisions). UUID prefix is gone.
- New `TextArea` UI component for comment edit mode: multi-line, Enter inserts newline, basic per-row rendering. Built on the existing `TextFieldStateHolder` (newlines already round-trip in its text field).

**Tech Stack:** Kotlin 2.0.20, Compose runtime 1.7.0, Mojang Codec API, NBT, JUnit 5.

**Branch:** master.

**Verification:** `./gradlew build` and `./gradlew test`. No `runClient`.

---

## File map

### New files

| Path | Responsibility |
|---|---|
| `src/main/kotlin/dev/nitka/nodewire/ui/components/TextArea.kt` | Multi-line text input composable. Wraps `TextFieldStateHolder`. Enter inserts `\n`. Renders text rows via the existing per-line renderer pattern. |
| `src/main/kotlin/dev/nitka/nodewire/graph/Comment.kt` | `CommentId`, `Comment` data class + `CODEC`. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/CommentCard.kt` | Per-comment composable: title-less plain card. Drag header + resize handle. View mode shows text; double-click switches to edit (TextArea). |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/CommentLayer.kt` | Iterates `editor.comments` and mounts `CommentCard` per entry. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLabelOverlay.kt` | Overlay layer that renders the inline TextInput when a wire is being renamed. Reads `editor.renamingEdge`. |

### Modified files

| Path | Change |
|---|---|
| `src/main/kotlin/dev/nitka/nodewire/graph/Edge.kt` | Add `label: String? = null` field + extend `CODEC` with `optionalFieldOf("label")`. |
| `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt` | Add `comments: MutableList<Comment>` + extend `CODEC`; `optionalFieldOf("comments", emptyList())` for back-compat with pre-comments saves. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` | `_comments` flow + ops (`addComment`, `removeComment`, `updateCommentText`, `moveComment`, `resizeComment`); `setEdgeLabel(edge, label)`; `renamingEdge: Edge?` state for inline rename. `snapshotGraph` includes comments. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupProxyPins.kt` | Lookup edge label for each proxy pin first; fallback to `pin.name`; resolve duplicates with `TypeDisplayName.PinName` and numeric suffixes. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt` | Render `edge.label` at curve midpoint. Hit-test edge midpoint and label area for click-to-rename. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeContextMenu.kt` | Canvas-create menu: "Add Comment" action. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeEditorScreen.kt` | Mount `CommentLayer` and `WireLabelOverlay`. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/ContextMenuTarget.kt` | Add `Comment(screenX, screenY, commentId)` variant for comment context menu. |

### New tests

| Path | Coverage |
|---|---|
| `src/test/kotlin/dev/nitka/nodewire/graph/CommentCodecTest.kt` | Round-trip `Comment` and `NodeGraph` with comments. |
| `src/test/kotlin/dev/nitka/nodewire/graph/EdgeLabelCodecTest.kt` | Round-trip `Edge` with and without `label`. Back-compat: an edge tag without `label` decodes to `label == null`. |
| `src/test/kotlin/dev/nitka/nodewire/graph/GroupProxyPinsLabelingTest.kt` | Label resolution: edge.label wins; otherwise `pin.name`; duplicates fall through to `TypeName.PinName`; further dupes get numeric suffix. |
| `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateCommentOpsTest.kt` | add/remove/update/move/resize Comment ops. |

---

## Phase 1 — TextArea component

### Task 1.1: TextArea composable

**Files:** Create `src/main/kotlin/dev/nitka/nodewire/ui/components/TextArea.kt`.

Reuse the existing `TextFieldStateHolder` (it already accepts arbitrary text including `\n`). The component differs from `TextInput` in three ways:

- Visual layout: split `holder.state.text` on `\n` and render each line as its own `Text`. Width is fixed by caller; height is `lines.size * scaledLineHeight + 2`.
- Enter key inserts `'\n'` instead of submitting. Submit (if needed) is via Ctrl+Enter.
- Up/Down arrows move caret across lines by counting characters in the previous/next line; falls back to `MoveLeft`/`MoveRight` semantics.

**Signature:**

```kotlin
@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    minLines: Int = 3,
)
```

- [ ] **Step 1:** Read `TextInput.kt` end-to-end to learn the focus / blink / pointer pattern. Copy the `TextInput.kt` file as a starting point for `TextArea.kt`.

- [ ] **Step 2:** In the copied file, change:
  - Function name → `TextArea`.
  - Remove `onSubmit` parameter (or keep as Ctrl+Enter only — caller's choice).
  - In the `KeyHandler.handle` Press branch, intercept `GLFW_KEY_ENTER` (without modifiers) by calling `holder.insertChar('\n'.code)` instead of `onSubmit`. Ctrl+Enter still releases focus / fires submit if desired.
  - Replace the single-line render with a per-line loop:

```kotlin
val lines = remember(holder.state.text) { holder.state.text.split('\n') }
val rowHeight = scaledLineHeight + 2
Column(modifier = Modifier.height(maxOf(minLines, lines.size) * rowHeight)) {
    for (line in lines) {
        // Each line is a Text(...) sized via NwTheme.typography.caption.
        Text(line, style = NwTheme.typography.caption)
    }
}
```

  Caret/selection rendering is a known limitation: we render the caret on the line that contains `holder.state.selection.end`, computed by counting newlines up to that index. Selection across lines is NOT rendered visually in this iteration — model still tracks it (`holder.state.selection`) so copy/cut/paste work, just the highlight only shows on the caret's row.

- [ ] **Step 3:** Build and commit.

```bash
./gradlew compileKotlin
git -C /home/nitka/CODING/nodewire add src/main/kotlin/dev/nitka/nodewire/ui/components/TextArea.kt
git -C /home/nitka/CODING/nodewire commit -m "feat(ui): TextArea — multi-line text input composable"
```

---

## Phase 2 — Comment data model

### Task 2.1: Comment data class + codec

**Files:** Create `src/main/kotlin/dev/nitka/nodewire/graph/Comment.kt`.

```kotlin
package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.UUID

typealias CommentId = UUID

/**
 * A floating plain-text annotation on the canvas. Like [Group], it is
 * pure visual metadata; evaluator never reads it.
 */
data class Comment(
    val id: CommentId,
    val pos: CanvasPos,
    val width: Int,
    val height: Int,
    val text: String,
) {
    companion object {
        fun newId(): CommentId = UUID.randomUUID()

        val CODEC: Codec<Comment> = RecordCodecBuilder.create { i ->
            i.group(
                GraphCodecs.UUID_CODEC.fieldOf("id").forGetter(Comment::id),
                CanvasPos.CODEC.fieldOf("pos").forGetter(Comment::pos),
                Codec.INT.fieldOf("w").forGetter(Comment::width),
                Codec.INT.fieldOf("h").forGetter(Comment::height),
                Codec.STRING.fieldOf("text").forGetter(Comment::text),
            ).apply(i, ::Comment)
        }
    }
}
```

- [ ] Compile + commit.

### Task 2.2: NodeGraph.comments + codec

**Files:** Modify `src/main/kotlin/dev/nitka/nodewire/graph/NodeGraph.kt`.

Add `val comments: MutableList<Comment> = mutableListOf()`. Extend `CODEC` to include `Comment.CODEC.listOf().optionalFieldOf("comments", emptyList()).forGetter { g -> g.comments.toList() }` and append to the constructor builder. Back-compat: graphs saved without `comments` decode to empty list.

- [ ] Build + commit.

### Task 2.3: Comment codec round-trip test

**Files:** `src/test/kotlin/dev/nitka/nodewire/graph/CommentCodecTest.kt`. Two tests: `Comment` direct round-trip; `NodeGraph` carrying one comment round-trip.

- [ ] Run + commit.

---

## Phase 3 — Edge.label + WireLayer rendering

### Task 3.1: Edge.label field

**Files:** Modify `src/main/kotlin/dev/nitka/nodewire/graph/Edge.kt`.

```kotlin
data class Edge(val from: PinRef, val to: PinRef, val label: String? = null) {
    companion object {
        val CODEC: Codec<Edge> = RecordCodecBuilder.create { i ->
            i.group(
                PinRef.CODEC.fieldOf("from").forGetter(Edge::from),
                PinRef.CODEC.fieldOf("to").forGetter(Edge::to),
                Codec.STRING.optionalFieldOf("label").forGetter { java.util.Optional.ofNullable(it.label) },
            ).apply(i) { f, t, label -> Edge(f, t, label.orElse(null)) }
        }
    }
}
```

- [ ] Build + tests (existing) must still pass (back-compat: pre-label edges decode with `label == null`). Commit.

### Task 3.2: Edge label codec test

**Files:** `src/test/kotlin/dev/nitka/nodewire/graph/EdgeLabelCodecTest.kt`. Test: edge with label round-trips; edge without label round-trips to `null`.

- [ ] Run + commit.

### Task 3.3: WireRenderer renders edge labels

**Files:** Modify `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt`.

Inside the wire render loop, after the curve is drawn, if `edge.label != null`, draw a small filled rect with the label text at the geometric midpoint of the curve `(fromPos, toPos)`. Use `NwTheme.colors.surface` + `border` + caption typography. Rect size = font width of label + 6 px padding × line height + 2.

- [ ] Build (UI-only, no test) + commit.

---

## Phase 4 — Wire rename UX

### Task 4.1: EditorState.renamingEdge slot + setEdgeLabel

**Files:** Modify `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`.

```kotlin
    var renamingEdge: Edge? by mutableStateOf(null)

    fun setEdgeLabel(edge: Edge, label: String?) {
        mutateGraph(mergeable = false) {
            val idx = graph.edges.indexOf(edge)
            if (idx < 0) return@mutateGraph
            val sanitized = label?.takeIf { it.isNotEmpty() }
            graph.edges[idx] = edge.copy(label = sanitized)
            _edges.value = graph.edges.toList()
        }
    }
```

- [ ] Build + commit.

### Task 4.2: WireLayer click hit-test + open rename

**Files:** Modify `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt`.

Add a `Modifier.pointerInput` at the wire layer's root that, on `Press`, walks all edges, computes the curve midpoint, and checks if the press is within a small radius (e.g., 12px world units). If so, set `editor.renamingEdge = edge`.

This requires the wire layer to be `Layout`+`Renderer` AND interactive. The existing `WireLayer` only renders. Simplest workaround: add a sibling transparent Box with `pointerInput` that does the hit test using the same `pinPositions` data.

Implementation strategy:

```kotlin
@Composable
fun WireLayer() {
    val editor = LocalEditorState.current ?: return
    // existing render Layout ...

    // Hit overlay (transparent, occupies the same area, hit-tests wires).
    Layout(
        modifier = Modifier.fillMaxSize().pointerInput { ev, x, y ->
            if (ev !is PointerEvent.Press) return@pointerInput false
            val edges = editor.edges.value
            val positions = editor.pinPositions
            for (e in edges) {
                val from = positions.get(PinKey(e.from.node, e.from.pin, PinSide.Output)) ?: continue
                val to = positions.get(PinKey(e.to.node, e.to.pin, PinSide.Input)) ?: continue
                val midX = (from.first + to.first) * 0.5f
                val midY = (from.second + to.second) * 0.5f
                val dx = x.toFloat() - midX
                val dy = y.toFloat() - midY
                if (dx * dx + dy * dy < HIT_RADIUS_SQ) {
                    editor.renamingEdge = e
                    return@pointerInput true
                }
            }
            false
        },
        renderer = NoopRenderer,
    )
}
```

(Make sure this Box does NOT consume drag events that the canvas pan handler needs — return `false` for non-Press.)

- [ ] Build + commit.

### Task 4.3: WireLabelOverlay — inline rename input

**Files:** Create `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLabelOverlay.kt`.

```kotlin
@Composable
fun WireLabelOverlay() {
    val editor = LocalEditorState.current ?: return
    val edge = editor.renamingEdge ?: return
    val positions = editor.pinPositions
    val from = positions.get(PinKey(edge.from.node, edge.from.pin, PinSide.Output)) ?: return
    val to = positions.get(PinKey(edge.to.node, edge.to.pin, PinSide.Input)) ?: return
    val midX = ((from.first + to.first) * 0.5f).toInt()
    val midY = ((from.second + to.second) * 0.5f).toInt()
    var text by remember(edge) { mutableStateOf(edge.label ?: "") }
    Box(modifier = Modifier.absolutePosition(midX - 40, midY - 8).width(80)) {
        TextInput(
            value = text,
            placeholder = "label",
            onValueChange = { text = it },
            onSubmit = {
                editor.setEdgeLabel(edge, text)
                editor.renamingEdge = null
            },
        )
    }
}
```

Add Esc handling: in `NodeEditorScreen.keyPressed`, if `editor.renamingEdge != null` and the key is Esc, clear it and consume.

Mount the overlay in `NodeEditorScreen.Content` after `NodeCanvas { ... }` block (so it sits above wires + cards).

- [ ] Build + commit.

---

## Phase 5 — Smart proxy pin names

### Task 5.1: GroupProxyPins label resolution + disambiguation

**Files:** Modify `src/main/kotlin/dev/nitka/nodewire/client/screen/GroupProxyPins.kt`.

Rewrite the label-building branch of `compute`:

1. **First-pass** — collect raw candidates for each cross-boundary edge: `(side, innerNode, innerPin, type, edgeLabel?, pinName, typeDisplayName)`.
2. **Resolve to displayed label** in order:
   - If `pinLabelOverrides["${innerNode}.${innerPin}"]` set → use it.
   - Else if `edgeLabel != null` → use `edgeLabel`.
   - Else default to `pinName`.
3. **Disambiguate** within (side, label) collisions:
   - If `(side, label)` is unique → keep `label`.
   - Else replace label with `"${typeDisplayName}.${pinName}"` if unique.
   - Else append numeric suffix: `"${typeDisplayName}.${pinName} 2"`, `... 3`, …

`typeDisplayName` from `NodeTypeRegistry.get(node.typeKey)?.displayName ?: node.typeKey.path`.

- [ ] Existing tests in `GroupProxyPinsTest` must still pass (or be updated — adjust assertions to use the new label format).
- [ ] Add `GroupProxyPinsLabelingTest` with cases: unique pin names; duplicate pin names → type prefix; duplicate type+pin → numeric suffix; edge.label override wins.
- [ ] Run + commit.

---

## Phase 6 — Comment UI

### Task 6.1: EditorState comment ops

**Files:** Modify `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt`.

Mirror `_groups` plumbing: `_comments: MutableStateFlow<List<Comment>>`, `comments` public read-only, `syncCommentsFlow()`. Ops:

```kotlin
    fun addComment(pos: CanvasPos): CommentId {
        val c = Comment(id = Comment.newId(), pos = pos, width = 180, height = 60, text = "")
        mutateGraph { graph.comments.add(c); syncCommentsFlow() }
        return c.id
    }
    fun removeComment(id: CommentId) {
        mutateGraph { graph.comments.removeAll { it.id == id }; syncCommentsFlow() }
    }
    fun updateCommentText(id: CommentId, text: String) {
        mutateGraph(mergeable = true) {
            val i = graph.comments.indexOfFirst { it.id == id }
            if (i < 0) return@mutateGraph
            graph.comments[i] = graph.comments[i].copy(text = text)
            syncCommentsFlow()
        }
    }
    fun moveComment(id: CommentId, dx: Float, dy: Float) {
        mutateGraph(mergeable = true) {
            val i = graph.comments.indexOfFirst { it.id == id }
            if (i < 0) return@mutateGraph
            val c = graph.comments[i]
            graph.comments[i] = c.copy(pos = CanvasPos(c.pos.x + dx, c.pos.y + dy))
            syncCommentsFlow()
        }
    }
    fun resizeComment(id: CommentId, w: Int, h: Int) {
        mutateGraph(mergeable = true) {
            val i = graph.comments.indexOfFirst { it.id == id }
            if (i < 0) return@mutateGraph
            graph.comments[i] = graph.comments[i].copy(width = w, height = h)
            syncCommentsFlow()
        }
    }
```

Update `restoreFrom` to repopulate `graph.comments` from the snapshot and call `syncCommentsFlow()`. Update `snapshotGraph` to copy `_comments.value` into the new NodeGraph.

- [ ] Build + commit.

### Task 6.2: EditorStateCommentOpsTest

**Files:** `src/test/kotlin/dev/nitka/nodewire/client/screen/EditorStateCommentOpsTest.kt`. Cover add / update text / move / resize / remove. Verify `_comments` flow emits.

- [ ] Run + commit.

### Task 6.3: CommentCard composable

**Files:** Create `src/main/kotlin/dev/nitka/nodewire/client/screen/CommentCard.kt`.

Layout:
- Outer `Box` at `comment.pos`, size `(width, height)`, `background(NwTheme.colors.surfaceHover)`, `border(NwTheme.colors.border)`.
- Inside: a 12-pixel top header strip with `pointerInput` handling Drag → `editor.moveComment(id, deltaX/zoom, deltaY/zoom)` and Press with `RIGHT_BUTTON` → opens comment context menu via `editor.openCommentMenu(screenX, screenY, id)` (added in 6.4).
- Below header: text body. Local `var editing by remember(comment.id) { mutableStateOf(false) }`. If `!editing`: `Text(comment.text)` (multi-line, plain). If `editing`: `TextArea(value = comment.text, onValueChange = { editor.updateCommentText(id, it) })`. Double-click on body toggles `editing = true`; Esc handler in the screen clears it (or click outside).
- A 10×10 resize handle at bottom-right; drag updates `editor.resizeComment`.

- [ ] Build + commit.

### Task 6.4: ContextMenuTarget.Comment + EditorState.openCommentMenu + NodeContextMenu

**Files:**
- Modify `ContextMenuTarget.kt` — add `Comment(screenX, screenY, commentId)` variant.
- Modify `EditorState.kt` — add `openCommentMenu(screenX, screenY, id)` setter.
- Modify `NodeContextMenu.kt` — handle the new variant: build a comment menu with one action: "Delete comment". Also extend `buildCreateItems` to include an `Action("Add Comment") { editor.addComment(target.world) }` near the top.

- [ ] Build + commit.

### Task 6.5: CommentLayer composable + screen mount

**Files:**
- Create `src/main/kotlin/dev/nitka/nodewire/client/screen/CommentLayer.kt`:

```kotlin
@Composable
fun CommentLayer() {
    val editor = LocalEditorState.current ?: return
    val comments by editor.comments.collectAsState()
    for (c in comments) CommentCard(c)
}
```

- Modify `NodeEditorScreen.kt`: mount `CommentLayer()` between `GroupFramesLayer()` and `WireLayer()` — comments live UNDER wires/nodes (think Blender frames). Also mount `WireLabelOverlay()` after the `NodeCanvas { ... }` block (above wires so the input sits on top of curves).

- [ ] Build + commit.

---

## Phase 7 — Final build + handoff

- [ ] `./gradlew build` green.
- [ ] `./gradlew test` green.
- [ ] Stop. Ask user to run `runClient` and validate:
  - Canvas right-click → "Add Comment" → comment card appears.
  - Double-click card body → TextArea opens → typing including Enter for newline → click outside or Esc → text persists.
  - Drag a wire → click midpoint → inline input → type label → Enter → label renders on the wire.
  - Collapsed group: proxy pins show pin name (e.g., `A`, `Out`) instead of UUID prefix; if duplicates, shows `TypeName.Pin`.
  - Reopen the Logic Block: comments, wire labels, and groups all persist.
