package com.psdk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The virtual keyboard's ✓ (an editor action) is sent to the game as a
// physical Enter would be. It used to be the Enter key only, down and up at
// once: PSDK's NameInput confirms on the typed newline, so Foret Eternelle's
// name screen could not be validated.
class KeyTapTest {
    private val events = mutableListOf<String>()
    private val posted = mutableListOf<Pair<Long, () -> Unit>>()
    private val keyTap = KeyTap(
        injectKey = { keyCode, down -> events += if (down) "down $keyCode" else "up $keyCode" },
        injectText = { codePoint -> events += "text $codePoint" },
        postDelayed = { delayMs, action -> posted += delayMs to action },
    )

    @Test
    fun theKeyIsReleasedFramesAfterItIsPressed() {
        keyTap.tap(66, 10)

        assertEquals(listOf("down 66"), events)
        val (delayMs, release) = posted.single()
        // Two frames at 60 fps at least, so that a frame sees the key down
        assertTrue("released after $delayMs ms", delayMs >= 34)
        release()
        assertEquals("up 66", events[1])
    }

    @Test
    fun theReleaseTypesTheKeysCharacter() {
        keyTap.tap(66, 10)
        posted.single().second()

        assertEquals(listOf("down 66", "up 66", "text 10"), events)
    }
}
