# TextInput Jewel-level UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite `TextInput` UX to IntelliJ Jewel level — real blinking caret, selection (Shift-extend, word jump, drag select, double/triple click), full clipboard + undo shortcuts. Visual style preserved.

**Architecture:** Pure-data layer (`TextRange`, `TextFieldState`) + pure `EditOps` (testable without MC bootstrap) + stateful `TextFieldStateHolder` (mutable state + undo merge + mouse + scroll + clipboard) + data-driven `TextFieldKeyBindings` table + dedicated `TextFieldRenderer` for caret/selection/scroll-clip. `TextInput.kt` becomes a thin shell wiring these together. PointerEvent stays untouched — modifier state is read via vanilla `Screen.hasShiftDown()` directly inside the input's mouse handler (no PointerEvent schema change needed).

**Tech Stack:** Kotlin 2.0.20, MC Forge 1.20.1, Compose runtime, JUnit 5 (pure tests). Reads `Minecraft.getInstance().keyboardHandler.clipboard` for clipboard; injectable lambda in holder for tests.

**Spec:** `docs/superpowers/specs/2026-05-17-textinput-jewel-ux-design.md`

---

## File Structure

**New:**
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextRange.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldState.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldKeyBindings.kt`
- `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldRenderer.kt`
- `src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt`
- `src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt`

**Modified:**
- `src/main/kotlin/dev/nitka/nodewire/ui/components/TextInput.kt` — rewrite as thin shell; same public signature.

**Decision (deviation from spec):** the spec's "modify PointerEvent + NwComposeScreen + NwUiOwner to carry modifiers" path is NOT needed. Use `net.minecraft.client.gui.screens.Screen.hasShiftDown()` (a vanilla static) inside the pointer handler instead — same outcome, zero ripple into the input layer. Plan reflects this.

---

## Phase 1 — Pure data + EditOps

### Task 1: `TextRange` + `TextFieldState` + EditOpsTest skeleton

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextRange.kt`
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldState.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt`

- [ ] **Step 1: Write `TextRange`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextRange.kt
package dev.nitka.nodewire.ui.input.text

/**
 * Half-open text range; `start` may equal `end` (a caret with no selection).
 * `start` is the anchor (where Shift-extend pivots from); `end` is the
 * caret (where the next character lands).
 */
data class TextRange(val start: Int, val end: Int) {
    val min: Int get() = minOf(start, end)
    val max: Int get() = maxOf(start, end)
    val length: Int get() = max - min
    val collapsed: Boolean get() = start == end

    companion object {
        val Zero = TextRange(0, 0)
        fun caret(at: Int) = TextRange(at, at)
    }
}
```

- [ ] **Step 2: Write `TextFieldState`**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldState.kt
package dev.nitka.nodewire.ui.input.text

/**
 * Immutable snapshot of a text-field's content + cursor position.
 * Mutated only via [EditOps] (pure functions that return a new state).
 */
data class TextFieldState(
    val text: String = "",
    val selection: TextRange = TextRange.Zero,
) {
    val caret: Int get() = selection.end
    val hasSelection: Boolean get() = !selection.collapsed
}
```

- [ ] **Step 3: Write 3 failing tests for selection helpers**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt
package dev.nitka.nodewire.ui.input.text

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EditOpsTest {
    @Test fun `TextRange min max work regardless of direction`() {
        val r1 = TextRange(3, 7)
        val r2 = TextRange(7, 3)
        assertEquals(3, r1.min); assertEquals(7, r1.max); assertEquals(4, r1.length)
        assertEquals(3, r2.min); assertEquals(7, r2.max); assertEquals(4, r2.length)
        assertFalse(r1.collapsed); assertFalse(r2.collapsed)
    }

    @Test fun `TextRange caret() builds collapsed range at position`() {
        val r = TextRange.caret(5)
        assertEquals(5, r.start); assertEquals(5, r.end); assertTrue(r.collapsed); assertEquals(0, r.length)
    }

    @Test fun `TextFieldState caret returns selection end`() {
        val s = TextFieldState("hello", TextRange(2, 4))
        assertEquals(4, s.caret); assertTrue(s.hasSelection)
    }
}
```

