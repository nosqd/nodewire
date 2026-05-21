# Algorithm Nodes + Generic Pins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Each task commits ONCE at the end** — do not split into TDD-step commits.

**Goal:** Add `PinType.ANY` + implicit value conversion + 11 algorithm-building nodes (control flow, math remap, stateful).

**Architecture:** Type-system Phase 1 (ANY enum + `PinValueConversion` helper + edge-read integration in both evaluators + UI feedback) unlocks Phase 2-3 nodes that all reuse ANY-typed pins. Stateful nodes serialize their state into the existing `CompoundTag` per-node store in `StatefulGraphEvaluator`.

**Tech Stack:** NeoForge 1.21.1, Kotlin 2.0.20, Compose UI runtime 1.7.0, JUnit 5.

**Branch:** `dev`. No `runClient` — use `:compileKotlin`, `:test`, `build`. Each task commits exactly once at the end.

**Spec:** [`docs/superpowers/specs/2026-05-21-algo-nodes-design.md`](../specs/2026-05-21-algo-nodes-design.md)

---

## File layout

| File | Responsibility |
| ---- | -------------- |
| `src/main/kotlin/dev/nitka/nodewire/graph/PinType.kt` | Add `ANY` enum value. |
| `src/main/kotlin/dev/nitka/nodewire/graph/PinValue.kt` | `PinValue.default(ANY)` returns `Bool(false)` (no-signal). |
| `src/main/kotlin/dev/nitka/nodewire/graph/PinValueConversion.kt` | New: conversion table + `canConvert` + `convert` entry points. |
| `src/main/kotlin/dev/nitka/nodewire/graph/StatefulGraphEvaluator.kt` | Edge-read auto-conversion. |
| `src/main/kotlin/dev/nitka/nodewire/graph/GraphEvaluator.kt` | Edge-read auto-conversion. |
| `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt` | New evaluators for 5 pure nodes + 6 stateful nodes. |
| `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` | Register 11 new `nodeType(...)` entries. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt` | Pin-color for ANY; drag-hover convertibility feedback. |
| `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwColors.kt` | `pinAny` color. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` | `changeSwitchCases` reshape mutator. |
| `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` | Config UI for `Switch.cases` + `Sequencer.steps` + `Pid` advanced. |
| `src/test/kotlin/dev/nitka/nodewire/graph/PinValueConversionTest.kt` | Conversion table tests. |
| `src/test/kotlin/dev/nitka/nodewire/graph/WireCompatibilityTest.kt` | `canConvert` matrix. |
| `src/test/kotlin/dev/nitka/nodewire/graph/AlgoNodeEvaluatorsTest.kt` | All 11 new evaluators. |

---

## Phase 1 — Type system

### Task 1: `PinType.ANY` + `PinValue.default(ANY)`

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/PinType.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/PinValue.kt`

- [ ] **Implement**

In `PinType.kt`, append `ANY` after `QUAT` (with semicolon adjustment):

```kotlin
    QUAT,
    /**
     * Generic — accepts a connection from any other pin type. Carries
     * the raw [PinValue] through the evaluator unchanged. The connect-UI
     * never rejects an ANY-end edge. See PinValueConversion for the
     * implicit-conversion rules used everywhere ANY is NOT involved.
     */
    ANY;
```

In `PinValue.kt`, extend `default`:

```kotlin
        fun default(type: PinType): PinValue = when (type) {
            PinType.BOOL -> Bool(false)
            PinType.INT -> Int(0)
            PinType.FLOAT -> Float(0f)
            PinType.REDSTONE -> Redstone(0)
            PinType.STRING -> Str("")
            PinType.VEC2 -> Vec2(0f, 0f)
            PinType.VEC3 -> Vec3(0f, 0f, 0f)
            PinType.QUAT -> Quat(0f, 0f, 0f, 1f)
            // ANY has no canonical value — Bool(false) is the cheapest
            // no-signal placeholder. Callers should usually avoid asking
            // for default(ANY); the framework treats unconnected ANY-pins
            // as "use the type from whatever IS connected".
            PinType.ANY -> Bool(false)
        }
```

- [ ] **Build**

Run: `./gradlew :compileKotlin`. Expected: `BUILD SUCCESSFUL`. Existing tests may break compile if they exhaustively `when` on `PinType` — fix those by adding the `ANY` arm (return a sensible default).

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/PinType.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/PinValue.kt
git commit -m "feat(graph): PinType.ANY + default(ANY) -> Bool(false)"
```

---

### Task 2: `PinValueConversion` + test

**Files:**
- Create: `src/main/kotlin/dev/nitka/nodewire/graph/PinValueConversion.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/graph/PinValueConversionTest.kt`

- [ ] **Implement test first**

