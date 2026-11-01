package com.psdk.controls

/**
 * The keys a player's button can send: Sf::Keyboard::Scancode names (the
 * runtime's LiteRGSS binding) and how the screen shows them. The keyboard
 * keys games bind; the rest of SFML's scancodes are left out.
 */
object Keys {
    val ALL: List<Pair<String, String>> =
        ('A'..'Z').map { "$it" to "$it" } +
            (0..9).map { "Num$it" to "$it" } +
            (1..12).map { "F$it" to "F$it" } +
            listOf(
                "Space" to "Space", "Enter" to "Enter", "Escape" to "Escape", "Backspace" to "Backspace",
                "Tab" to "Tab", "LShift" to "Left Shift", "RShift" to "Right Shift",
                "LControl" to "Left Ctrl", "RControl" to "Right Ctrl", "LAlt" to "Left Alt", "RAlt" to "Right Alt",
                "Up" to "Up", "Down" to "Down", "Left" to "Left", "Right" to "Right",
                "Insert" to "Insert", "Delete" to "Delete", "Home" to "Home", "End" to "End",
                "PageUp" to "Page Up", "PageDown" to "Page Down",
            )

    private val NAMES = ALL.toMap()

    /** How the screen shows [key]: its scancode name when not in [ALL]. */
    fun label(key: String) = NAMES[key] ?: key
}
