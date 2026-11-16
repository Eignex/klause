package com.eignex.klause.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressWindowTest {
    @Test
    fun `repeated observations at one step should not record cost improvements`() {
        val progress = ProgressWindow()
        progress.observe(0L, 10L)
        progress.observe(2L, 9L)

        progress.observe(2L, 1L)
        progress.observe(3L, 8L)

        assertEquals(0L, progress.elapsed(3L))
    }

    @Test
    fun `rewinding should replace the previous epoch best cost`() {
        val progress = ProgressWindow()
        progress.observe(10L, 1L)

        progress.observe(0L, 10L)
        progress.observe(1L, 9L)

        assertEquals(0L, progress.elapsed(1L))
    }

    @Test
    fun `fraction progress should only reset windows that track it`() {
        for (trackFraction in listOf(false, true)) {
            val progress = ProgressWindow(trackFraction = trackFraction)
            progress.observe(0L, 23L, 0.9)

            progress.observe(1L, 23L, 0.8)

            assertEquals(if (trackFraction) 0L else 1L, progress.elapsed(1L))
        }
    }

    @Test
    fun `restarting the window should retain the best observed cost`() {
        val progress = ProgressWindow()
        progress.observe(0L, 10L)
        progress.observe(3L, 12L)
        progress.restartAt(3L)

        progress.observe(4L, 11L)

        assertEquals(1L, progress.elapsed(4L))
    }

    @Test
    fun `reset should restore the configured initial progress timestamp`() {
        for (initialStep in listOf(-1L, 0L)) {
            val progress = ProgressWindow(initialProgressStep = initialStep)
            progress.observe(10L, 1L)

            progress.reset()
            progress.observe(0L, Long.MAX_VALUE)

            assertEquals(-initialStep, progress.elapsed(0L))
        }
    }
}