- [ ] **Step 4: Run tests; expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.EditOpsTest" -i`
Expected: PASS — all three tests green. (These tests verify the data types themselves; no `EditOps` referenced yet.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextRange.kt \
        src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldState.kt \
        src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt
git commit -m "$(cat <<'EOF'
feat(textinput): TextRange + TextFieldState pure data types

Foundation for the Jewel-style text editing rewrite. TextRange uses
start/end (start = selection anchor, end = caret) so Shift-extend can
preserve the anchor across nav ops without needing a separate field.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `EditOps` — insert / deleteSelection / backspace / deleteForward

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `EditOpsTest.kt`:

```kotlin
    @Test fun `insert into empty state places caret after inserted text`() {
        val r = EditOps.insert(TextFieldState(), "abc")
        assertEquals("abc", r.text)
        assertEquals(TextRange.caret(3), r.selection)
    }

    @Test fun `insert at caret leaves text intact around it`() {
        val s = TextFieldState("ace", TextRange.caret(1))
        val r = EditOps.insert(s, "b")
        assertEquals("abce", r.text)
        assertEquals(TextRange.caret(2), r.selection)
    }

    @Test fun `insert replaces selection`() {
        val s = TextFieldState("axxxe", TextRange(1, 4))
        val r = EditOps.insert(s, "b")
        assertEquals("abe", r.text)
        assertEquals(TextRange.caret(2), r.selection)
    }

    @Test fun `deleteSelection removes selected range and collapses caret`() {
        val s = TextFieldState("hello world", TextRange(6, 11))
        val r = EditOps.deleteSelection(s)
        assertEquals("hello ", r.text)
        assertEquals(TextRange.caret(6), r.selection)
    }

    @Test fun `deleteSelection is a no-op when collapsed`() {
        val s = TextFieldState("abc", TextRange.caret(1))
        assertEquals(s, EditOps.deleteSelection(s))
    }

    @Test fun `backspace removes char before caret`() {
        val r = EditOps.backspace(TextFieldState("abc", TextRange.caret(2)))
        assertEquals("ac", r.text); assertEquals(TextRange.caret(1), r.selection)
    }

    @Test fun `backspace at caret 0 is no-op`() {
        val s = TextFieldState("abc", TextRange.caret(0))
        assertEquals(s, EditOps.backspace(s))
    }

    @Test fun `backspace with selection deletes selection only`() {
        val s = TextFieldState("abcdef", TextRange(1, 4))
        val r = EditOps.backspace(s)
        assertEquals("aef", r.text); assertEquals(TextRange.caret(1), r.selection)
    }

    @Test fun `deleteForward removes char at caret`() {
        val r = EditOps.deleteForward(TextFieldState("abc", TextRange.caret(1)))
        assertEquals("ac", r.text); assertEquals(TextRange.caret(1), r.selection)
    }

    @Test fun `deleteForward at end is no-op`() {
        val s = TextFieldState("abc", TextRange.caret(3))
        assertEquals(s, EditOps.deleteForward(s))
    }
```

- [ ] **Step 2: Run tests; expect FAIL (EditOps unresolved)**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.EditOpsTest" -i`
Expected: FAIL — `EditOps` unresolved.

- [ ] **Step 3: Implement `EditOps` (initial subset)**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt
package dev.nitka.nodewire.ui.input.text

/**
 * Pure functions on [TextFieldState]. Each returns a new state; no MC
 * dependencies — safe for unit tests. Caller (TextFieldStateHolder) is
 * responsible for clamping selection within text bounds AFTER any text
 * mutation that could leave caret out of range.
 */
object EditOps {
    /** Replace selection (or insert at caret) with [str]; caret lands after inserted text. */
    fun insert(state: TextFieldState, str: String): TextFieldState {
        val s = state.selection
        val newText = state.text.substring(0, s.min) + str + state.text.substring(s.max)
        val caretAt = s.min + str.length
        return TextFieldState(newText, TextRange.caret(caretAt))
    }

    /** Drop the selected text; caret collapses to selection.min. No-op if collapsed. */
    fun deleteSelection(state: TextFieldState): TextFieldState {
        if (state.selection.collapsed) return state
        val s = state.selection
        val newText = state.text.substring(0, s.min) + state.text.substring(s.max)
        return TextFieldState(newText, TextRange.caret(s.min))
    }

    /** Delete selection if present; otherwise remove the char before caret. */
    fun backspace(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val caret = state.caret
        if (caret == 0) return state
        return TextFieldState(
            text = state.text.removeRange(caret - 1, caret),
            selection = TextRange.caret(caret - 1),
        )
    }

    /** Delete selection if present; otherwise remove the char at caret. */
    fun deleteForward(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val caret = state.caret
        if (caret >= state.text.length) return state
        return TextFieldState(
            text = state.text.removeRange(caret, caret + 1),
            selection = TextRange.caret(caret),
        )
    }
}
```

- [ ] **Step 4: Run tests; expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.EditOpsTest" -i`
Expected: PASS — all 13 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt \
        src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt
git commit -m "$(cat <<'EOF'
feat(textinput): EditOps insert/delete/backspace/deleteForward

Pure functions on TextFieldState — no MC dependency, unit-tested.
Selection replacement on insert + uniform "selection-first" treatment
of backspace/deleteForward (matches Jewel / IDEA semantics).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Caret navigation + word boundary

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test fun `moveCaretBy clamps to text bounds`() {
        val s = TextFieldState("abc", TextRange.caret(1))
        assertEquals(TextRange.caret(0), EditOps.moveCaretBy(s, -5, extend = false).selection)
        assertEquals(TextRange.caret(3), EditOps.moveCaretBy(s, +5, extend = false).selection)
    }

    @Test fun `moveCaretBy with extend preserves anchor`() {
        val s = TextFieldState("abcdef", TextRange(2, 2))
        val r = EditOps.moveCaretBy(s, +2, extend = true)
        assertEquals(TextRange(2, 4), r.selection)
    }

    @Test fun `moveCaretBy without extend collapses selection toward direction`() {
        // Has existing selection; nav without Shift should collapse, NOT extend.
        val s = TextFieldState("abcdef", TextRange(2, 5))
        val r = EditOps.moveCaretBy(s, +1, extend = false)
        assertEquals(TextRange.caret(6), r.selection)
    }

    @Test fun `wordBoundary forward skips whitespace into word`() {
        assertEquals(7, EditOps.wordBoundary("hello world", 5, +1))
    }

    @Test fun `wordBoundary forward stops at end of word`() {
        assertEquals(5, EditOps.wordBoundary("hello world", 0, +1))
    }

    @Test fun `wordBoundary backward stops at start of word`() {
        assertEquals(6, EditOps.wordBoundary("hello world", 11, -1))
    }

    @Test fun `wordBoundary on empty returns 0`() {
        assertEquals(0, EditOps.wordBoundary("", 0, +1))
        assertEquals(0, EditOps.wordBoundary("", 0, -1))
    }

    @Test fun `moveCaretWord advances by one word`() {
        val s = TextFieldState("foo bar baz", TextRange.caret(0))
        assertEquals(TextRange.caret(3), EditOps.moveCaretWord(s, +1, extend = false).selection)
    }

    @Test fun `moveCaretToLineStart and End collapse caret`() {
        val s = TextFieldState("hello", TextRange.caret(2))
        assertEquals(TextRange.caret(0), EditOps.moveCaretToLineStart(s, extend = false).selection)
        assertEquals(TextRange.caret(5), EditOps.moveCaretToLineEnd(s, extend = false).selection)
    }

    @Test fun `selectAll covers whole text`() {
        val s = TextFieldState("hello", TextRange.caret(2))
        assertEquals(TextRange(0, 5), EditOps.selectAll(s).selection)
    }
```

- [ ] **Step 2: Run tests; expect FAIL**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.EditOpsTest" -i`
Expected: FAIL — `moveCaretBy`, `wordBoundary`, `moveCaretWord`, `moveCaretToLineStart`, `moveCaretToLineEnd`, `selectAll` unresolved.

- [ ] **Step 3: Extend `EditOps`**

Append inside `object EditOps`:

```kotlin
    /** Move caret by [delta] chars, clamping to bounds. If [extend], keep anchor. */
    fun moveCaretBy(state: TextFieldState, delta: Int, extend: Boolean): TextFieldState {
        val newCaret = (state.caret + delta).coerceIn(0, state.text.length)
        return state.copy(selection = makeRange(state.selection.start, newCaret, extend))
    }

    /** Move caret to the nearest word boundary in [direction] (-1 or +1). */
    fun moveCaretWord(state: TextFieldState, direction: Int, extend: Boolean): TextFieldState {
        val newCaret = wordBoundary(state.text, state.caret, direction)
        return state.copy(selection = makeRange(state.selection.start, newCaret, extend))
    }

    fun moveCaretToLineStart(state: TextFieldState, extend: Boolean): TextFieldState =
        state.copy(selection = makeRange(state.selection.start, 0, extend))

    fun moveCaretToLineEnd(state: TextFieldState, extend: Boolean): TextFieldState =
        state.copy(selection = makeRange(state.selection.start, state.text.length, extend))

    fun selectAll(state: TextFieldState): TextFieldState =
        state.copy(selection = TextRange(0, state.text.length))

    /**
     * Word-boundary search using three character classes (whitespace, word,
     * other). Skips the class of the char at [from] (or before it, for
     * backward) and returns the first index where the class changes.
     */
    fun wordBoundary(text: String, from: Int, direction: Int): Int {
        if (text.isEmpty()) return 0
        return if (direction > 0) {
            if (from >= text.length) return text.length
            val startClass = classOf(text[from])
            var i = from
            while (i < text.length && classOf(text[i]) == startClass) i++
            i
        } else {
            if (from <= 0) return 0
            val startClass = classOf(text[from - 1])
            var i = from
            while (i > 0 && classOf(text[i - 1]) == startClass) i--
            i
        }
    }

    /** When extending, keep the existing anchor; otherwise collapse to caret. */
    private fun makeRange(anchor: Int, caret: Int, extend: Boolean): TextRange =
        if (extend) TextRange(anchor, caret) else TextRange.caret(caret)

    private enum class CharClass { Whitespace, Word, Other }
    private fun classOf(c: Char): CharClass = when {
        c.isWhitespace() -> CharClass.Whitespace
        c.isLetterOrDigit() -> CharClass.Word
        else -> CharClass.Other
    }
```

- [ ] **Step 4: Run tests; expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.EditOpsTest" -i`
Expected: PASS — all tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt \
        src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt
git commit -m "$(cat <<'EOF'
feat(textinput): caret navigation + word boundary in EditOps

moveCaretBy / moveCaretWord / moveCaretToLineStart / moveCaretToLineEnd
+ selectAll, plus the three-class wordBoundary helper (whitespace /
word / other) that backs Ctrl+Left/Right and Ctrl+Backspace/Delete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `deleteWordBackward` / `deleteWordForward`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test fun `deleteWordBackward removes to previous word boundary`() {
        val s = TextFieldState("foo bar baz", TextRange.caret(7))
        val r = EditOps.deleteWordBackward(s)
        assertEquals("foo  baz", r.text); assertEquals(TextRange.caret(4), r.selection)
    }

    @Test fun `deleteWordForward removes to next word boundary`() {
        val s = TextFieldState("foo bar baz", TextRange.caret(0))
        val r = EditOps.deleteWordForward(s)
        assertEquals(" bar baz", r.text); assertEquals(TextRange.caret(0), r.selection)
    }

    @Test fun `deleteWordBackward deletes selection if present`() {
        val s = TextFieldState("abcdef", TextRange(1, 4))
        val r = EditOps.deleteWordBackward(s)
        assertEquals("aef", r.text); assertEquals(TextRange.caret(1), r.selection)
    }
```

- [ ] **Step 2: Run tests; expect FAIL**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.EditOpsTest" -i`
Expected: FAIL — `deleteWordBackward`/`deleteWordForward` unresolved.

- [ ] **Step 3: Implement**

Append inside `object EditOps`:

```kotlin
    fun deleteWordBackward(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val from = wordBoundary(state.text, state.caret, -1)
        if (from == state.caret) return state
        return TextFieldState(
            text = state.text.removeRange(from, state.caret),
            selection = TextRange.caret(from),
        )
    }

    fun deleteWordForward(state: TextFieldState): TextFieldState {
        if (state.hasSelection) return deleteSelection(state)
        val to = wordBoundary(state.text, state.caret, +1)
        if (to == state.caret) return state
        return TextFieldState(
            text = state.text.removeRange(state.caret, to),
            selection = TextRange.caret(state.caret),
        )
    }
```

- [ ] **Step 4: Run tests; expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.EditOpsTest" -i`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/EditOps.kt \
        src/test/kotlin/dev/nitka/nodewire/ui/input/text/EditOpsTest.kt
git commit -m "$(cat <<'EOF'
feat(textinput): EditOps deleteWord forward + backward

Ctrl+Backspace / Ctrl+Delete behavior. Delegate to wordBoundary then
removeRange. Selection (if present) wins as in IDEA — a selected
region is deleted regardless of whether word-mode was requested.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Stateful holder

### Task 5: `TextFieldStateHolder` skeleton + state + undo stack

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt`
- Create: `src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt
package dev.nitka.nodewire.ui.input.text

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TextFieldStateHolderTest {

    private fun holder(initial: String = "", caret: Int = 0): TextFieldStateHolder =
        TextFieldStateHolder(
            initial = TextFieldState(initial, TextRange.caret(caret)),
            clipboardGet = { "" },
            clipboardSet = {},
            nowMillis = { 0L },
        )

    @Test fun `initial state matches constructor input`() {
        val h = holder("hi", caret = 2)
        assertEquals("hi", h.state.text); assertEquals(2, h.state.caret)
    }

    @Test fun `insertString appends and notifies caret`() {
        val h = holder("ab", caret = 2)
        h.insertString("c")
        assertEquals("abc", h.state.text); assertEquals(TextRange.caret(3), h.state.selection)
    }

    @Test fun `undo restores previous state and redo replays`() {
        val h = holder("a", caret = 1)
        h.insertString("b")
        assertEquals("ab", h.state.text)
        h.undo()
        assertEquals("a", h.state.text)
        h.redo()
        assertEquals("ab", h.state.text)
    }

    @Test fun `redo stack cleared after fresh edit`() {
        val h = holder("a", caret = 1)
        h.insertString("b")
        h.undo()
        h.insertString("c")
        h.redo()  // nothing to redo
        assertEquals("ac", h.state.text)
    }

    @Test fun `replaceText resets undo branch`() {
        val h = holder("ab", caret = 2)
        h.insertString("c")
        h.replaceText("xyz")
        h.undo()  // should be no-op (undo cleared)
        assertEquals("xyz", h.state.text)
    }
}
```

- [ ] **Step 2: Run tests; expect FAIL**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.TextFieldStateHolderTest" -i`
Expected: FAIL — `TextFieldStateHolder` unresolved.

- [ ] **Step 3: Implement holder skeleton**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt
package dev.nitka.nodewire.ui.input.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stateful wrapper around [TextFieldState]. Owns the undo/redo stacks,
 * scroll offset, mouse-tracking bookkeeping, and dispatches edits via
 * [EditOps]. Constructed via `remember { ... }` inside a composable so
 * its identity persists across recomposition.
 *
 * Clipboard access is injected (defaults wire to MC's KeyboardHandler in
 * the composable) so tests don't pull in MC bootstrap.
 */
class TextFieldStateHolder(
    initial: TextFieldState = TextFieldState(),
    private val clipboardGet: () -> String = { "" },
    private val clipboardSet: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    var state: TextFieldState by mutableStateOf(initial)
        private set

    private val undoStack: ArrayDeque<TextFieldState> = ArrayDeque<TextFieldState>().apply { add(initial) }
    private val redoStack: ArrayDeque<TextFieldState> = ArrayDeque()
    private var lastUndoPushAt: Long = 0L
    private var lastUndoMergeable: Boolean = false

    private val mergeWindowMs: Long = 500L
    private val undoCapacity: Int = 100

    /**
     * Apply [produce] to current state and push onto undo. If [mergeable]
     * is true AND the previous push was also mergeable within
     * [mergeWindowMs], the new state overwrites the top of the undo stack
     * instead of pushing a fresh entry — keeps "type a sentence" into one
     * undo step.
     */
    fun mutate(mergeable: Boolean = false, produce: (TextFieldState) -> TextFieldState) {
        val next = produce(state)
        if (next == state) return
        val now = nowMillis()
        val canMerge = mergeable && lastUndoMergeable && (now - lastUndoPushAt) < mergeWindowMs
        if (canMerge && undoStack.isNotEmpty()) {
            undoStack[undoStack.size - 1] = next
        } else {
            undoStack.addLast(next)
            if (undoStack.size > undoCapacity) undoStack.removeFirst()
        }
        lastUndoPushAt = now
        lastUndoMergeable = mergeable
        redoStack.clear()
        state = next
    }

    fun insertString(s: String) = mutate(mergeable = true) { EditOps.insert(it, s) }

    /** Replace text entirely (external value reset). Clears undo as a fresh branch. */
    fun replaceText(text: String) {
        val newState = TextFieldState(text, TextRange.caret(text.length))
        state = newState
        undoStack.clear(); undoStack.addLast(newState)
        redoStack.clear()
        lastUndoMergeable = false
    }

    fun undo() {
        if (undoStack.size <= 1) return
        val current = undoStack.removeLast()
        redoStack.addLast(current)
        state = undoStack.last()
        lastUndoMergeable = false
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        state = next
        lastUndoMergeable = false
    }
}
```

- [ ] **Step 4: Run tests; expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.TextFieldStateHolderTest" -i`
Expected: PASS — 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt \
        src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt
git commit -m "$(cat <<'EOF'
feat(textinput): TextFieldStateHolder + undo/redo with merge window

Stateful holder around TextFieldState with mutable Compose state, undo
stack (cap 100), redo stack cleared on fresh edit, replaceText() to
sync external value resets. Clipboard / time injected for testability.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Holder edit/nav/clipboard methods + undo merge test

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test fun `consecutive inserts within merge window collapse to one undo step`() {
        var now = 0L
        val h = TextFieldStateHolder(
            initial = TextFieldState(""),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { now },
        )
        h.insertString("a"); now += 100
        h.insertString("b"); now += 100
        h.insertString("c")
        assertEquals("abc", h.state.text)
        h.undo()
        assertEquals("", h.state.text)
    }

    @Test fun `insert after delete starts new undo step`() {
        var now = 0L
        val h = TextFieldStateHolder(
            initial = TextFieldState("ab", TextRange.caret(2)),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { now },
        )
        h.insertString("c"); now += 100
        h.backspace(); now += 100
        h.insertString("d")
        assertEquals("abd", h.state.text)
        h.undo(); assertEquals("ab", h.state.text)        // d
        h.undo(); assertEquals("abc", h.state.text)       // backspace
        h.undo(); assertEquals("ab", h.state.text)        // c
    }

    @Test fun `paste truncates at first newline`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("", TextRange.caret(0)),
            clipboardGet = { "first\nsecond" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.pasteFromClipboard()
        assertEquals("first", h.state.text)
    }

    @Test fun `cut copies selection then deletes it`() {
        val written = StringBuilder()
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello", TextRange(0, 5)),
            clipboardGet = { "" }, clipboardSet = { written.append(it) },
            nowMillis = { 0L },
        )
        h.cutToClipboard()
        assertEquals("hello", written.toString())
        assertEquals("", h.state.text)
    }

    @Test fun `selectAll selects whole text`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hi", TextRange.caret(0)),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.selectAll()
        assertEquals(TextRange(0, 2), h.state.selection)
    }
