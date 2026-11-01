package com.psdk.controls

/**
 * The keys a player's button can send: Sf::Keyboard::Scancode names (the
 * runtime's LiteRGSS binding) and how the screen shows them. The keyboard
 * keys games bind; the rest of SFML's scancodes are left out.
 */
object Keys {
    /** The keys by kind, as the picker shows them. */
    val GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Common" to listOf(
            "Enter" to "Enter", "Escape" to "Escape", "Space" to "Space", "Backspace" to "Backspace", "Tab" to "Tab",
            "LShift" to "Left Shift", "RShift" to "Right Shift", "LControl" to "Left Ctrl", "RControl" to "Right Ctrl",
            "LAlt" to "Left Alt", "RAlt" to "Right Alt",
        ),
        "Arrows" to listOf("Up" to "Up", "Down" to "Down", "Left" to "Left", "Right" to "Right"),
        "Letters" to ('A'..'Z').map { "$it" to "$it" },
        "Digits" to (0..9).map { "Num$it" to "$it" },
        "Function keys" to (1..12).map { "F$it" to "F$it" },
        "Others" to listOf(
            "Insert" to "Insert", "Delete" to "Delete", "Home" to "Home", "End" to "End",
            "PageUp" to "Page Up", "PageDown" to "Page Down",
        ),
    )

    val ALL: List<Pair<String, String>> = GROUPS.flatMap { it.second }

    private val NAMES = ALL.toMap()

    /** How the screen shows [key]: its scancode name when not in [ALL]. */
    fun label(key: String) = NAMES[key] ?: key
}
