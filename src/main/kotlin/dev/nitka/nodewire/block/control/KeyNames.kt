package dev.nitka.nodewire.block.control

import org.lwjgl.glfw.GLFW

/**
 * English key labels for the Control Block UI. Vanilla's key display names are
 * localized to the game language (so they'd read in Ukrainian etc.); these are
 * derived from GLFW so the bind editor / HUD stay English regardless.
 */
object KeyNames {

    fun label(code: Int): String = when {
        code < 0 -> "—"
        code >= Binding.MOUSE_BUTTON_BASE -> "Mouse ${code - Binding.MOUSE_BUTTON_BASE + 1}"
        SPECIAL.containsKey(code) -> SPECIAL.getValue(code)
        // Printable keys: GLFW gives the layout character (null for the rest).
        else -> GLFW.glfwGetKeyName(code, 0)?.uppercase() ?: "Key $code"
    }

    private val SPECIAL: Map<Int, String> = buildMap {
        put(GLFW.GLFW_KEY_SPACE, "Space")
        put(GLFW.GLFW_KEY_ENTER, "Enter")
        put(GLFW.GLFW_KEY_TAB, "Tab")
        put(GLFW.GLFW_KEY_ESCAPE, "Esc")
        put(GLFW.GLFW_KEY_BACKSPACE, "Bksp")
        put(GLFW.GLFW_KEY_LEFT_SHIFT, "L.Shift")
        put(GLFW.GLFW_KEY_RIGHT_SHIFT, "R.Shift")
        put(GLFW.GLFW_KEY_LEFT_CONTROL, "L.Ctrl")
        put(GLFW.GLFW_KEY_RIGHT_CONTROL, "R.Ctrl")
        put(GLFW.GLFW_KEY_LEFT_ALT, "L.Alt")
        put(GLFW.GLFW_KEY_RIGHT_ALT, "R.Alt")
        put(GLFW.GLFW_KEY_LEFT, "Left")
        put(GLFW.GLFW_KEY_RIGHT, "Right")
        put(GLFW.GLFW_KEY_UP, "Up")
        put(GLFW.GLFW_KEY_DOWN, "Down")
        put(GLFW.GLFW_KEY_CAPS_LOCK, "Caps")
        for (n in 0..9) put(GLFW.GLFW_KEY_0 + n, n.toString())
        for (f in 1..12) put(GLFW.GLFW_KEY_F1 + (f - 1), "F$f")
        for (n in 0..9) put(GLFW.GLFW_KEY_KP_0 + n, "KP$n")
    }
}