```

- [ ] **Step 2: Run tests; expect FAIL**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.TextFieldStateHolderTest" -i`
Expected: FAIL — `backspace`, `pasteFromClipboard`, `cutToClipboard`, `selectAll` unresolved on holder.

- [ ] **Step 3: Extend `TextFieldStateHolder`**

Add inside the class (after the existing `insertString` method):

```kotlin
    fun backspace() = mutate { EditOps.backspace(it) }
    fun deleteForward() = mutate { EditOps.deleteForward(it) }
    fun deleteWordBackward() = mutate { EditOps.deleteWordBackward(it) }
    fun deleteWordForward() = mutate { EditOps.deleteWordForward(it) }

    fun moveCaretBy(delta: Int, extend: Boolean) = mutate { EditOps.moveCaretBy(it, delta, extend) }
    fun moveCaretWord(direction: Int, extend: Boolean) = mutate { EditOps.moveCaretWord(it, direction, extend) }
    fun moveCaretToLineStart(extend: Boolean) = mutate { EditOps.moveCaretToLineStart(it, extend) }
    fun moveCaretToLineEnd(extend: Boolean) = mutate { EditOps.moveCaretToLineEnd(it, extend) }

    fun selectAll() = mutate { EditOps.selectAll(it) }

    fun copyToClipboard() {
        if (state.hasSelection) clipboardSet(state.text.substring(state.selection.min, state.selection.max))
    }

    fun cutToClipboard() {
        if (!state.hasSelection) return
        clipboardSet(state.text.substring(state.selection.min, state.selection.max))
        mutate { EditOps.deleteSelection(it) }
    }

    fun pasteFromClipboard() {
        val text = clipboardGet().substringBefore('\n')
        if (text.isEmpty()) return
        mutate { EditOps.insert(it, text) }
    }

    fun insertChar(codePoint: Int) {
        if (codePoint < 0x20 || codePoint == 0x7F) return
        insertString(Character.toChars(codePoint).concatToString())
    }
```

