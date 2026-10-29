package com.psdk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// A compiled release (Foret Eternelle's archive) has no Ruby sources: the
// project page offered Debug for it anyway.
class GameModeTest {
    @Test
    fun aCompiledReleaseRunsInReleaseWhateverWasChosen() {
        assertEquals(GameMode.RELEASE, GameMode.forProject(GameMode.DEBUG_UNCOMPILED, compiledRelease = true))
        assertEquals(GameMode.RELEASE, GameMode.forProject(GameMode.RELEASE, compiledRelease = true))
    }

    @Test
    fun anArchiveWithSourcesKeepsTheChosenMode() {
        assertEquals(GameMode.DEBUG_UNCOMPILED, GameMode.forProject(GameMode.DEBUG_UNCOMPILED, compiledRelease = false))
        assertEquals(GameMode.RELEASE, GameMode.forProject(GameMode.RELEASE, compiledRelease = false))
    }
}
