package dev.nitka.nodewire.script.lexer

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType

/** Which body builder the script uses — drives the evaluator-slot fork (§4). */
enum class BodyKind { TICK, EVAL, NONE }

data class HeaderResult(
    val inputs: List<Pin>,
    val outputs: List<Pin>,
    val body: BodyKind,
    val warnings: List<String>,
)

/**
 * A **non-compiling**, single-pass recognizer for the script *header* — the
 * top-level `val/var x = input<Type>("name")` / `output<Type>("name")`
 * declarations. It exists so the client and the `Node.CODEC` decode path get
 * the same `(inputs, outputs)` the compiled script would, **without** dragging
 * in the Kotlin compiler.
 *
 * Runs on every decode and every client render frame, so it is allocation-cheap
 * and **never throws** — malformed declarations are skipped with a warning.
 *
 * Recognition is gated to brace-depth 0 / paren-depth 0, outside
 * strings/chars/comments. That is what keeps `input(...)` typed *inside* a
 * `tick { }` lambda (or anywhere past the first body brace) from being mistaken
 * for a pin.
 *
 * The `ALLOWED` type set MUST stay in lockstep with
 * [dev.nitka.nodewire.script.scriptPinType] so the client never shows a pin the
 * server would refuse.
 */
object HeaderLexer {

    private val ALLOWED: Map<String, PinType> = mapOf(
        "Boolean" to PinType.BOOL,
        "Int" to PinType.INT,
        "Float" to PinType.FLOAT,
        "Redstone" to PinType.REDSTONE,
        "String" to PinType.STRING,
        "Vec2" to PinType.VEC2,
        "Vec3" to PinType.VEC3,
        "Quat" to PinType.QUAT,
        "Video" to PinType.VIDEO,
    )