- [ ] **Step 4: Run tests; expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.TextFieldStateHolderTest" -i`
Expected: PASS — 10 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt \
        src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt
git commit -m "$(cat <<'EOF'
feat(textinput): holder edit/nav/clipboard methods

Wires EditOps to the holder's mutate pipeline. Clipboard ops use the
injectable getter/setter (MC's KeyboardHandler at runtime, stub in
tests). Paste truncates at first newline — matches IDEA single-line
field behavior.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Mouse tracking on the holder

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt`
- Modify: `src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
    @Test fun `mousePress sets caret and anchor`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        // fontWidthOf is a stub: 6 px per char (matches MC's default font width close enough for testing).
        h.fontWidthOf = { it.length * 6 }
        h.paddingLeftPx = 0
        h.mousePress(localX = 18, shift = false, now = 1000L)
        assertEquals(TextRange.caret(3), h.state.selection)
    }

    @Test fun `mousePress with shift extends selection from existing anchor`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world", TextRange.caret(2)),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 36, shift = true, now = 1000L)
        // anchor = 2 (previous caret), new caret = 6
        assertEquals(TextRange(2, 6), h.state.selection)
    }

    @Test fun `double click selects word around caret`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 12, shift = false, now = 1000L)  // caret at 2
        h.mousePress(localX = 12, shift = false, now = 1100L)  // same idx, within 400ms
        assertEquals(TextRange(0, 5), h.state.selection)
    }

    @Test fun `triple click selects all`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 12, shift = false, now = 1000L)
        h.mousePress(localX = 12, shift = false, now = 1100L)
        h.mousePress(localX = 12, shift = false, now = 1200L)
        assertEquals(TextRange(0, 11), h.state.selection)
    }

    @Test fun `mouseDrag updates selection from anchor`() {
        val h = TextFieldStateHolder(
            initial = TextFieldState("hello world"),
            clipboardGet = { "" }, clipboardSet = {},
            nowMillis = { 0L },
        )
        h.fontWidthOf = { it.length * 6 }
        h.mousePress(localX = 6, shift = false, now = 1000L)   // anchor at 1
        h.mouseDrag(localX = 30)
        assertEquals(TextRange(1, 5), h.state.selection)
    }