```kotlin
package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinValueConversionTest {

    @Test fun `bool to int`() {
        assertEquals(PinValue.Int(1), PinValueConversion.convert(PinValue.Bool(true), PinType.INT))
        assertEquals(PinValue.Int(0), PinValueConversion.convert(PinValue.Bool(false), PinType.INT))
    }

    @Test fun `int to bool nonzero`() {
        assertEquals(PinValue.Bool(true), PinValueConversion.convert(PinValue.Int(7), PinType.BOOL))
        assertEquals(PinValue.Bool(false), PinValueConversion.convert(PinValue.Int(0), PinType.BOOL))
    }

    @Test fun `float to int truncates`() {
        assertEquals(PinValue.Int(3), PinValueConversion.convert(PinValue.Float(3.9f), PinType.INT))
        assertEquals(PinValue.Int(-3), PinValueConversion.convert(PinValue.Float(-3.9f), PinType.INT))
    }

    @Test fun `int to redstone clamps`() {
        assertEquals(PinValue.Redstone(15), PinValueConversion.convert(PinValue.Int(99), PinType.REDSTONE))
        assertEquals(PinValue.Redstone(0), PinValueConversion.convert(PinValue.Int(-5), PinType.REDSTONE))
        assertEquals(PinValue.Redstone(7), PinValueConversion.convert(PinValue.Int(7), PinType.REDSTONE))
    }

    @Test fun `redstone passthrough to int`() {
        assertEquals(PinValue.Int(8), PinValueConversion.convert(PinValue.Redstone(8), PinType.INT))
    }

    @Test fun `string parse to int`() {
        assertEquals(PinValue.Int(42), PinValueConversion.convert(PinValue.Str("42"), PinType.INT))
        assertEquals(PinValue.Int(0), PinValueConversion.convert(PinValue.Str("abc"), PinType.INT))
    }

    @Test fun `string parse to bool`() {
        assertEquals(PinValue.Bool(true), PinValueConversion.convert(PinValue.Str("true"), PinType.BOOL))
        assertEquals(PinValue.Bool(false), PinValueConversion.convert(PinValue.Str("garbage"), PinType.BOOL))
    }

    @Test fun `identity always returns the same value`() {
        val v = PinValue.Float(1.5f)
        assertEquals(v, PinValueConversion.convert(v, PinType.FLOAT))
    }

    @Test fun `vec3 to int falls back to default`() {
        assertEquals(PinValue.Int(0), PinValueConversion.convert(PinValue.Vec3(1f, 2f, 3f), PinType.INT))
    }

    @Test fun `canConvert allows ANY anywhere`() {
        assertTrue(PinValueConversion.canConvert(PinType.ANY, PinType.FLOAT))
        assertTrue(PinValueConversion.canConvert(PinType.FLOAT, PinType.ANY))
    }

    @Test fun `canConvert rejects vec to scalar`() {
        assertFalse(PinValueConversion.canConvert(PinType.VEC3, PinType.INT))
        assertFalse(PinValueConversion.canConvert(PinType.QUAT, PinType.FLOAT))
    }

    @Test fun `canConvert identity always true`() {
        for (t in PinType.entries) {
            assertTrue(PinValueConversion.canConvert(t, t), "self-convert failed for $t")
        }
    }
}
```

- [ ] **Implement**

```kotlin
package dev.nitka.nodewire.graph

/**
 * Implicit conversions between [PinValue] / [PinType] pairs. The single
 * source of truth for "can wire X to Y" and "what value does Y see when X
 * was sent?" — every other code path consults this helper, never bakes
 * conversion logic of its own.
 *
 * Lossy conversions (FLOAT→INT truncates toward zero, REDSTONE clamps to
 * 0..15, STRING parses with default fallback) are intentional — the
 * editor's connect-UI surfaces them as "auto: …" tooltips so the user
 * sees what's happening.
 *
 * Vector ↔ scalar conversions are NOT defined (lossy, error-prone). User
 * must explicitly split / make vectors with the Vec nodes.
 */
object PinValueConversion {

    /** True when an edge from [from] → [to] is allowed (either equal,
     *  involves ANY, or has a defined conversion). */
    fun canConvert(from: PinType, to: PinType): Boolean {
        if (from == to) return true
        if (from == PinType.ANY || to == PinType.ANY) return true
        return convertibleScalarPair(from, to)
    }

    /**
     * Convert [value] to a [PinValue] of [target]'s type. Falls back to
     * [PinValue.default] when no conversion is defined for the (source,
     * target) pair (e.g. VEC3 → INT).
     */
    fun convert(value: PinValue, target: PinType): PinValue {
        val source = typeOf(value)
        if (source == target) return value
        if (target == PinType.ANY) return value
        return convertScalar(value, source, target)
            ?: PinValue.default(target)
    }

    private fun convertibleScalarPair(from: PinType, to: PinType): Boolean {
        val scalar = setOf(
            PinType.BOOL, PinType.INT, PinType.FLOAT,
            PinType.REDSTONE, PinType.STRING,
        )
        return from in scalar && to in scalar
    }

    private fun typeOf(v: PinValue): PinType = when (v) {
        is PinValue.Bool -> PinType.BOOL
        is PinValue.Int -> PinType.INT
        is PinValue.Float -> PinType.FLOAT
        is PinValue.Redstone -> PinType.REDSTONE
        is PinValue.Str -> PinType.STRING
        is PinValue.Vec2 -> PinType.VEC2
        is PinValue.Vec3 -> PinType.VEC3
        is PinValue.Quat -> PinType.QUAT
    }

    private fun convertScalar(value: PinValue, from: PinType, to: PinType): PinValue? {
        // Stage 1: source → Double (or null if non-scalar source).
        val n: Double? = when (value) {
            is PinValue.Bool -> if (value.value) 1.0 else 0.0
            is PinValue.Int -> value.value.toDouble()
            is PinValue.Float -> value.value.toDouble()
            is PinValue.Redstone -> value.value.toDouble()
            is PinValue.Str -> when (to) {
                PinType.STRING -> return PinValue.Str(value.value)
                PinType.BOOL -> return PinValue.Bool(value.value.equals("true", ignoreCase = true))
                PinType.INT -> return PinValue.Int(value.value.toIntOrNull() ?: 0)
                PinType.FLOAT -> return PinValue.Float(value.value.toFloatOrNull() ?: 0f)
                PinType.REDSTONE -> return PinValue.Redstone(
                    (value.value.toIntOrNull() ?: 0).coerceIn(0, 15)
                )
                else -> return null
            }
            else -> return null
        }
        // Stage 2: Double → target.
        return when (to) {
            PinType.BOOL -> PinValue.Bool(n != 0.0)
            PinType.INT -> PinValue.Int(n.toInt())
            PinType.FLOAT -> PinValue.Float(n.toFloat())
            PinType.REDSTONE -> PinValue.Redstone(n.toInt().coerceIn(0, 15))
            PinType.STRING -> when (from) {
                PinType.INT, PinType.REDSTONE -> PinValue.Str(n.toInt().toString())
                PinType.FLOAT -> PinValue.Str("%.3f".format(n))
                PinType.BOOL -> PinValue.Str(if (n != 0.0) "true" else "false")
                else -> null
            }
            else -> null
        }
    }
}
```

- [ ] **Test + commit**

