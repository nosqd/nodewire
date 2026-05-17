# TextInput Jewel-level UX — Design Spec

Rewrite the `TextInput` widget so its behavior matches IntelliJ Jewel / Compose `BasicTextField`: real caret with blink, selection, full keyboard shortcut coverage (arrows, Home/End, word-jump, Shift-extend, clipboard, undo/redo), mouse click-to-position + drag-select + double/triple-click word/line select. Visual design preserved; only UX changes.

## Goal

End user opens any `TextInput` (search box in the redstone-link picker, node config text fields, future `TextArea`s) and gets the editing experience they expect from any modern IDE / Jewel-built UI — without us pulling in compose-foundation (vetoed: compose.ui + Skiko are out of scope per CLAUDE.md).

## Architecture

Stateful logic extracted from `TextInput.kt` into a reusable text-editing layer under `ui/input/text/`. Composition:

1. **Pure data:** `TextRange` value type; `TextFieldState(text, selection)` value type.
2. **Pure operations:** `EditOps` — `TextFieldState → TextFieldState` functions (insert, deleteSelection, backspace, deleteWord*, moveCaret*, selectAll, replaceRange). All unit-testable without MC bootstrap.
3. **Stateful holder:** `TextFieldStateHolder` — mutable wrapper, undo stack, scroll offset, callback dispatcher, drag/click bookkeeping. Method API matches what key-bindings + mouse handlers call.
4. **Key bindings table:** `TextFieldKeyBindings` — list of `KeyBinding(keyCode, modifiers, action)` matched O(N) per key press. Default list covers Jewel-equivalent shortcuts; caller can prepend custom bindings.
5. **Renderer:** `TextFieldRenderer : Renderer` — draws selection rect under text + blinking caret bar after text. Applies horizontal scroll-clip when text overflows.
6. **Composable:** `TextInput.kt` rewritten as a thin shell that wires holder + KeyHandler + Renderer + Modifier chain. Public API signature unchanged.

This isolates rewriting impact: visual style files untouched; layout primitives untouched; existing `LocalKeyFocus` controller untouched.

## Data types

```kotlin
data class TextRange(val start: Int, val end: Int) {
    val min get() = minOf(start, end)
    val max get() = maxOf(start, end)
    val length get() = max - min
    val collapsed get() = start == end
    companion object {
        val Zero = TextRange(0, 0)
        fun caret(at: Int) = TextRange(at, at)
    }
}

data class TextFieldState(
    val text: String = "",
    val selection: TextRange = TextRange.Zero,
) {
    val caret: Int get() = selection.end
    val hasSelection: Boolean get() = !selection.collapsed
}
```

## EditOps (pure functions)

Each takes `TextFieldState` and returns a new one. Selection always clamped to `[0, text.length]`. Functions:

- `insert(s: TextFieldState, str: String): TextFieldState` — replaces selection with `str`, caret moves to `selection.min + str.length`. If `str` contains `\n` and host is single-line: caller filters first (see clipboard paste).
- `deleteSelection(s)` — returns state with selection text removed, caret at `selection.min`. No-op if collapsed.
- `backspace(s)` — if selection present, `deleteSelection`. Else remove char at `caret - 1`, caret--. No-op if caret == 0.
- `deleteForward(s)` — symmetric of backspace at `caret`.
- `deleteWordBackward(s)` / `deleteWordForward(s)` — delete to `wordBoundary(text, caret, ±1)` instead of one char.
- `moveCaretBy(s, delta, extend): TextFieldState` — `newCaret = (caret + delta).coerceIn(0, length)`. If `extend`: anchor = `selection.start` (preserved). Else collapse to caret.
- `moveCaretWord(s, direction, extend): TextFieldState` — `newCaret = wordBoundary(text, caret, direction)`. Same anchor logic.
- `moveCaretToLineStart(s, extend)` / `moveCaretToLineEnd(s, extend)` — single-line → 0 / text.length.
- `selectAll(s)` — `selection = TextRange(0, text.length)`.
- `collapseToCaret(s, side)` — `side = Side.Left|Right` → `selection.collapsed at min or max`. Used after some no-op nav.