```

- [ ] **Step 2: Run tests; expect FAIL**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.TextFieldStateHolderTest" -i`
Expected: FAIL — `fontWidthOf`, `paddingLeftPx`, `mousePress`, `mouseDrag` unresolved.

- [ ] **Step 3: Extend holder with mouse state + methods**

Add inside the class:

```kotlin
    // Set each frame by the composable so we can convert localX → char index.
    var fontWidthOf: (String) -> Int = { 0 }
    var paddingLeftPx: Int = 0
    var scrollXPx: Int = 0
    var visibleWidthPx: Int = 0

    private var selectionAnchor: Int = 0
    private var lastClickAt: Long = -10_000L
    private var lastClickIdx: Int = -1
    private var clickStreak: Int = 0

    private val doubleClickWindowMs: Long = 400L

    /**
     * Convert a local x (within the input's outer bounds) to a character
     * index in the text. Binary search over substring widths.
     */
    fun pixelToCharIndex(localX: Int): Int {
        val px = (localX - paddingLeftPx + scrollXPx).coerceAtLeast(0)
        val text = state.text
        var lo = 0; var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fontWidthOf(text.substring(0, mid)) <= px) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun mousePress(localX: Int, shift: Boolean, now: Long) {
        val idx = pixelToCharIndex(localX)
        if (now - lastClickAt < doubleClickWindowMs && lastClickIdx == idx) {
            clickStreak++
        } else {
            clickStreak = 1
        }
        lastClickAt = now
        lastClickIdx = idx

        when (clickStreak) {
            1 -> {
                if (shift) {
                    state = state.copy(selection = TextRange(state.selection.start, idx))
                } else {
                    selectionAnchor = idx
                    state = state.copy(selection = TextRange.caret(idx))
                }
            }
            2 -> {
                val from = EditOps.wordBoundary(state.text, idx, -1)
                val to = EditOps.wordBoundary(state.text, idx, +1)
                selectionAnchor = from
                state = state.copy(selection = TextRange(from, to))
            }
            else -> {
                selectionAnchor = 0
                state = state.copy(selection = TextRange(0, state.text.length))
                clickStreak = 0  // reset so the next click starts fresh
            }
        }
    }

    fun mouseDrag(localX: Int) {
        val idx = pixelToCharIndex(localX)
        state = state.copy(selection = TextRange(selectionAnchor, idx))
    }
```

Note: mouse ops mutate `state` directly (not via `mutate { ... }`) — we don't want selection-only changes to fill the undo stack.

- [ ] **Step 4: Run tests; expect PASS**

Run: `./gradlew test --tests "dev.nitka.nodewire.ui.input.text.TextFieldStateHolderTest" -i`
Expected: PASS — 15 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt \
        src/test/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolderTest.kt
git commit -m "$(cat <<'EOF'
feat(textinput): mouse tracking — press, drag, double/triple click

pixelToCharIndex via binary search on font widths; click streak
detection (400ms window) drives word-select / select-all on
double / triple click. Drag updates selection from the press anchor.
Mouse ops don't touch the undo stack — selection-only changes
shouldn't be reachable via Ctrl+Z.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — Key bindings table