Run: `./gradlew test --tests "dev.nitka.nodewire.graph.PinValueConversionTest"`. Expect 12 tests pass.

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/PinValueConversion.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/PinValueConversionTest.kt
git commit -m "feat(graph): PinValueConversion helper + test"
```

---

### Task 3: Edge-read auto-conversion in evaluators

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StatefulGraphEvaluator.kt` (the line `inputs[pin.id] = value ?: PinValue.default(pin.type)`)
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/GraphEvaluator.kt` (analogous line)

- [ ] **Implement**

In `StatefulGraphEvaluator.tick`, find the existing loop:

```kotlin
val inputs = HashMap<String, PinValue>()
for (pin in node.inputs) {
    val src = incoming[nodeId to pin.id]
    val value = if (src != null) {
        if (stateful) lastOutputs[src.node to src.pin]
        else outputs[src.node to src.pin]
    } else null
    inputs[pin.id] = value ?: PinValue.default(pin.type)
}
```

Replace the assignment line with conversion-aware logic:

```kotlin
    inputs[pin.id] = when {
        value == null -> PinValue.default(pin.type)
        pin.type == PinType.ANY -> value
        else -> PinValueConversion.convert(value, pin.type)
    }
```

In `GraphEvaluator.evaluate`, apply the same change to the analogous assignment (search for `outputs[src.node to src.pin]` to locate it). Result:

```kotlin
    inputs[pin.id] = when {
        rawValue == null -> PinValue.default(pin.type)
        pin.type == PinType.ANY -> rawValue
        else -> PinValueConversion.convert(rawValue, pin.type)
    }
```

(Rename the local from `value` to `rawValue` if needed to match.)

- [ ] **Build + test**

Run: `./gradlew :compileKotlin && ./gradlew test`. All existing tests must continue to pass — pre-existing matched-type wires aren't affected because `convert(x, T)` when source type already equals T returns x unchanged.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/graph/StatefulGraphEvaluator.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/GraphEvaluator.kt
git commit -m "feat(graph): auto-convert edge values via PinValueConversion"
```

---

### Task 4: Wire-connect UI rule + ANY-pin rendering

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/ui/theme/NwColors.kt` (add `pinAny: Color`)
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt` (use `pinAny`, gate connect rule via `canConvert`)
- Test: `src/test/kotlin/dev/nitka/nodewire/graph/WireCompatibilityTest.kt`

- [ ] **Test first**

```kotlin
package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireCompatibilityTest {

    @Test fun `every scalar pair allowed`() {
        val scalars = listOf(PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.REDSTONE, PinType.STRING)
        for (a in scalars) for (b in scalars) {
            assertTrue(PinValueConversion.canConvert(a, b), "$a → $b should be allowed")
        }
    }

    @Test fun `any to and from everything`() {
        for (t in PinType.entries) {
            assertTrue(PinValueConversion.canConvert(PinType.ANY, t))
            assertTrue(PinValueConversion.canConvert(t, PinType.ANY))
        }
    }

    @Test fun `vec3 to bool rejected`() {
        assertFalse(PinValueConversion.canConvert(PinType.VEC3, PinType.BOOL))
    }

    @Test fun `quat to float rejected`() {
        assertFalse(PinValueConversion.canConvert(PinType.QUAT, PinType.FLOAT))
    }

    @Test fun `vec2 to vec3 rejected`() {
        assertFalse(PinValueConversion.canConvert(PinType.VEC2, PinType.VEC3))
    }
}
```

- [ ] **NwColors update**

In `NwColors.kt`, near the other `pin*` color values, add:

```kotlin
    val pinAny: Color = Color(0xFF9CA3AF.toInt()),  // neutral gray for any-typed pins
```

- [ ] **WireLayer update**

In `WireLayer.kt`, in the `pinColors` map construction add:

```kotlin
    PinType.ANY to c.pinAny,
```