### Word boundary algorithm

Three character classes: `whitespace`, `word` (letter or digit), `other`. Boundary = transition between classes.

```kotlin
private enum class CharClass { Whitespace, Word, Other }
private fun classOf(c: Char): CharClass = when {
    c.isWhitespace() -> CharClass.Whitespace
    c.isLetterOrDigit() -> CharClass.Word
    else -> CharClass.Other
}

fun wordBoundary(text: String, from: Int, direction: Int): Int {
    if (text.isEmpty()) return 0
    var i = from
    if (direction > 0) {
        // Skip the class at i, then stop at the next class boundary.
        if (i >= text.length) return text.length
        val startClass = classOf(text[i])
        while (i < text.length && classOf(text[i]) == startClass) i++
        return i
    } else {
        if (i <= 0) return 0
        val startClass = classOf(text[i - 1])
        while (i > 0 && classOf(text[i - 1]) == startClass) i--
        return i
    }
}
```

(Jewel uses essentially this — IDEA's `EditorActionUtil.getNextWordEnd` family. Three-class model is the IDEA default for "Smart Home/End and Word Jumping".)

## TextFieldStateHolder

```kotlin
class TextFieldStateHolder(initial: TextFieldState = TextFieldState()) {
    var state: TextFieldState by mutableStateOf(initial)
        private set
    var scrollX: Int by mutableStateOf(0)
        private set

    private val undoStack = ArrayDeque<TextFieldState>().also { it.add(initial) }
    private val redoStack = ArrayDeque<TextFieldState>()
    private var lastUndoPushAt = 0L
    private val undoMergeWindowMs = 500L

    // Mouse double/triple click tracking
    private var lastClickAt = 0L
    private var lastClickIdx = -1
    private var clickStreak = 0
    private var selectionAnchor: Int = 0

    // Set externally by composable each frame.
    var visibleWidthPx: Int = 0
    var paddingLeftPx: Int = 0
    var fontWidthOf: (String) -> Int = { 0 }
    var fontHeightPx: Int = 9
    var onSubmit: () -> Unit = {}
    var onReleaseFocus: () -> Unit = {}

    /** Replaces state and pushes onto undo (with merge). Notifies onValueChange via composable. */
    fun mutate(produce: (TextFieldState) -> TextFieldState, mergeable: Boolean = false) { ... }

    // Edit API (each calls mutate appropriately)
    fun insertChar(codePoint: Int) { ... }
    fun insertString(s: String) { ... }
    fun backspace() { ... }
    fun deleteForward() { ... }
    fun deleteWordBackward() { ... }
    fun deleteWordForward() { ... }
    fun moveCaretBy(delta: Int, extend: Boolean) { ... }
    fun moveCaretWord(direction: Int, extend: Boolean) { ... }
    fun moveCaretToLineStart(extend: Boolean) { ... }
    fun moveCaretToLineEnd(extend: Boolean) { ... }
    fun selectAll() { ... }
    fun copyToClipboard() { ... }
    fun cutToClipboard() { ... }
    fun pasteFromClipboard() { ... }   // strips \n past first line
    fun undo() { ... }
    fun redo() { ... }
    fun submit() = onSubmit()
    fun releaseFocus() = onReleaseFocus()

    // Mouse API
    fun mousePress(localX: Int, shift: Boolean, now: Long) { ... }
    fun mouseDrag(localX: Int) { ... }

    // Caret pixel-x in text-local coords (used by renderer).
    fun caretPixelX(): Int { ... }
    // Selection pixel range (used by renderer).
    fun selectionPixelRange(): IntRange? { ... }
    // Ensure caret in view; adjusts scrollX.
    fun ensureCaretVisible() { ... }
}
```

**Undo merging:** consecutive `insertChar` calls within `undoMergeWindowMs` of the previous one merge into a single undo entry (the latest state overwrites the top of the stack). Any non-insert op (delete, paste, nav with selection change) breaks the merge — subsequent inserts push a fresh entry. Redo cleared on any new edit.

**Clipboard:** `Minecraft.getInstance().keyboardHandler.clipboard` for get/set. Paste strips at first `\n`: `clipboard.split('\n').firstOrNull() ?: ""`.

**Mouse press tracking:**
- Compute `idx = pixelToCharIndex(text, localX - paddingLeft + scrollX, font)`.
- If `now - lastClickAt < 400 && lastClickIdx == idx` → clickStreak++; else clickStreak = 1.
- `clickStreak == 1`: setCaret(idx, extend = shift). selectionAnchor = idx.
- `clickStreak == 2`: select word around idx (`wordBoundary -1 .. +1`).
- `clickStreak == 3`: selectAll. Reset streak.
- Save `lastClickAt = now; lastClickIdx = idx`.

**Mouse drag (button == 0):**
- Compute `idx` from new `localX`.
- `state = state.copy(selection = TextRange(selectionAnchor, idx))`.
- Holder calls `ensureCaretVisible()` to auto-scroll.

## Key bindings

```kotlin
data class KeyBinding(
    val keyCode: Int,
    val modifiers: Int = 0,
    val action: (TextFieldStateHolder) -> Boolean,
)

object TextFieldKeyBindings {
    private const val MOD_MASK = GLFW_MOD_CONTROL or GLFW_MOD_SHIFT or GLFW_MOD_ALT or GLFW_MOD_SUPER

    val DEFAULT: List<KeyBinding> = listOf(
        // Navigation
        KeyBinding(KEY_LEFT)                                       { it.moveCaretBy(-1, false); true },
        KeyBinding(KEY_LEFT, GLFW_MOD_SHIFT)                       { it.moveCaretBy(-1, true);  true },
        KeyBinding(KEY_LEFT, GLFW_MOD_CONTROL)                     { it.moveCaretWord(-1, false); true },
        KeyBinding(KEY_LEFT, GLFW_MOD_CONTROL or GLFW_MOD_SHIFT)   { it.moveCaretWord(-1, true);  true },
        KeyBinding(KEY_RIGHT)                                      { it.moveCaretBy(+1, false); true },
        KeyBinding(KEY_RIGHT, GLFW_MOD_SHIFT)                      { it.moveCaretBy(+1, true);  true },
        KeyBinding(KEY_RIGHT, GLFW_MOD_CONTROL)                    { it.moveCaretWord(+1, false); true },
        KeyBinding(KEY_RIGHT, GLFW_MOD_CONTROL or GLFW_MOD_SHIFT)  { it.moveCaretWord(+1, true);  true },
        KeyBinding(KEY_HOME)                                       { it.moveCaretToLineStart(false); true },
        KeyBinding(KEY_HOME, GLFW_MOD_SHIFT)                       { it.moveCaretToLineStart(true);  true },
        KeyBinding(KEY_END)                                        { it.moveCaretToLineEnd(false); true },
        KeyBinding(KEY_END, GLFW_MOD_SHIFT)                        { it.moveCaretToLineEnd(true);  true },
        // Edit
        KeyBinding(KEY_BACKSPACE)                                  { it.backspace(); true },
        KeyBinding(KEY_BACKSPACE, GLFW_MOD_CONTROL)                { it.deleteWordBackward(); true },
        KeyBinding(KEY_DELETE)                                     { it.deleteForward(); true },
        KeyBinding(KEY_DELETE, GLFW_MOD_CONTROL)                   { it.deleteWordForward(); true },
        // Selection / Clipboard
        KeyBinding(KEY_A, GLFW_MOD_CONTROL)                        { it.selectAll(); true },
        KeyBinding(KEY_C, GLFW_MOD_CONTROL)                        { it.copyToClipboard(); true },
        KeyBinding(KEY_X, GLFW_MOD_CONTROL)                        { it.cutToClipboard(); true },
        KeyBinding(KEY_V, GLFW_MOD_CONTROL)                        { it.pasteFromClipboard(); true },
        // Undo / redo
        KeyBinding(KEY_Z, GLFW_MOD_CONTROL)                        { it.undo(); true },
        KeyBinding(KEY_Z, GLFW_MOD_CONTROL or GLFW_MOD_SHIFT)      { it.redo(); true },
        KeyBinding(KEY_Y, GLFW_MOD_CONTROL)                        { it.redo(); true },
        // Submit / cancel
        KeyBinding(KEY_ENTER)                                      { it.submit(); true },
        KeyBinding(KEY_NUMPAD_ENTER)                               { it.submit(); true },
        KeyBinding(KEY_ESCAPE)                                     { it.releaseFocus(); true },
    )

    fun match(bindings: List<KeyBinding>, event: KeyEvent.Press): KeyBinding? {
        val mods = event.modifiers and MOD_MASK
        return bindings.firstOrNull { it.keyCode == event.keyCode && it.modifiers == mods }
    }
}
```

**Char events:** processed separately, NOT through bindings. In `TextInput`'s `KeyHandler`:

```kotlin
is KeyEvent.Char -> {
    val cp = event.codePoint
    if (cp >= 0x20 && cp != 0x7F) {
        holder.insertChar(cp)
        true
    } else false
}
is KeyEvent.Press -> TextFieldKeyBindings.match(bindings, event)?.action?.invoke(holder) ?: false
```

## Renderer

```kotlin
class TextFieldRenderer(
    private val holder: TextFieldStateHolder,
    private val font: Font,
    private val textColor: Color,
    private val selectionColor: Color,
    private val caretColor: Color,
    private val isFocused: () -> Boolean,
    private val blinkPhase: () -> Boolean,
) : Renderer {
    override fun NwCanvas.render(node: UiNode) {
        val s = holder.state
        val w = node.layoutWidth
        val h = node.layoutHeight
        val padL = holder.paddingLeftPx
        val padTop = (h - holder.fontHeightPx) / 2
        pushClip(0, 0, w, h)
        try {
            // Selection rect (under text)
            holder.selectionPixelRange()?.let { range ->
                fillRect(padL + range.first - holder.scrollX, padTop, range.last - range.first, holder.fontHeightPx, selectionColor)
            }
            // Text (offset by -scrollX)
            drawText(s.text, padL - holder.scrollX, padTop, textColor)
            // Caret
            if (isFocused() && (blinkPhase() || s.hasSelection)) {
                val cx = padL + holder.caretPixelX() - holder.scrollX
                fillRect(cx, padTop, 1, holder.fontHeightPx, caretColor)
            }
        } finally {
            popClip()
        }
    }
}
```

This renderer replaces both the existing Text composable AND the static caret Box inside the TextInput Row. Layout becomes a single `Layout(modifier = ..., renderer = TextFieldRenderer(...))` sized to fill the input.

## TextInput composable (rewrite)

```kotlin
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onSubmit: () -> Unit = {},
    enabled: Boolean = true,
    keyBindings: List<KeyBinding> = TextFieldKeyBindings.DEFAULT,
) {
    val focusController = LocalKeyFocus.current
    val font = Minecraft.getInstance().font

    val holder = remember { TextFieldStateHolder(TextFieldState(text = value, selection = TextRange.caret(value.length))) }

    // Sync external value → holder when it changes externally.
    LaunchedEffect(value) {
        if (holder.state.text != value) {
            holder.replaceText(value)  // clears undo as a fresh branch
        }
    }
    // Holder.onValueChange notifies caller; rememberUpdatedState for staleness.
    val cb = rememberUpdatedState(onValueChange)
    LaunchedEffect(holder) {
        snapshotFlow { holder.state.text }.collect { cb.value(it) }
    }

    holder.onSubmit = onSubmit
    holder.fontWidthOf = { font.width(it) }
    holder.fontHeightPx = font.lineHeight

    // Blink tick: 530ms on, 470ms off.
    var blinkCounter by remember { mutableStateOf(0) }
    val focused = focusController?.isFocused(handler) == true
    holder.onReleaseFocus = { focusController?.release(handler) }
    LaunchedEffect(focused) {
        if (focused) while (true) { delay(50); blinkCounter++ }
    }
    val blinkOn = ((Util.getMillis() % 1000L) < 530L)

    // KeyHandler — same pattern as before, but dispatches via key bindings.
    val handler = remember {
        object : KeyHandler {
            override fun handle(event: KeyEvent): Boolean {
                if (!enabled) return false
                return when (event) {
                    is KeyEvent.Char -> { ... insertChar ... }
                    is KeyEvent.Press -> TextFieldKeyBindings.match(keyBindings, event)?.action?.invoke(holder) ?: false
                    else -> false
                }
            }
        }
    }
    DisposableEffect(handler) { onDispose { focusController?.release(handler) } }

    Layout(
        modifier = modifier
            .background(if (focused) NwTheme.colors.surfacePressed else NwTheme.colors.surfaceHover, NwTheme.shapes.medium)
            .let { m -> if (focused) m.border(BorderStroke(1, NwTheme.colors.accent), NwTheme.shapes.medium) else m }
            .padding(horizontal = NwTheme.dimens.space4, vertical = 1)
            .onSizeChanged { holder.visibleWidthPx = it.width; holder.paddingLeftPx = 0 /* relative to inner Layout */ }
            .pointerInput { ev, localX, _ ->
                if (!enabled) return@pointerInput false
                when (ev) {
                    is PointerEvent.Press -> {
                        focusController?.request(handler)
                        val shift = (ev as? PointerEvent.Press)?.let { (it.modifiers and GLFW_MOD_SHIFT) != 0 } ?: false  // (need shift state)
                        holder.mousePress(localX, shift, Util.getMillis())
                        true
                    }
                    is PointerEvent.Drag -> { if (ev.button == 0) { holder.mouseDrag(localX); true } else false }
                    else -> false
                }
            },
        renderer = TextFieldRenderer(holder, font, /* colors */, /* focus + blink lambdas */),
    )

    // Placeholder rendered as separate composable layered above when empty + !focused.
    // (Or rendered inside TextFieldRenderer when state.text.isEmpty.)
}
```

**Important caveat:** existing `PointerEvent.Press/Drag` don't carry modifiers (only `button`). So the shift-click-extends-selection case requires either:
(a) Adding `modifiers: Int` to `PointerEvent.Press`/`Drag` (small change to `ui/input/PointerEvent.kt`).
(b) Reading current keyboard state via GLFW directly (`InputConstants.isKeyDown(...)`).

Plan goes with (a) — clean and reusable. Modify PointerEvent + the place that constructs them (NwComposeScreen.mouseClicked etc.) to forward MC's modifier int.

## File layout

**New:**
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextRange.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldState.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldKeyBindings.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldRenderer.kt`
- `src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt` (12-15 cases)
- `src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt` (5-8 cases: undo merge, paste-strips-newline, drag-extend-selection, double-click word, redo cleared on edit)

**Modified:**
- `src/main/kotlin/dev/nitka/nodewire/ui/components/TextInput.kt` — full rewrite, same public signature + new `enabled`/`keyBindings` optional params.
- `src/main/kotlin/dev/nitka/nodewire/ui/input/PointerEvent.kt` — add `modifiers: Int = 0` to `Press` and `Drag` (default keeps existing callers compiling).
- `src/main/kotlin/dev/nitka/nodewire/ui/core/NwComposeScreen.kt` — forward MC modifier int into PointerEvent.Press/Drag construction.
- `src/main/kotlin/dev/nitka/nodewire/ui/core/NwUiOwner.kt` — pass modifier through `dispatchPointer` route to focus.

## Edge cases

- **Multi-input on same screen:** focus controller routes key events to one; mouse picks per-input via hit-tester. Each gets its own holder via `remember`.
- **External `value` reset:** `LaunchedEffect(value)` syncs holder.state.text; resets undo branch.
- **`onValueChange` cycle:** `snapshotFlow { holder.state.text }.collect` notifies caller; caller's recomp doesn't loop because next composition sees `holder.state.text == value` and the LaunchedEffect is a no-op.
- **Surrogate pairs / emoji:** caret is char-index (UTF-16 units); breaks for code points > U+FFFF. NOTE in code comment, not fixed. Frequency item display names are ASCII/Latin so this is fine for our current use.
- **IME:** no composition rendering. MC's `charTyped` gives composed code points already.
- **Read-only:** `enabled = false` blocks all key+mouse edits; caret hidden; text rendered dimmed.
- **Paste multiline:** truncate to first line (`split('\n').first()`).
- **Drag past edge of input:** clamp localX to [0, visibleWidth]; auto-scroll via `ensureCaretVisible`.
- **Long text + linear `font.width(substring)`:** O(N log N) per click. Acceptable for sub-1000-char strings. Optimization (cumulative-width cache) deferred.
- **Esc with selection:** Esc still releases focus; selection retained in holder state (visible on refocus).

## Tests

`EditOpsTest`:
1. insert into empty → text/caret correct
2. insert at caret position, no selection
3. insert replaces selection
4. backspace removes one char
5. backspace at caret=0 is no-op
6. backspace with selection deletes selection only
7. deleteForward removes char at caret
8. deleteForward at end is no-op
9. moveCaretBy clamps to [0, length]
10. moveCaretBy with extend grows selection
11. moveCaretWord across whitespace → word transition
12. moveCaretWord across word → punctuation transition
13. deleteWordBackward removes to previous word boundary
14. selectAll covers full text
15. wordBoundary on empty string returns 0

`TextFieldStateHolderTest`:
1. consecutive insertChar within 500ms merges into one undo step
2. insertChar after delete starts new undo step
3. undo restores previous state, redo replays
4. redo stack cleared on new edit after undo
5. mousePress sets caret + anchor; double-click selects word; triple-click selects all
6. drag updates selection from anchor to drag position
7. paste with `\n` truncates to first line
8. external `replaceText` clears undo

Both test files run without MC bootstrap — pure data + state manipulation. `pasteFromClipboard` test injects a `clipboard: () -> String` lambda via constructor parameter so we don't touch MC's Keyboard singleton.

## Out of scope (separate sub-projects)

- Tab focus navigation across focusables (next sub-project).
- Button / Checkbox / Select keyboard activation.
- Multi-line `TextArea` (will reuse this entire infrastructure when added).
- IME composition rendering / underline.
- Surrogate-pair aware caret.
- Drag-scroll while drag-selecting beyond input bounds (auto-scroll on drag-out).
- Cumulative-width cache for long-text performance.

## Decisions log

- **Pure data layer + stateful holder split.** Mirrors Jewel's `TextFieldState` (immutable) + `TextFieldBuffer` (mutable) shape. Lets us unit-test 80% of behavior without MC bootstrap.
- **Key bindings as data, not switch.** Allows callers (future code editor, password input) to override / extend.
- **Renderer instead of layered composables for caret/selection.** Existing layout system doesn't support px-precise child positioning; one Renderer with `fillRect` is cleaner than fighting the layout.
- **530/470ms blink phases.** Matches Jewel / IntelliJ.
- **First-line paste only.** Matches IntelliJ single-line input behavior; multi-line `TextArea` will accept newlines.
- **`PointerEvent` gains modifiers.** Needed for Shift-click selection. Default param keeps existing call sites green.