### Task 8: `TextFieldKeyBindings` data table + match function

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldKeyBindings.kt`

(No new tests — these are pure data + a 3-line match function. Bindings will be exercised end-to-end via the TextInput composable rewrite in Task 10.)

- [ ] **Step 1: Implement the bindings**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldKeyBindings.kt
package dev.nitka.nodewire.ui.input.text

import com.mojang.blaze3d.platform.InputConstants
import dev.nitka.nodewire.ui.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Data-driven key bindings for [TextFieldStateHolder]. Each [KeyBinding]
 * maps a (keyCode, modifiers) pair to a holder action returning whether
 * it consumed the event.
 *
 * Modifier matching: bindings compare against the GLFW modifier bitmask
 * with [MOD_MASK] applied so lock keys (CAPS, NUM) don't break matches.
 */
data class KeyBinding(
    val keyCode: Int,
    val modifiers: Int = 0,
    val action: (TextFieldStateHolder) -> Boolean,
)

object TextFieldKeyBindings {
    private const val MOD_MASK = GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT or
        GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SUPER

    val DEFAULT: List<KeyBinding> = listOf(
        // Navigation
        KeyBinding(InputConstants.KEY_LEFT)                                          { it.moveCaretBy(-1, false); true },
        KeyBinding(InputConstants.KEY_LEFT, GLFW.GLFW_MOD_SHIFT)                     { it.moveCaretBy(-1, true);  true },
        KeyBinding(InputConstants.KEY_LEFT, GLFW.GLFW_MOD_CONTROL)                   { it.moveCaretWord(-1, false); true },
        KeyBinding(InputConstants.KEY_LEFT, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { it.moveCaretWord(-1, true); true },
        KeyBinding(InputConstants.KEY_RIGHT)                                         { it.moveCaretBy(+1, false); true },
        KeyBinding(InputConstants.KEY_RIGHT, GLFW.GLFW_MOD_SHIFT)                    { it.moveCaretBy(+1, true);  true },
        KeyBinding(InputConstants.KEY_RIGHT, GLFW.GLFW_MOD_CONTROL)                  { it.moveCaretWord(+1, false); true },
        KeyBinding(InputConstants.KEY_RIGHT, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { it.moveCaretWord(+1, true); true },
        KeyBinding(InputConstants.KEY_HOME)                                          { it.moveCaretToLineStart(false); true },
        KeyBinding(InputConstants.KEY_HOME, GLFW.GLFW_MOD_SHIFT)                     { it.moveCaretToLineStart(true);  true },
        KeyBinding(InputConstants.KEY_END)                                           { it.moveCaretToLineEnd(false); true },
        KeyBinding(InputConstants.KEY_END, GLFW.GLFW_MOD_SHIFT)                      { it.moveCaretToLineEnd(true);  true },
        // Edit
        KeyBinding(InputConstants.KEY_BACKSPACE)                                     { it.backspace(); true },
        KeyBinding(InputConstants.KEY_BACKSPACE, GLFW.GLFW_MOD_CONTROL)              { it.deleteWordBackward(); true },
        KeyBinding(InputConstants.KEY_DELETE)                                        { it.deleteForward(); true },
        KeyBinding(InputConstants.KEY_DELETE, GLFW.GLFW_MOD_CONTROL)                 { it.deleteWordForward(); true },
        // Selection / clipboard
        KeyBinding(InputConstants.KEY_A, GLFW.GLFW_MOD_CONTROL)                      { it.selectAll(); true },
        KeyBinding(InputConstants.KEY_C, GLFW.GLFW_MOD_CONTROL)                      { it.copyToClipboard(); true },
        KeyBinding(InputConstants.KEY_X, GLFW.GLFW_MOD_CONTROL)                      { it.cutToClipboard(); true },
        KeyBinding(InputConstants.KEY_V, GLFW.GLFW_MOD_CONTROL)                      { it.pasteFromClipboard(); true },
        // Undo / redo
        KeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL)                      { it.undo(); true },
        KeyBinding(InputConstants.KEY_Z, GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT) { it.redo(); true },
        KeyBinding(InputConstants.KEY_Y, GLFW.GLFW_MOD_CONTROL)                      { it.redo(); true },
        // Submit / cancel — handled by composable; bindings just call hooks
        KeyBinding(InputConstants.KEY_RETURN)                                        { it.submit(); true },
        KeyBinding(InputConstants.KEY_NUMPADENTER)                                   { it.submit(); true },
        KeyBinding(InputConstants.KEY_ESCAPE)                                        { it.releaseFocus(); true },
    )

    fun match(bindings: List<KeyBinding>, event: KeyEvent.Press): KeyBinding? {
        val mods = event.modifiers and MOD_MASK
        return bindings.firstOrNull { it.keyCode == event.keyCode && it.modifiers == mods }
    }
}
```

- [ ] **Step 2: Extend holder with `submit` / `releaseFocus` hooks**

Add inside `TextFieldStateHolder`:

```kotlin
    /** Called when Enter is pressed; composable owns the actual callback. */
    var onSubmit: () -> Unit = {}
    fun submit() = onSubmit()

    /** Called when Esc is pressed; composable owns the actual focus controller. */
    var onReleaseFocus: () -> Unit = {}
    fun releaseFocus() = onReleaseFocus()
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS. `InputConstants.KEY_*` and GLFW codes resolve via vanilla deps. **If a constant name differs** (e.g. `InputConstants.KEY_RETURN` vs `KEY_ENTER`) — fix by inspecting `com.mojang.blaze3d.platform.InputConstants` via:

```bash
javap -classpath build/moddev/artifacts/forge-1.20.1-47.4.10.jar com.mojang.blaze3d.platform.InputConstants | grep -E "KEY_(LEFT|RIGHT|HOME|END|BACKSPACE|DELETE|RETURN|NUMPADENTER|ESCAPE|A|C|V|X|Y|Z)\b"
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldKeyBindings.kt \
        src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt
git commit -m "$(cat <<'EOF'
feat(textinput): data-driven key bindings table

26 default bindings cover navigation (←→ Home End + Shift / Ctrl
combinations), edit (Backspace / Delete + Ctrl-word variants),
clipboard (Ctrl+A/C/X/V), undo (Ctrl+Z/Y), submit (Enter), cancel
(Esc). Holder gains onSubmit / onReleaseFocus hooks the composable
will wire up.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 — Renderer + composable rewrite