Find the existing connect-allowed check (search for `PinType.` near a place that compares `from.type == to.type` — there's a check in the wire-drag commit path). Replace strict equality with `PinValueConversion.canConvert(from, to)`.

If a clear single check doesn't exist, locate the drag-commit handler in `WireLayer` (the place that creates an `Edge`) and gate edge creation behind `PinValueConversion.canConvert(fromPinType, toPinType)`. Mismatched-but-allowed pairs become valid edges; the runtime evaluator then auto-converts.

- [ ] **Build + tests**

Run: `./gradlew :compileKotlin && ./gradlew test --tests "dev.nitka.nodewire.graph.WireCompatibilityTest"`. Expect 5 tests pass.

- [ ] **Commit**

```bash
git add src/main/kotlin/dev/nitka/nodewire/ui/theme/NwColors.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/WireLayer.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/WireCompatibilityTest.kt
git commit -m "feat(ui): pin-color for ANY + canConvert-gated wire connect"
```

---

## Phase 2 — Pure nodes

> All Phase 2 nodes are pure — no state across ticks. Each task adds: an evaluator function in `StockEvaluators.kt`, a `nodeType(...)` entry in `StockNodeTypes.kt`, and a JUnit test in `AlgoNodeEvaluatorsTest.kt` (one file accumulates all evaluator tests).

### Task 5: `if_then_else` node

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Test: `src/test/kotlin/dev/nitka/nodewire/graph/AlgoNodeEvaluatorsTest.kt`

- [ ] **Evaluator**

Append to `StockEvaluators.kt`:

```kotlin
    /**
     * IfThenElse: cond:BOOL + then:ANY + else:ANY → out:ANY. Selects
     * one of two PinValues unchanged. ANY in/out means no conversion —
     * callers wire compatible types.
     */
    val IfThenElse: NodeEvaluator = { _, inputs ->
        val cond = (inputs["cond"] as? PinValue.Bool)?.value ?: false
        val chosen = if (cond) inputs["then"] else inputs["else_"]
        mapOf("out" to (chosen ?: PinValue.Bool(false)))
    }
```

- [ ] **Register**

Add to `StockNodeTypes.kt` (in the FLOW category section, near `SELECT_BOOL`):

```kotlin
    val IF_THEN_ELSE = nodeType(
        id = "if_then_else",
        displayName = "❓ If Then Else",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("cond", "Cond", PinType.BOOL),
            Pin("then", "Then", PinType.ANY),
            Pin("else_", "Else", PinType.ANY),
        ),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        evaluate = StockEvaluators.IfThenElse,
    )
```

Also register in `registerAll()`.

- [ ] **Test**

Create `AlgoNodeEvaluatorsTest.kt` (or append if it exists):

```kotlin
package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlgoNodeEvaluatorsTest {

    private val empty = CompoundTag()

    @Test fun `if then else picks then on true`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(true),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(1f), out["out"])
    }

    @Test fun `if then else picks else on false`() {
        val out = StockEvaluators.IfThenElse(empty, mapOf(
            "cond" to PinValue.Bool(false),
            "then" to PinValue.Float(1f),
            "else_" to PinValue.Float(2f),
        ))
        assertEquals(PinValue.Float(2f), out["out"])
    }
}
```

- [ ] **Build + test + commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.AlgoNodeEvaluatorsTest"
git add src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt \
        src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/AlgoNodeEvaluatorsTest.kt
git commit -m "feat(node): if_then_else"
```

---

### Task 6: `switch` node + reshape mutator

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockEvaluators.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt`
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt` (add `changeSwitchCases`)
- Modify: `src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt` (Switch config UI)
- Test: `AlgoNodeEvaluatorsTest.kt`

- [ ] **Evaluator**

```kotlin
    /**
     * Switch: index:INT + case_0..N-1:ANY → out:ANY. Out-of-range index
     * yields Bool(false) — downstream auto-conversion turns it into the
     * default of whatever was expected.
     */
    val Switch: NodeEvaluator = { config, inputs ->
        val cases = config.getInt("cases").coerceIn(2, 8)
        val idx = (inputs["index"] as? PinValue.Int)?.value ?: 0
        val key = "case_$idx"
        val out = if (idx in 0 until cases) inputs[key] else null
        mapOf("out" to (out ?: PinValue.Bool(false)))
    }

    /** Build the input pin list for a Switch given its configured case count. */
    fun switchInputs(cases: Int): List<Pin> {
        val n = cases.coerceIn(2, 8)
        val list = mutableListOf(Pin("index", "Index", PinType.INT))
        for (i in 0 until n) list += Pin("case_$i", "Case $i", PinType.ANY)
        return list
    }
```

- [ ] **Register**

```kotlin
    val SWITCH = nodeType(
        id = "switch",
        displayName = "🔀 Switch",
        category = NodeCategory.FLOW,
        inputs = StockEvaluators.switchInputs(4),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        defaultConfig = { CompoundTag().apply { putInt("cases", 4) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SwitchCases,
        evaluate = StockEvaluators.Switch,
    )
```

- [ ] **Reshape mutator**

In `EditorState.kt`, alongside `changeVecOp`:

```kotlin
    /** Switch case-count change — reshape input pins, disconnect old edges. */
    fun changeSwitchCases(
        id: dev.nitka.nodewire.graph.NodeId,
        cases: Int,
    ) {
        val clamped = cases.coerceIn(2, 8)
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val newConfig = n.config.copy().apply { putInt("cases", clamped) }
                n.copy(
                    inputs = StockEvaluators.switchInputs(clamped),
                    config = newConfig,
                )
            }
            _disconnectAllEdgesInternal(id)
        }
    }
```

- [ ] **Config UI**

In `NodeConfigContent.kt`:

```kotlin
    val SwitchCases: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current ?: return@SwitchCases
        val current = node.config.getInt("cases").coerceIn(2, 8)
        Row(verticalAlignment = Alignment.Center) {
            Text("Cases:", style = NwTheme.typography.caption)
            for (n in 2..8) {
                Button(
                    text = n.toString(),
                    enabled = n != current,
                    onClick = { editor.changeSwitchCases(node.id, n) },
                )
            }
        }
    }
```

(Follow the existing `NodeConfigContent` patterns for Row / Button imports.)

- [ ] **Test**

Append to `AlgoNodeEvaluatorsTest.kt`:

```kotlin
    @Test fun `switch picks case by index`() {
        val cfg = CompoundTag().apply { putInt("cases", 3) }
        val inputs = mapOf(
            "index" to PinValue.Int(1),
            "case_0" to PinValue.Float(10f),
            "case_1" to PinValue.Float(20f),
            "case_2" to PinValue.Float(30f),
        )
        assertEquals(PinValue.Float(20f), StockEvaluators.Switch(cfg, inputs)["out"])
    }

    @Test fun `switch out of range yields default`() {
        val cfg = CompoundTag().apply { putInt("cases", 3) }
        val inputs = mapOf(
            "index" to PinValue.Int(99),
            "case_0" to PinValue.Float(10f),
        )
        assertEquals(PinValue.Bool(false), StockEvaluators.Switch(cfg, inputs)["out"])
    }
```

- [ ] **Build + test + commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.AlgoNodeEvaluatorsTest"
git add src/main/kotlin/dev/nitka/nodewire/graph/ \
        src/main/kotlin/dev/nitka/nodewire/client/screen/EditorState.kt \
        src/main/kotlin/dev/nitka/nodewire/client/screen/NodeConfigContent.kt \
        src/test/kotlin/dev/nitka/nodewire/graph/AlgoNodeEvaluatorsTest.kt
git commit -m "feat(node): switch + reshape mutator + config UI"
```

---

### Task 7: `clamp` node

**Files:** evaluator + registry + test.

- [ ] **Evaluator**

```kotlin
    val Clamp: NodeEvaluator = { _, inputs ->
        val v = (inputs["value"] as? PinValue.Float)?.value ?: 0f
        val lo = (inputs["min"] as? PinValue.Float)?.value ?: 0f
        val hi = (inputs["max"] as? PinValue.Float)?.value ?: 1f
        val (realLo, realHi) = if (lo <= hi) lo to hi else hi to lo
        mapOf("out" to PinValue.Float(v.coerceIn(realLo, realHi)))
    }
```

- [ ] **Register**

```kotlin
    val CLAMP = nodeType(
        id = "clamp",
        displayName = "📏 Clamp",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("value", "Value", PinType.FLOAT),
            Pin("min", "Min", PinType.FLOAT),
            Pin("max", "Max", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.Clamp,
    )
```