    fun parse(src: String): HeaderResult {
        val inputs = LinkedHashMap<String, Pin>()
        val outputs = LinkedHashMap<String, Pin>()
        val warnings = ArrayList<String>()
        var body = BodyKind.NONE

        var i = 0
        val n = src.length
        var braceDepth = 0
        var parenDepth = 0
        var blockCommentDepth = 0

        fun isIdentStart(c: Char) = c.isLetter() || c == '_'
        fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

        // word boundary before position p (start of source counts as a boundary).
        fun boundaryBefore(p: Int): Boolean = p == 0 || !isIdentPart(src[p - 1])

        while (i < n) {
            val c = src[i]

            // --- comments ---
            if (blockCommentDepth > 0) {
                if (c == '/' && i + 1 < n && src[i + 1] == '*') { blockCommentDepth++; i += 2; continue }
                if (c == '*' && i + 1 < n && src[i + 1] == '/') { blockCommentDepth--; i += 2; continue }
                i++; continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                i += 2
                while (i < n && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '*') { blockCommentDepth++; i += 2; continue }

            // --- string / char literals (skip wholesale) ---
            if (c == '"') {
                // raw string?
                if (i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                    i += 3
                    while (i + 2 < n && !(src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"')) i++
                    i = (i + 3).coerceAtMost(n)
                } else {
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\') i++ // skip escaped char
                        i++
                    }
                    i++ // closing quote
                }
                continue
            }
            if (c == '\'') {
                i++
                while (i < n && src[i] != '\'') {
                    if (src[i] == '\\') i++
                    i++
                }
                i++
                continue
            }

            // --- brace / paren depth tracking ---
            if (c == '{') { braceDepth++; i++; continue }
            if (c == '}') { if (braceDepth > 0) braceDepth--; i++; continue }
            if (c == '(') { parenDepth++; i++; continue }
            if (c == ')') { if (parenDepth > 0) parenDepth--; i++; continue }

            // Only recognize keywords at top level (outside any block + any parens).
            val atTop = braceDepth == 0 && parenDepth == 0

            if (atTop && isIdentStart(c) && boundaryBefore(i)) {
                // read identifier
                var j = i + 1
                while (j < n && isIdentPart(src[j])) j++
                val word = src.substring(i, j)

                when (word) {
                    "input", "output" -> {
                        val decl = tryParseDecl(src, j, n)
                        if (decl == null) {
                            // not actually a pin decl shaped `<Type>("name")` — treat
                            // as a plain identifier and move on (e.g. `input.value`).
                            i = j
                            continue
                        }
                        i = decl.endIndex
                        when (decl.outcome) {
                            DeclOutcome.OK -> {
                                val type = ALLOWED.getValue(decl.typeName)
                                val pin = Pin(id = decl.pinName, name = decl.pinName, type = type)
                                val map = if (word == "input") inputs else outputs
                                if (map.containsKey(decl.pinName)) {
                                    warnings += "duplicate ${word} pin '${decl.pinName}' — last declaration wins"
                                }
                                map[decl.pinName] = pin
                            }
                            DeclOutcome.BAD_TYPE ->
                                warnings += "$word pin: unsupported type '${decl.typeName}' (skipped)"
                            DeclOutcome.NON_LITERAL_NAME ->
                                warnings += "$word pin: name must be a string literal (skipped)"
                        }
                        continue
                    }
                    "tick", "eval" -> {
                        // body builder iff followed (after ws) by '{'
                        var k = j
                        while (k < n && src[k].isWhitespace()) k++
                        if (k < n && src[k] == '{') {
                            if (body == BodyKind.NONE) {
                                body = if (word == "tick") BodyKind.TICK else BodyKind.EVAL
                            }
                        }
                        i = j
                        continue
                    }
                    else -> { i = j; continue }
                }
            }

            i++
        }

        return HeaderResult(inputs.values.toList(), outputs.values.toList(), body, warnings)
    }

    private enum class DeclOutcome { OK, BAD_TYPE, NON_LITERAL_NAME }

    private class Decl(
        val outcome: DeclOutcome,
        val typeName: String,
        val pinName: String,
        val endIndex: Int,
    )

    /**
     * Starting just after the `input`/`output` keyword, try to match
     * `'<' TYPE '>' '(' STRINGLIT`. Returns null if the shape isn't a pin decl
     * at all (so the caller treats the word as a plain identifier).
     */
    private fun tryParseDecl(src: String, after: Int, n: Int): Decl? {
        var p = after
        fun skipWs() { while (p < n && src[p].isWhitespace()) p++ }

        skipWs()
        if (p >= n || src[p] != '<') return null
        p++
        skipWs()

        // bare identifier type (no FQN/generics/nullability)
        val typeStart = p
        if (p >= n || !(src[p].isLetter() || src[p] == '_')) return null
        p++
        while (p < n && (src[p].isLetterOrDigit() || src[p] == '_')) p++
        val typeName = src.substring(typeStart, p)
        skipWs()

        // anything other than a clean '>' here (e.g. '.', '?', '<' for nested
        // generics) means not a simple allowed type — consume to the closing
        // '>' then mark BAD_TYPE so the editor warns.
        var nullable = false
        if (p < n && src[p] == '?') { nullable = true; p++; skipWs() }
        if (p >= n || src[p] != '>') return null
        p++
        skipWs()
        if (p >= n || src[p] != '(') return null
        p++
        skipWs()

        if (p >= n || src[p] != '"') {
            // non-literal first arg (variable name etc.)
            return Decl(DeclOutcome.NON_LITERAL_NAME, typeName, "", p)
        }
        // read a simple single-line, no-escape literal
        p++
        val nameStart = p
        while (p < n && src[p] != '"' && src[p] != '\n') p++
        if (p >= n || src[p] != '"') {
            return Decl(DeclOutcome.NON_LITERAL_NAME, typeName, "", p)
        }
        val pinName = src.substring(nameStart, p)
        p++ // closing quote

        val outcome = when {
            nullable || typeName !in ALLOWED -> DeclOutcome.BAD_TYPE
            else -> DeclOutcome.OK
        }
        return Decl(outcome, typeName, pinName, p)
    }
}