### Task 9: `TextFieldRenderer` — caret + selection + scroll-clip

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldRenderer.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt` — add `caretPixelX` / `selectionPixelRange` / `ensureCaretVisible` / `fontHeightPx` field.

- [ ] **Step 1: Add holder helpers**

Append inside `TextFieldStateHolder`:

```kotlin
    var fontHeightPx: Int = 9

    /** Pixel x of the caret in text-local coordinates (NOT including paddingLeft). */
    fun caretPixelX(): Int = fontWidthOf(state.text.substring(0, state.caret))

    /** Pixel range of the selection in text-local coordinates, or null if collapsed. */
    fun selectionPixelRange(): IntRange? {
        val sel = state.selection
        if (sel.collapsed) return null
        val a = fontWidthOf(state.text.substring(0, sel.min))
        val b = fontWidthOf(state.text.substring(0, sel.max))
        return a..b
    }

    /** Scroll `scrollXPx` so caret stays within the visible region (with a small gutter). */
    fun ensureCaretVisible() {
        if (visibleWidthPx <= 0) return
        val caretX = caretPixelX()
        val gutter = 2
        val visibleStart = scrollXPx + gutter
        val visibleEnd = scrollXPx + visibleWidthPx - gutter
        if (caretX < visibleStart) scrollXPx = (caretX - gutter).coerceAtLeast(0)
        else if (caretX > visibleEnd) scrollXPx = caretX - visibleWidthPx + gutter
    }
```

- [ ] **Step 2: Implement renderer**

```kotlin
// src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldRenderer.kt
package dev.nitka.nodewire.ui.input.text

import dev.nitka.nodewire.ui.core.UiNode
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.NwCanvas
import dev.nitka.nodewire.ui.render.Renderer
import net.minecraft.client.gui.Font

/**
 * Draws text, selection highlight (under text), and a blinking caret bar
 * for a [TextFieldStateHolder]. Replaces the original "static Text + 1×7
 * caret Box" layout — that approach can't render selection or position
 * the caret at sub-character offsets.
 *
 * The renderer is the visual; the holder is the model. No state lives
 * inside the renderer.
 */