- [ ] **Test**

```kotlin
    @Test fun `clamp inside passes through`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(5f),
            "min" to PinValue.Float(0f),
            "max" to PinValue.Float(10f),
        ))
        assertEquals(PinValue.Float(5f), out["out"])
    }

    @Test fun `clamp above clips to max`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(99f),
            "min" to PinValue.Float(0f),
            "max" to PinValue.Float(10f),
        ))
        assertEquals(PinValue.Float(10f), out["out"])
    }

    @Test fun `clamp swaps reversed min max`() {
        val out = StockEvaluators.Clamp(empty, mapOf(
            "value" to PinValue.Float(5f),
            "min" to PinValue.Float(10f),
            "max" to PinValue.Float(0f),
        ))
        assertEquals(PinValue.Float(5f), out["out"])
    }
```

- [ ] **Build + test + commit**

```bash
git commit -am "feat(node): clamp"
```

---

### Task 8: `map` (range remap)

**Files:** evaluator + registry + test.

- [ ] **Evaluator**

```kotlin
    val Map: NodeEvaluator = { _, inputs ->
        val v = (inputs["value"] as? PinValue.Float)?.value ?: 0f
        val fromMin = (inputs["from_min"] as? PinValue.Float)?.value ?: 0f
        val fromMax = (inputs["from_max"] as? PinValue.Float)?.value ?: 1f
        val toMin = (inputs["to_min"] as? PinValue.Float)?.value ?: 0f
        val toMax = (inputs["to_max"] as? PinValue.Float)?.value ?: 1f
        val out = if (fromMax == fromMin) toMin
                  else toMin + (v - fromMin) * (toMax - toMin) / (fromMax - fromMin)
        mapOf("out" to PinValue.Float(out))
    }
```

- [ ] **Register**

```kotlin
    val MAP = nodeType(
        id = "map",
        displayName = "↗ Map",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("value", "Value", PinType.FLOAT),
            Pin("from_min", "From Min", PinType.FLOAT),
            Pin("from_max", "From Max", PinType.FLOAT),
            Pin("to_min", "To Min", PinType.FLOAT),
            Pin("to_max", "To Max", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.Map,
    )
```

- [ ] **Tests**

```kotlin
    @Test fun `map 0_to_1 onto 0_to_100`() {
        val out = StockEvaluators.Map(empty, mapOf(
            "value" to PinValue.Float(0.5f),
            "from_min" to PinValue.Float(0f), "from_max" to PinValue.Float(1f),
            "to_min" to PinValue.Float(0f), "to_max" to PinValue.Float(100f),
        ))
        assertEquals(PinValue.Float(50f), out["out"])
    }

    @Test fun `map degenerate range collapses to to_min`() {
        val out = StockEvaluators.Map(empty, mapOf(
            "value" to PinValue.Float(0.5f),
            "from_min" to PinValue.Float(1f), "from_max" to PinValue.Float(1f),
            "to_min" to PinValue.Float(7f), "to_max" to PinValue.Float(99f),
        ))
        assertEquals(PinValue.Float(7f), out["out"])
    }
```

- [ ] **Commit**

```bash
git commit -am "feat(node): map"
```

---

### Task 9: `lerp`

**Files:** evaluator + registry + test.

- [ ] **Evaluator**

```kotlin
    val Lerp: NodeEvaluator = { _, inputs ->
        val a = (inputs["a"] as? PinValue.Float)?.value ?: 0f
        val b = (inputs["b"] as? PinValue.Float)?.value ?: 0f
        val t = ((inputs["t"] as? PinValue.Float)?.value ?: 0f).coerceIn(0f, 1f)
        mapOf("out" to PinValue.Float(a + (b - a) * t))
    }
```

- [ ] **Register**

```kotlin
    val LERP = nodeType(
        id = "lerp",
        displayName = "🌊 Lerp",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("a", "A", PinType.FLOAT),
            Pin("b", "B", PinType.FLOAT),
            Pin("t", "T", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        evaluate = StockEvaluators.Lerp,
    )
```

- [ ] **Tests**

```kotlin
    @Test fun `lerp at zero returns a`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(0f),
        ))
        assertEquals(PinValue.Float(10f), out["out"])
    }

    @Test fun `lerp at one returns b`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(1f),
        ))
        assertEquals(PinValue.Float(20f), out["out"])
    }

    @Test fun `lerp t clamps`() {
        val out = StockEvaluators.Lerp(empty, mapOf(
            "a" to PinValue.Float(10f), "b" to PinValue.Float(20f), "t" to PinValue.Float(99f),
        ))
        assertEquals(PinValue.Float(20f), out["out"])
    }
```

- [ ] **Commit**

```bash
git commit -am "feat(node): lerp"
```

---

## Phase 3 — Stateful nodes

> Stateful nodes use `tickEvaluator: (CompoundTag, NodeConfig, Inputs) → Outputs`. The CompoundTag is per-node, per-BE, owned by `StatefulGraphEvaluator.nodeStates`. Conventions: keep state keys short (`v`, `lt`, `i`, `le`); use raw types when possible.
>
> Each task adds: a `TickEvaluator` in `StockEvaluators.kt`, a `nodeType(..., tickEvaluator = …)` entry in `StockNodeTypes.kt`, and tests that drive multiple ticks via a small helper.

### Task 10: `sample_hold`

- [ ] **Test helper**

In `AlgoNodeEvaluatorsTest.kt` add (once, reuse for all stateful tests):

```kotlin
    /** Drive a tick evaluator across a list of input frames. Returns each tick's outputs. */
    private fun runTicks(
        eval: TickEvaluator,
        config: CompoundTag = empty,
        frames: List<Map<String, PinValue>>,
    ): List<Map<String, PinValue>> {
        val state = CompoundTag()
        return frames.map { eval(state, config, it) }
    }
```

- [ ] **Evaluator**

