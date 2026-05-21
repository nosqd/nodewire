package dev.nitka.nodewire.graph

/**
 * Declarative spec for the inline editor a [Pin] gets when it has no
 * incoming edge. Each variant maps to a small compose composable in
 * the editor screen. When [Pin.editor] is null, [defaultEditorFor]
 * picks a sensible default based on [PinType].
 *
 * `None` is the explicit "render nothing" — used for ANY pins and for
 * any output pin (outputs never have inline editors).
 */
sealed class PinEditor {
    object Numeric : PinEditor()
    object Checkbox : PinEditor()
    object Text : PinEditor()
    data class Enum(val options: List<String>) : PinEditor()
    object Vector : PinEditor()
    data class Slider(val min: Float, val max: Float) : PinEditor()
    object None : PinEditor()
}

/** Editor that should be used when [Pin.editor] is left null. */
fun defaultEditorFor(type: PinType): PinEditor = when (type) {
    PinType.BOOL -> PinEditor.Checkbox
    PinType.INT, PinType.FLOAT, PinType.REDSTONE -> PinEditor.Numeric
    PinType.STRING -> PinEditor.Text
    PinType.VEC2, PinType.VEC3, PinType.QUAT -> PinEditor.Vector
    PinType.ANY -> PinEditor.None
}