class TextFieldRenderer(
    private val holder: TextFieldStateHolder,
    private val font: Font,
    private val textColor: Color,
    private val placeholderColor: Color,
    private val selectionColor: Color,
    private val caretColor: Color,
    private val placeholder: String,
    private val isFocused: () -> Boolean,
    private val blinkOn: () -> Boolean,
) : Renderer {

    override fun NwCanvas.render(node: UiNode) {
        val state = holder.state
        val w = node.layoutWidth
        val h = node.layoutHeight
        val padL = holder.paddingLeftPx
        val textY = (h - holder.fontHeightPx) / 2

        pushClip(0, 0, w, h)
        try {
            // Empty + unfocused → placeholder
            if (state.text.isEmpty() && !isFocused() && placeholder.isNotEmpty()) {
                drawText(placeholder, padL, textY, placeholderColor)
                return
            }

            // Selection rect under text
            holder.selectionPixelRange()?.let { range ->
                val x = padL + range.first - holder.scrollXPx
                val width = (range.last - range.first).coerceAtLeast(1)
                fillRect(x, textY, width, holder.fontHeightPx, selectionColor)
            }

            // Text
            drawText(state.text, padL - holder.scrollXPx, textY, textColor)

            // Caret
            if (isFocused() && (blinkOn() || state.hasSelection)) {
                val cx = padL + holder.caretPixelX() - holder.scrollXPx
                fillRect(cx, textY, 1, holder.fontHeightPx, caretColor)
            }
        } finally {
            popClip()
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldRenderer.kt \
        src/main/kotlin/dev/nitka/nodewire/ui/input/text/TextFieldStateHolder.kt
git commit -m "$(cat <<'EOF'
feat(textinput): TextFieldRenderer (selection + caret + scroll clip)

One Renderer replaces the prior static-Text + fixed caret Box layout.
Draws (in order): selection rect, text with horizontal scroll offset,
1-px caret bar gated by focus + blink phase. pushClip prevents
overflow when text exceeds visible width. Placeholder rendered when
empty + unfocused.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: `TextInput` composable rewrite

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/components/TextInput.kt` — full rewrite, same public signature.

- [ ] **Step 1: Replace `TextInput.kt` content**

```kotlin
package dev.nitka.nodewire.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.KeyEvent
import dev.nitka.nodewire.ui.input.KeyHandler
import dev.nitka.nodewire.ui.input.LocalKeyFocus
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.input.text.KeyBinding
import dev.nitka.nodewire.ui.input.text.TextFieldKeyBindings
import dev.nitka.nodewire.ui.input.text.TextFieldRenderer
import dev.nitka.nodewire.ui.input.text.TextFieldState
import dev.nitka.nodewire.ui.input.text.TextFieldStateHolder
import dev.nitka.nodewire.ui.input.text.TextRange
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.modifier.input.onSizeChanged
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import kotlinx.coroutines.delay
import net.minecraft.Util
import net.minecraft.client.Minecraft

/**
 * Compact text field with Jewel-level editing UX: blinking caret,
 * selection (Shift-extend, word jump, drag-select, double/triple click),
 * clipboard (Ctrl+A/C/X/V), undo/redo (Ctrl+Z/Y), submit (Enter),
 * cancel (Esc).
 *
 * Visual style preserved from the prior implementation — surface fill +
 * focus-only accent border.
 */
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

    val holder = remember {
        TextFieldStateHolder(
            initial = TextFieldState(value, TextRange.caret(value.length)),
            clipboardGet = { Minecraft.getInstance().keyboardHandler.clipboard ?: "" },
            clipboardSet = { Minecraft.getInstance().keyboardHandler.clipboard = it },
            nowMillis = { Util.getMillis() },
        )
    }

    // Wire holder runtime knobs each composition (cheap; no allocation).
    holder.fontWidthOf = { font.width(it) }
    holder.fontHeightPx = font.lineHeight
    holder.paddingLeftPx = NwTheme.dimens.space4
    val cb = rememberUpdatedState(onValueChange)
    val onSubmitState = rememberUpdatedState(onSubmit)

    // Sync external value → holder. snapshotFlow propagates internal edits → onValueChange.
    LaunchedEffect(value) { if (holder.state.text != value) holder.replaceText(value) }
    LaunchedEffect(holder) {
        snapshotFlow { holder.state.text }.collect { if (it != value) cb.value(it) }
    }

    val handler = remember(holder, keyBindings) {
        object : KeyHandler {
            override fun handle(event: KeyEvent): Boolean {
                if (!enabled) return false
                return when (event) {
                    is KeyEvent.Char -> {
                        // Ignore chars with Ctrl/Alt/Super to let shortcuts win.
                        val mods = event.modifiers
                        if (mods and (org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL or
                                org.lwjgl.glfw.GLFW.GLFW_MOD_ALT or
                                org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER) != 0) false
                        else { holder.insertChar(event.codePoint); true }
                    }
                    is KeyEvent.Press -> TextFieldKeyBindings.match(keyBindings, event)?.action?.invoke(holder) ?: false
                    else -> false
                }
            }
        }
    }
    val focused = focusController?.isFocused(handler) == true

    holder.onSubmit = { onSubmitState.value(); focusController?.release(handler) }
    holder.onReleaseFocus = { focusController?.release(handler) }

    DisposableEffect(handler) { onDispose { focusController?.release(handler) } }

    // Caret-blink ticker — drives recomposition only while focused.
    var blinkTick by remember { mutableStateOf(0) }
    LaunchedEffect(focused) {
        if (focused) while (true) { delay(50); blinkTick++ }
    }
    val blinkOn = ((Util.getMillis() % 1000L) < 530L)

    Layout(
        modifier = modifier
            .background(
                if (focused) NwTheme.colors.surfacePressed else NwTheme.colors.surfaceHover,
                NwTheme.shapes.medium,
            )
            .let { m ->
                if (focused) m.border(BorderStroke(1, NwTheme.colors.accent), NwTheme.shapes.medium)
                else m
            }
            .padding(horizontal = NwTheme.dimens.space4, vertical = 1)
            .onSizeChanged { size ->
                holder.visibleWidthPx = size.width
            }
            .pointerInput { ev, localX, _ ->
                if (!enabled) return@pointerInput false
                when (ev) {
                    is PointerEvent.Press -> {
                        focusController?.request(handler)
                        val shift = net.minecraft.client.gui.screens.Screen.hasShiftDown()
                        holder.mousePress(localX, shift, Util.getMillis())
                        true
                    }
                    is PointerEvent.Drag -> {
                        if (ev.button == 0) { holder.mouseDrag(localX); true } else false
                    }
                    else -> false
                }
            },
        renderer = TextFieldRenderer(
            holder = holder,
            font = font,
            textColor = NwTheme.colors.onSurface,
            placeholderColor = NwTheme.colors.onSurfaceDisabled,
            selectionColor = NwTheme.colors.accent.copy(alpha = 0.4f),
            caretColor = NwTheme.colors.accent,
            placeholder = placeholder,
            isFocused = { focused },
            blinkOn = { blinkOn },
        ),
    )
}
```

- [ ] **Step 2: Adjust paddingLeftPx if needed**

Inside `Layout(modifier = …)`, `padding(horizontal = space4, vertical = 1)` is OUTSIDE the renderer (it sits on the outer Box). The renderer draws inside the post-padding area — so its local (0,0) IS the post-padding top-left. That means `holder.paddingLeftPx` should be **0**, not `NwTheme.dimens.space4`. Override the assignment above:

```kotlin
    holder.paddingLeftPx = 0
```

(The outer padding modifier already adds the visual gap; renderer-local 0 is already shifted.)

- [ ] **Step 3: Build + run existing tests**

Run: `./gradlew build && ./gradlew test`
Expected: BUILD SUCCESSFUL. All prior tests still pass.

Compile errors likely categories + fixes:
- `NwTheme.colors.accent.copy(alpha = 0.4f)` — verify `Color` has `copy(alpha)`. Read `ui/render/Color.kt` if not. If not present, replace with a direct `Color(r, g, b, a)` literal using the accent's RGB.
- `Layout(modifier, renderer)` second-arg name — should be `renderer` per existing signature.
- `LocalKeyFocus.current` returning `KeyFocusController?` — handle null branches.
- `KeyEvent.Char.modifiers` field — confirmed present in `ui/input/KeyEvent.kt`.
- `Util.getMillis()` — from `net.minecraft.Util`.

Fix any compile error by mirroring the prior `TextInput.kt` code (commit before this task) — the import set + helper usages are already proven.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/components/TextInput.kt
git commit -m "$(cat <<'EOF'
feat(textinput): rewrite as thin shell over holder + renderer

Same public signature (value, onValueChange, modifier, placeholder,
onSubmit) plus two new optional params (enabled, keyBindings). Visual
style identical. All behavior shifted into TextFieldStateHolder +
TextFieldKeyBindings + TextFieldRenderer.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5 — Final validation + manual handoff

### Task 11: Full test suite + manual test plan

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS (~25 new tests + existing suite).

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Hand off in-game test plan to user**

**Do NOT run `./gradlew runClient` yourself.** Report this list to the user for manual verification:

1. Open the node editor on a LogicBlock with a Redstone-Link Input/Output node; click the search field in the freq picker popover.
2. Type "hello world" — caret should advance through each char; you should see a blinking 1-px bar at caret.
3. Use ←/→ to move caret one char at a time; Home/End jump to ends.
4. Hold Shift while pressing ←/→/Home/End — selection extends from the caret position when Shift was first pressed.
5. Hold Ctrl + ←/→ — caret jumps word boundaries (whitespace ↔ letter transitions).
6. Hold Ctrl + Shift + ←/→ — extends selection by word.
7. Press Backspace / Delete — single char delete. Ctrl+Backspace / Ctrl+Delete — word delete.
8. Ctrl+A — selects whole text. Ctrl+C — copies. Ctrl+X — cuts. Ctrl+V — pastes (multiline clipboard truncated at first \n).
9. Ctrl+Z — undoes the last "logical group" of inserts (consecutive typing within 500ms collapses to one undo step). Ctrl+Y or Ctrl+Shift+Z — redoes.
10. Click somewhere in the middle of the text — caret jumps there; Shift+click — selects from previous caret to click point.
11. Click-and-drag — selects across the dragged range.
12. Double-click — selects the word under the cursor. Triple-click — selects all.
13. Press Esc — drops focus, no submit. Press Enter — fires onSubmit + drops focus.
14. Two TextInputs on the same screen (one slot search, one node config text): clicking between them moves focus correctly; each maintains independent state.

If any of the above misbehaves, report the symptom and which step.

---

## Out of scope (separate sub-projects)

- Tab focus navigation across all focusables.
- Button / Checkbox / Select keyboard activation.
- Multi-line `TextArea` (will reuse this entire infrastructure).
- IME composition rendering / underline.
- Surrogate-pair / emoji-aware caret.
- Drag-scroll while drag-selecting beyond input bounds.
- Cumulative-width cache for very long text.