```kotlin
    /**
     * SampleHold: captures `value` on a rising edge of `trigger`. Holds
     * across subsequent ticks until the next rising edge. PinValue is
     * stored in the state CompoundTag via PinValue.CODEC.
     */
    val SampleHold: TickEvaluator = { state, _, inputs ->
        val rawValue = inputs["value"] ?: PinValue.Bool(false)
        val trig = (inputs["trigger"] as? PinValue.Bool)?.value ?: false
        val wasTrig = state.getBoolean("lt")
        if (trig && !wasTrig) {
            val tag = CompoundTag()
            PinValue.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, rawValue)
                .result().ifPresent { state.put("v", it) }
        }
        state.putBoolean("lt", trig)
        val held = state.get("v")
            ?.let { PinValue.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, it).result().orElse(null) }
            ?: PinValue.Bool(false)
        mapOf("out" to held)
    }
```

- [ ] **Register**

```kotlin
    val SAMPLE_HOLD = nodeType(
        id = "sample_hold",
        displayName = "📷 Sample & Hold",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("value", "Value", PinType.ANY),
            Pin("trigger", "Trigger", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        tickEvaluator = StockEvaluators.SampleHold,
    )
```

- [ ] **Test**

```kotlin
    @Test fun `sample hold captures on rising edge`() {
        val frames = listOf(
            mapOf("value" to PinValue.Float(1f), "trigger" to PinValue.Bool(false)),
            mapOf("value" to PinValue.Float(2f), "trigger" to PinValue.Bool(true)),  // captures 2
            mapOf("value" to PinValue.Float(3f), "trigger" to PinValue.Bool(true)),  // holds 2
            mapOf("value" to PinValue.Float(4f), "trigger" to PinValue.Bool(false)),
            mapOf("value" to PinValue.Float(5f), "trigger" to PinValue.Bool(true)),  // captures 5
        )
        val outs = runTicks(StockEvaluators.SampleHold, frames = frames)
        assertEquals(PinValue.Bool(false), outs[0]["out"])  // nothing held yet
        assertEquals(PinValue.Float(2f), outs[1]["out"])
        assertEquals(PinValue.Float(2f), outs[2]["out"])
        assertEquals(PinValue.Float(2f), outs[3]["out"])
        assertEquals(PinValue.Float(5f), outs[4]["out"])
    }
```

- [ ] **Build + test + commit**

```bash
./gradlew test --tests "dev.nitka.nodewire.graph.AlgoNodeEvaluatorsTest"
git commit -am "feat(node): sample_hold"
```

---

### Task 11: `latch_sr`

- [ ] **Evaluator**

```kotlin
    val LatchSr: TickEvaluator = { state, _, inputs ->
        val set = (inputs["set"] as? PinValue.Bool)?.value ?: false
        val reset = (inputs["reset"] as? PinValue.Bool)?.value ?: false
        var v = state.getBoolean("v")
        when {
            reset -> v = false
            set -> v = true
        }
        state.putBoolean("v", v)
        mapOf("out" to PinValue.Bool(v))
    }
```

- [ ] **Register**

```kotlin
    val LATCH_SR = nodeType(
        id = "latch_sr",
        displayName = "🔒 Latch SR",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("set", "Set", PinType.BOOL),
            Pin("reset", "Reset", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.BOOL)),
        tickEvaluator = StockEvaluators.LatchSr,
    )
```

- [ ] **Test**

```kotlin
    @Test fun `latch sr set then reset`() {
        val frames = listOf(
            mapOf("set" to PinValue.Bool(false), "reset" to PinValue.Bool(false)),
            mapOf("set" to PinValue.Bool(true), "reset" to PinValue.Bool(false)),   // → true
            mapOf("set" to PinValue.Bool(false), "reset" to PinValue.Bool(false)),  // holds true
            mapOf("set" to PinValue.Bool(false), "reset" to PinValue.Bool(true)),   // → false
            mapOf("set" to PinValue.Bool(true), "reset" to PinValue.Bool(true)),    // reset wins → false
        )
        val outs = runTicks(StockEvaluators.LatchSr, frames = frames)
        assertEquals(PinValue.Bool(false), outs[0]["out"])
        assertEquals(PinValue.Bool(true), outs[1]["out"])
        assertEquals(PinValue.Bool(true), outs[2]["out"])
        assertEquals(PinValue.Bool(false), outs[3]["out"])
        assertEquals(PinValue.Bool(false), outs[4]["out"])
    }
```

- [ ] **Commit**

```bash
git commit -am "feat(node): latch_sr"
```

---

### Task 12: `latch_d`

- [ ] **Evaluator**

```kotlin
    val LatchD: TickEvaluator = { state, _, inputs ->
        val data = inputs["data"] ?: PinValue.Bool(false)
        val clock = (inputs["clock"] as? PinValue.Bool)?.value ?: false
        val wasClock = state.getBoolean("lc")
        if (clock && !wasClock) {
            PinValue.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, data)
                .result().ifPresent { state.put("v", it) }
        }
        state.putBoolean("lc", clock)
        val held = state.get("v")
            ?.let { PinValue.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, it).result().orElse(null) }
            ?: PinValue.Bool(false)
        mapOf("out" to held)
    }
```

- [ ] **Register**

```kotlin
    val LATCH_D = nodeType(
        id = "latch_d",
        displayName = "📌 Latch D",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("data", "Data", PinType.ANY),
            Pin("clock", "Clock", PinType.BOOL),
        ),
        outputs = listOf(Pin("out", "Out", PinType.ANY)),
        tickEvaluator = StockEvaluators.LatchD,
    )
```

- [ ] **Test**

```kotlin
    @Test fun `latch d captures on rising clock`() {
        val frames = listOf(
            mapOf("data" to PinValue.Int(7), "clock" to PinValue.Bool(false)),
            mapOf("data" to PinValue.Int(7), "clock" to PinValue.Bool(true)),   // captures 7
            mapOf("data" to PinValue.Int(99), "clock" to PinValue.Bool(true)),  // holds 7
            mapOf("data" to PinValue.Int(99), "clock" to PinValue.Bool(false)),
            mapOf("data" to PinValue.Int(99), "clock" to PinValue.Bool(true)),  // captures 99
        )
        val outs = runTicks(StockEvaluators.LatchD, frames = frames)
        assertEquals(PinValue.Int(7), outs[1]["out"])
        assertEquals(PinValue.Int(7), outs[2]["out"])
        assertEquals(PinValue.Int(99), outs[4]["out"])
    }
```

