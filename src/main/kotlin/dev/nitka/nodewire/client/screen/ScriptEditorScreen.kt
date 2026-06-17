package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.net.SetScriptSourcePacket
import dev.nitka.nodewire.script.ScriptDiagnostics
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.Dialog
import dev.nitka.nodewire.ui.components.DialogContent
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextArea
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.height
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import dev.nitka.nodewire.script.lexer.ScriptHighlighter
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Full-screen source editor for a `script` node. Opened from the script
 * node's `configContent` "📜 Edit" button. Holds local edit state; the
 * authoritative source stays in the server-side node config.
 *
 * Layout: a title bar (node display name), a large highlighted [TextArea]
 * bound to local source state, a bottom status strip fed by the node's
 * synced diagnostics ([ScriptDiagnostics]), and a Close button.
 *
 * Apply-on-close: both ESC and the Close button trigger [closeAndApply].
 * If the text changed since open, a [SetScriptSourcePacket] commits it to the
 * server (which re-reshapes pins + prunes edges); then we return to the node
 * editor for [pos]. No explicit Apply button — per the design spec.
 */
class ScriptEditorScreen(
    private val pos: BlockPos,
    private val nodeId: NodeId,
    private val initialSrc: String,
    private val nodeName: String,
) : NwComposeScreen(Component.literal("Script — $nodeName")) {

    private var src: String = initialSrc

    /**
     * Read the live (re-synced) diagnostics token + text from the client BE's
     * copy of this node's config. The server stamps these after (re)compile;
     * the client BE graph is kept in sync, so polling per frame shows the
     * last known status without any client compile.
     */
    private fun diagnostics(): Pair<String, String> {
        val level = Minecraft.getInstance().level ?: return ScriptDiagnostics.STATUS_EMPTY to ""
        val be = level.getBlockEntity(pos) as? LogicBlockEntity
            ?: return ScriptDiagnostics.STATUS_EMPTY to ""
        val node = be.graph.nodes[nodeId] ?: return ScriptDiagnostics.STATUS_EMPTY to ""
        return ScriptDiagnostics.readStatus(node.config) to ScriptDiagnostics.readText(node.config)
    }

    private companion object {
        /** Sanity cap for a loaded script file — scripts are a few KiB. */
        const val MAX_SCRIPT_FILE_BYTES = 512L * 1024

        /** Fixed size of the read-only error modal (Popup clamps it on-screen). */
        const val ERROR_DIALOG_W = 380
        const val ERROR_DIALOG_H = 220
    }

    /** One native picker at a time — a second click while one is open is a no-op. */
    private val pickerBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Native OS file picker (LWJGL tinyfd — ships with vanilla MC) on a worker
     * thread; zenity/kdialog block their caller, so the render thread must NOT
     * host the dialog. The chosen file is read off-thread too; only the final
     * state write hops back to the client thread via [Minecraft.execute].
     * Starts in `<gameDir>/nodewire-scripts/` (created on first use).
     */
    private fun browseAndLoad(onLoaded: (String) -> Unit, onError: (String) -> Unit) {
        if (!pickerBusy.compareAndSet(false, true)) return
        val mc = Minecraft.getInstance()
        val dir = java.io.File(mc.gameDirectory, "nodewire-scripts").apply { mkdirs() }
        Thread({
            try {
                val path = org.lwjgl.system.MemoryStack.stackPush().use { stack ->
                    val patterns = stack.mallocPointer(2)
                    patterns.put(stack.UTF8("*.kts"))
                    patterns.put(stack.UTF8("*.txt"))
                    patterns.flip()
                    org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                        "Open Nodewire script",
                        dir.absolutePath + java.io.File.separator,
                        patterns,
                        "Nodewire scripts (*.kts, *.txt)",
                        false,
                    )
                } ?: return@Thread // cancelled
                val file = java.io.File(path)
                val result = runCatching {
                    require(file.length() <= MAX_SCRIPT_FILE_BYTES) {
                        "file is larger than ${MAX_SCRIPT_FILE_BYTES / 1024} KiB"
                    }
                    file.readText()
                }
                mc.execute {
                    result.fold(onLoaded) { e -> onError(e.message ?: e.toString()) }
                }
            } finally {
                pickerBusy.set(false)
            }
        }, "nw-script-open").apply { isDaemon = true }.start()
    }

    /**
     * Native "save as" dialog (tinyfd), mirrored on [browseAndLoad]: worker
     * thread for the blocking dialog + the disk write, client thread for the
     * result callback. Default name = sanitized node name + `.nw.kts`; a
     * picked name without an extension gets `.nw.kts` appended.
     */
    private fun browseAndSave(content: String, onSaved: (String) -> Unit, onError: (String) -> Unit) {
        if (!pickerBusy.compareAndSet(false, true)) return
        val mc = Minecraft.getInstance()
        val dir = java.io.File(mc.gameDirectory, "nodewire-scripts").apply { mkdirs() }
        val defaultName = nodeName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "script" } + ".nw.kts"
        Thread({
            try {
                val path = org.lwjgl.system.MemoryStack.stackPush().use { stack ->
                    val patterns = stack.mallocPointer(2)
                    patterns.put(stack.UTF8("*.kts"))
                    patterns.put(stack.UTF8("*.txt"))
                    patterns.flip()
                    org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog(
                        "Save Nodewire script",
                        dir.absolutePath + java.io.File.separator + defaultName,
                        patterns,
                        "Nodewire scripts (*.kts, *.txt)",
                    )
                } ?: return@Thread // cancelled
                val file = java.io.File(if (path.substringAfterLast('/').contains('.')) path else "$path.nw.kts")
                val result = runCatching { file.writeText(content); file.name }
                mc.execute {
                    result.fold(onSaved) { e -> onError(e.message ?: e.toString()) }
                }
            } finally {
                pickerBusy.set(false)
            }
        }, "nw-script-save").apply { isDaemon = true }.start()
    }

    /** Commit edits (if changed) + return to the node editor. */
    private fun closeAndApply() {
        val mc = Minecraft.getInstance()
        val be = mc.level?.getBlockEntity(pos) as? LogicBlockEntity
        if (src != initialSrc) {
            PacketDistributor.sendToServer(SetScriptSourcePacket(pos, nodeId, src))
            // Mirror the edit into the CLIENT graph immediately. The server
            // round-trip hasn't landed yet, and the node editor we re-open saves
            // the WHOLE graph on close (NodeEditorScreen.removed → SaveGraphPacket).
            // Without this the stale client graph would clobber the just-sent src
            // (this also reshapes pins + prunes edges client-side to match).
            be?.let { LogicBlockEntity.applyScriptSourceToGraph(it.graph, nodeId, src) }
        }
        mc.setScreen(if (be != null) NodeEditorScreen(pos, be.graph) else null)
    }

    /**
     * EVERY close path must commit. Vanilla `Screen.keyPressed` handles ESC
     * itself (shouldCloseOnEsc → onClose → setScreen(null)), so a manual ESC
     * branch after `super.keyPressed` is dead code — the screen used to close
     * through vanilla onClose and silently DROP the edit ("закрив редактор —
     * а там минулий скрипт", 2026-06-12). Routing onClose into closeAndApply
     * covers ESC and any other vanilla-initiated close. A TextArea-focused
     * ESC is still consumed by the TextArea first (blur), so the first ESC
     * blurs, the second commits and closes.
     */
    override fun onClose() {
        closeAndApply()
    }

    @Composable
    override fun Content() {
        NwThemeProvider {
            var text by remember { mutableStateOf(src) }
            // Transient file-IO feedback: (message, isError) or null.
            var ioMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
            // Read-only full-error overlay toggle.
            var showErrors by remember { mutableStateOf(false) }
            val (statusToken, statusText) = diagnostics()
            Box(modifier = Modifier.fillMaxSize().padding(NwTheme.dimens.space8)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    style = SurfaceStyle(
                        color = NwTheme.colors.surface,
                        shape = NwTheme.shapes.medium,
                        border = BorderStroke(1, NwTheme.colors.border),
                        padding = PaddingValues(NwTheme.dimens.space8),
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
                    ) {
                        // Title bar — node display name.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Center,
                            horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                        ) {
                            Text(nodeName, style = NwTheme.typography.subtitle)
                            ioMsg?.let { (msg, isError) ->
                                Text(
                                    if (isError) "⚠ $msg" else msg,
                                    style = NwTheme.typography.caption.copy(
                                        color = if (isError) NwTheme.colors.pinRedstone else NwTheme.colors.onSurfaceMuted,
                                    ),
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {}
                            // Full compile error → read-only viewer. Only when
                            // the last server compile actually failed.
                            if (statusToken == ScriptDiagnostics.STATUS_ERROR) {
                                Button(
                                    onClick = { showErrors = true },
                                    style = ButtonDefaults.ghost(),
                                ) { Text("⚠ Errors") }
                            }
                            Button(
                                onClick = {
                                    browseAndLoad(
                                        onLoaded = { c -> text = c; src = c; ioMsg = null },
                                        onError = { msg -> ioMsg = msg to true },
                                    )
                                },
                                style = ButtonDefaults.ghost(),
                            ) { Text("Open…") }
                            Button(
                                onClick = {
                                    browseAndSave(
                                        content = text,
                                        onSaved = { name -> ioMsg = "✔ $name" to false },
                                        onError = { msg -> ioMsg = msg to true },
                                    )
                                },
                                style = ButtonDefaults.ghost(),
                            ) { Text("Save…") }
                            Button(onClick = { closeAndApply() }) { Text("Close") }
                        }

                        // Source editor — fills the remaining height.
                        TextArea(
                            value = text,
                            onValueChange = { new -> text = new; src = new },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            highlight = { line -> ScriptHighlighter.highlight(line) },
                            lineNumbers = true,
                        )

                        // Status strip — last known server diagnostics.
                        StatusStrip(statusToken, statusText)
                    }
                }

                // Read-only error overlay — stacks on top of the editor Surface.
                if (showErrors) {
                    ErrorOverlay(statusText) { showErrors = false }
                }
            }
        }
    }

    /**
     * Modal, read-only view of the full compile diagnostics — a centered,
     * scrim-backed [Dialog] (the framework's modal primitive: it dims the
     * editor behind and dismisses on outside-click / its own Close button).
     * The text is the server-stamped [ScriptDiagnostics] payload (already
     * noise-filtered by the scripting host), shown in a scrollable, selectable
     * read-only [TextArea] so long multi-line errors can be read and copied
     * without touching the source.
     */
    @Composable
    private fun ErrorOverlay(text: String, onClose: () -> Unit) {
        Dialog(onDismissRequest = onClose) {
            DialogContent(modifier = Modifier.width(ERROR_DIALOG_W).height(ERROR_DIALOG_H)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Center,
                        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4),
                    ) {
                        Text(
                            "Compile errors",
                            style = NwTheme.typography.subtitle.copy(color = NwTheme.colors.pinRedstone),
                        )
                        Box(modifier = Modifier.weight(1f)) {}
                        Button(onClick = onClose, style = ButtonDefaults.ghost()) { Text("Close") }
                    }
                    TextArea(
                        value = text.ifEmpty { "(no diagnostics)" },
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        readOnly = true,
                        lineNumbers = false,
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusStrip(token: String, text: String) {
        val (label, color) = when (token) {
            ScriptDiagnostics.STATUS_COMPILING ->
                "Compiling…" to NwTheme.colors.onSurfaceMuted
            ScriptDiagnostics.STATUS_OK ->
                "✅ Compiled" to NwTheme.colors.onSurfaceMuted
            ScriptDiagnostics.STATUS_ERROR -> {
                // Keep the strip to one line — the "⚠ Errors" button reveals
                // the full multi-line text in the read-only overlay.
                val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Compile error"
                val more = (text.count { it == '\n' }).let { if (it > 0) "  (+$it more)" else "" }
                "❌ $first$more" to NwTheme.colors.pinRedstone
            }
            else -> "" to NwTheme.colors.onSurfaceMuted
        }
        if (label.isEmpty()) return
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = NwTheme.typography.caption.copy(color = color))
        }
    }
}
