package com.psdk.remote

import org.junit.Assert.assertEquals
import org.junit.Test

// "Downloading Lethalmon v2.24...": the version as the game's splash shows
// it (loader/util.rb's format_version)
class GameSplashTest {
    @Test
    fun aPsdkVersionShowsAsItsBytes() {
        assertEquals("2.24", GameSplash.displayVersion("536"))
        assertEquals("1.0", GameSplash.displayVersion("256"))
        assertEquals("7", GameSplash.displayVersion("7"))
        assertEquals("0", GameSplash.displayVersion("0"))
    }

    @Test
    fun anyOtherVersionShowsAsIs() {
        assertEquals("1.6.13", GameSplash.displayVersion("1.6.13"))
        assertEquals("beta", GameSplash.displayVersion("beta"))
    }
}