- [ ] **Commit**

```bash
git commit -am "feat(node): latch_d"
```

---

### Task 13: `sequencer`

- [ ] **Evaluator**

```kotlin
    val Sequencer: TickEvaluator = { state, config, inputs ->
        val steps = config.getInt("steps").coerceIn(2, 16)
        val advance = (inputs["advance"] as? PinValue.Bool)?.value ?: false
        val reset = (inputs["reset"] as? PinValue.Bool)?.value ?: false
        val wasAdvance = state.getBoolean("la")
        var step = state.getInt("s")
        when {
            reset -> step = 0
            advance && !wasAdvance -> step = (step + 1) % steps
        }
        state.putInt("s", step)
        state.putBoolean("la", advance)
        mapOf("step" to PinValue.Int(step))
    }
```

- [ ] **Register**

```kotlin
    val SEQUENCER = nodeType(
        id = "sequencer",
        displayName = "🎼 Sequencer",
        category = NodeCategory.FLOW,
        inputs = listOf(
            Pin("advance", "Advance", PinType.BOOL),
            Pin("reset", "Reset", PinType.BOOL),
        ),
        outputs = listOf(Pin("step", "Step", PinType.INT)),
        defaultConfig = { CompoundTag().apply { putInt("steps", 4) } },
        configContent = dev.nitka.nodewire.client.screen.NodeConfigContent.SequencerSteps,
        tickEvaluator = StockEvaluators.Sequencer,
    )
```

- [ ] **Config UI**

In `NodeConfigContent.kt`:

```kotlin
    val SequencerSteps: @Composable (Node) -> Unit = { node ->
        val editor = LocalEditorState.current ?: return@SequencerSteps
        val current = node.config.getInt("steps").coerceIn(2, 16)
        Row(verticalAlignment = Alignment.Center) {
            Text("Steps:", style = NwTheme.typography.caption)
            IntInput(value = current, range = 2..16) { newSteps ->
                editor.updateNode(node.id) { n ->
                    n.copy(config = n.config.copy().apply { putInt("steps", newSteps) })
                }
            }
        }
    }
```

