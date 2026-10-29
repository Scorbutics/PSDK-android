package com.psdk.ui

/**
 * Vertical placement of the project hub's satellites, which sit on a row
 * above the centred "+" FAB. The row is [preferredOffset] above the FAB's
 * centre when the view is tall enough; on a short view (landscape: about
 * 370 dp) it comes closer, so that a satellite never leaves the view, but
 * not so close that it covers the FAB.
 */
object SatelliteLayout {
    /**
     * Distance from the FAB's centre (the view's centre) to a satellite's
     * centre, in px.
     *
     * @param viewHeight height of the view the satellites are laid out in
     * @param slotHeight height of a satellite (icon and name caption)
     * @param preferredOffset distance used when the view is tall enough
     * @param margin space kept between a satellite and the view's top edge
     * @param minOffset closest a satellite may come to the FAB's centre
     */
    fun verticalOffset(
        viewHeight: Float,
        slotHeight: Float,
        preferredOffset: Float,
        margin: Float,
        minOffset: Float,
    ): Float {
        val fitting = viewHeight / 2f - slotHeight / 2f - margin
        return fitting.coerceAtMost(preferredOffset).coerceAtLeast(minOffset)
    }
}
