package com.psdk.ui

/**
 * A key press sent to the game the way [GameInputForwarder] sends a physical
 * one: key-down, then key-up followed by the character the key types.
 *
 * Both matter: PSDK's NameInput confirms on the typed character (13 or 10),
 * not on the Enter key, and key states are read per frame, so a key-down and
 * a key-up injected at once would land in the same frame and cancel out. The
 * key-up is sent [RELEASE_DELAY_MS] later, as a physical key would release it.
 */
class KeyTap(
    private val injectKey: (keyCode: Int, down: Boolean) -> Unit,
    private val injectText: (codePoint: Int) -> Unit,
    private val postDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    fun tap(keyCode: Int, codePoint: Int) {
        injectKey(keyCode, true)
        postDelayed(RELEASE_DELAY_MS) {
            injectKey(keyCode, false)
            injectText(codePoint)
        }
    }

    companion object {
        /** A few frames at 60 fps, still well under a human key press. */
        const val RELEASE_DELAY_MS = 100L
    }
}