(If `IntInput` doesn't exist, fall back to a Row of small buttons `2 4 8 16`. Match the surrounding components.)

- [ ] **Test**

```kotlin
    @Test fun `sequencer wraps modulo`() {
        val cfg = CompoundTag().apply { putInt("steps", 3) }
        val advance = mapOf("advance" to PinValue.Bool(true), "reset" to PinValue.Bool(false))
        val none = mapOf("advance" to PinValue.Bool(false), "reset" to PinValue.Bool(false))
        val outs = runTicks(
            StockEvaluators.Sequencer,
            config = cfg,
            frames = listOf(none, advance, none, advance, none, advance, none, advance),
        )
        assertEquals(PinValue.Int(0), outs[0]["step"])
        assertEquals(PinValue.Int(1), outs[1]["step"])
        assertEquals(PinValue.Int(2), outs[3]["step"])
        assertEquals(PinValue.Int(0), outs[5]["step"])  // wrapped
        assertEquals(PinValue.Int(1), outs[7]["step"])
    }
```

- [ ] **Commit**

```bash
git commit -am "feat(node): sequencer"
```

---

### Task 14: `smooth` (low-pass filter)

- [ ] **Evaluator**

```kotlin
    val Smooth: TickEvaluator = { state, _, inputs ->
        val target = (inputs["target"] as? PinValue.Float)?.value ?: 0f
        val factor = ((inputs["factor"] as? PinValue.Float)?.value ?: 0.5f).coerceIn(0f, 1f)
        val initialised = state.getBoolean("init")
        var current = if (initialised) state.getFloat("c") else target
        current = current + (target - current) * factor
        state.putBoolean("init", true)
        state.putFloat("c", current)
        mapOf("out" to PinValue.Float(current))
    }
```

- [ ] **Register**

```kotlin
    val SMOOTH = nodeType(
        id = "smooth",
        displayName = "🌫 Smooth",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("target", "Target", PinType.FLOAT),
            Pin("factor", "Factor", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        tickEvaluator = StockEvaluators.Smooth,
    )
```

- [ ] **Tests**

```kotlin
    @Test fun `smooth converges`() {
        val outs = runTicks(StockEvaluators.Smooth, frames = List(50) {
            mapOf("target" to PinValue.Float(100f), "factor" to PinValue.Float(0.1f))
        })
        val last = (outs.last()["out"] as PinValue.Float).value
        org.junit.jupiter.api.Assertions.assertTrue(last > 99f, "expected near 100, got $last")
    }

    @Test fun `smooth factor 1 is instant`() {
        val outs = runTicks(StockEvaluators.Smooth, frames = listOf(
            mapOf("target" to PinValue.Float(100f), "factor" to PinValue.Float(1f)),
        ))
        assertEquals(PinValue.Float(100f), outs[0]["out"])
    }
```

- [ ] **Commit**

```bash
git commit -am "feat(node): smooth low-pass"
```

---

### Task 15: `pid`

- [ ] **Evaluator**

```kotlin
    val Pid: TickEvaluator = { state, config, inputs ->
        val setpoint = (inputs["setpoint"] as? PinValue.Float)?.value ?: 0f
        val measurement = (inputs["measurement"] as? PinValue.Float)?.value ?: 0f
        val kp = (inputs["kp"] as? PinValue.Float)?.value ?: 1f
        val ki = (inputs["ki"] as? PinValue.Float)?.value ?: 0f
        val kd = (inputs["kd"] as? PinValue.Float)?.value ?: 0f
        val iMin = config.getFloat("i_min").let { if (it == 0f) -1000f else it }
        val iMax = config.getFloat("i_max").let { if (it == 0f) 1000f else it }
        val error = setpoint - measurement
        var integral = state.getFloat("i") + error
        integral = integral.coerceIn(iMin, iMax)
        val lastError = state.getFloat("le")
        val derivative = error - lastError
        state.putFloat("i", integral)
        state.putFloat("le", error)
        val out = kp * error + ki * integral + kd * derivative
        mapOf("out" to PinValue.Float(out))
    }
```

- [ ] **Register**

```kotlin
    val PID = nodeType(
        id = "pid",
        displayName = "🎯 PID",
        category = NodeCategory.MATH,
        inputs = listOf(
            Pin("setpoint", "Setpoint", PinType.FLOAT),
            Pin("measurement", "Measurement", PinType.FLOAT),
            Pin("kp", "Kp", PinType.FLOAT),
            Pin("ki", "Ki", PinType.FLOAT),
            Pin("kd", "Kd", PinType.FLOAT),
        ),
        outputs = listOf(Pin("out", "Out", PinType.FLOAT)),
        defaultConfig = {
            CompoundTag().apply {
                putFloat("i_min", -1000f)
                putFloat("i_max", 1000f)
            }
        },
        tickEvaluator = StockEvaluators.Pid,
    )
```

- [ ] **Test**

```kotlin
    @Test fun `pid p-only emits kp times error`() {
        val outs = runTicks(StockEvaluators.Pid, frames = listOf(
            mapOf(
                "setpoint" to PinValue.Float(10f), "measurement" to PinValue.Float(7f),
                "kp" to PinValue.Float(2f), "ki" to PinValue.Float(0f), "kd" to PinValue.Float(0f),
            ),
        ))
        val v = (outs[0]["out"] as PinValue.Float).value
        org.junit.jupiter.api.Assertions.assertEquals(6f, v, 0.001f)
    }

    @Test fun `pid integral accumulates`() {
        val frame = mapOf(
            "setpoint" to PinValue.Float(10f), "measurement" to PinValue.Float(9f),  // error = 1
            "kp" to PinValue.Float(0f), "ki" to PinValue.Float(1f), "kd" to PinValue.Float(0f),
        )
        val outs = runTicks(StockEvaluators.Pid, frames = List(5) { frame })
        // After 5 ticks integral = 5 → out = 5
        val last = (outs.last()["out"] as PinValue.Float).value
        org.junit.jupiter.api.Assertions.assertEquals(5f, last, 0.001f)
    }
```

- [ ] **Commit**

```bash
git commit -am "feat(node): pid"
```

---

### Task 16: Final wiring + manual verification

**Files:**
- Modify: `src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt` — confirm `registerAll()` includes all 11 new entries (`IF_THEN_ELSE`, `SWITCH`, `SAMPLE_HOLD`, `LATCH_SR`, `LATCH_D`, `SEQUENCER`, `CLAMP`, `MAP`, `LERP`, `SMOOTH`, `PID`).
- Modify: `docs/usage.md` — append the new nodes to the catalogue list under "Node categories".

- [ ] **Verify registry**

```bash
grep -E "IF_THEN_ELSE|SWITCH|SAMPLE_HOLD|LATCH_SR|LATCH_D|SEQUENCER|CLAMP|MAP|LERP|SMOOTH|PID" \
    src/main/kotlin/dev/nitka/nodewire/graph/StockNodeTypes.kt | wc -l
```

Expected: at least 22 lines (each constant referenced in its declaration + its `registerAll()` call).

- [ ] **Full test suite**

Run: `./gradlew test`. Expect all CC + Aero + algorithm tests pass.

- [ ] **Docs update**

In `docs/usage.md`, find the "Node categories" section, append under FLOW:
- `If Then Else`, `Switch`, `Sample & Hold`, `Latch SR`, `Latch D`, `Sequencer`.

Under MATH:
- `Clamp`, `Map`, `Lerp`, `Smooth`, `PID`.

Under the section about types, add:
> **`any` pins** — generic pins shown in gray accept connections from any type. Edges between mismatched scalar types (int↔float, bool↔redstone, etc.) are auto-converted at evaluation time; the wire-drag UI shows an `auto: …` tooltip when a conversion is applied. Vector and quaternion types are not auto-converted from / to scalars.

- [ ] **Manual in-client verification**

Stop and ask the user — do NOT dispatch a subagent for this task. Ask them to `./gradlew runClient`, build a small graph using each new node, save / load, and verify:

1. ANY pin renders gray with label `(any)`.
2. Dragging Int → Float pin shows amber `auto: int → float` tooltip; edge persists; value flows correctly.
3. Dragging Vec3 → Bool shows red ring; edge not created.
4. Switch with 4 cases — change config to 6, two more pins appear, prior wires intact, new pins unconnected.
5. Sample & Hold captures and holds across reload.
6. PID drives a Smooth-input measurement; output stabilises.

- [ ] **Commit + bump version**

If everything works, bump `mod_version` and `modVersion` in `gradle.properties` to `0.3.0`. Commit:

```bash
git commit -am "release: v0.3.0 — algorithm nodes + generic pins"
```

User will tag and push.

---

## Self-review

**Spec coverage:**
- §Type system → Tasks 1–4.
- §New nodes FLOW → Tasks 5 (if_then_else), 6 (switch), 10 (sample_hold), 11 (latch_sr), 12 (latch_d), 13 (sequencer).
- §New nodes MATH → Tasks 7 (clamp), 8 (map), 9 (lerp), 14 (smooth), 15 (pid).
- §UI → Tasks 4 (ANY pin color + drag feedback), 6 (Switch config), 13 (Sequencer config).
- §Versioning → Task 16.
- §Testing — every listed test class is created across Tasks 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15.

**Type consistency:**
- `PinValueConversion.canConvert` / `.convert`: signatures fixed in Task 2, used unchanged in Tasks 3 + 4.
- Stateful state keys (`v`, `lc`, `lt`, `la`, `i`, `le`, `s`, `init`, `c`) chosen per-node; no overlap.
- `TickEvaluator` signature reused unchanged — matches existing pattern in evaluators registry.

**Commit hygiene** (per the user's "fewer commits" preference): each task commits ONCE at the very end (TDD steps are noted as `- [ ]` checkpoints, not separate commits).
