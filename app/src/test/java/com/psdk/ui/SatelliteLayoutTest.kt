package com.psdk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The project hub placed its satellites 200 dp above the screen's centre:
// in landscape (a 372 dp high window on a Redmi Note 13 Pro 5G) they were
// above the top of the screen.
class SatelliteLayoutTest {
    private val slotHeight = 112f
    private val margin = 8f

    private fun offset(viewHeight: Float) = SatelliteLayout.verticalOffset(
        viewHeight = viewHeight,
        slotHeight = slotHeight,
        preferredOffset = 200f,
        margin = margin,
        minOffset = 90f,
    )

    @Test
    fun aTallViewKeepsThePreferredOffset() {
        assertEquals(200f, offset(800f))
    }

    @Test
    fun aLandscapeViewKeepsTheSatellitesOnScreen() {
        val viewHeight = 372f
        val satelliteTop = viewHeight / 2f - offset(viewHeight) - slotHeight / 2f
        assertTrue("satellite top at $satelliteTop", satelliteTop >= margin)
    }

    @Test
    fun aVeryShortViewNeverCoversTheFab() {
        assertEquals(90f, offset(200f))
    }
}
