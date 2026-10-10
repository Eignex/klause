package com.eignex.klause.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertTrue

class NoiseControllerTest {
    @Test
    fun `noise controller bumps level on stall and decays on improvement`() {
        val controller = NoiseController(initial = 0.2, theta = 3, phi = 0.2)
        controller.observe(10)
        controller.observe(10)
        controller.observe(10)
        controller.observe(10)
        assertTrue(controller.level > 0.2, "expected bump after 3 stalls, got ${controller.level}")
        val afterBump = controller.level
        controller.observe(9)
        assertTrue(
            controller.level < afterBump,
            "expected decay after improvement, got ${controller.level} vs $afterBump",
        )
    }

    @Test
    fun `noise controller respects bounds`() {
        val controller = NoiseController(initial = 0.5, theta = 1, phi = 0.5, minLevel = 0.5, maxLevel = 0.9)
        repeat(20) { controller.observe(100) }
        assertTrue(controller.level <= 0.9, "level escaped maxLevel: ${controller.level}")
        val before = controller.level
        for (cost in 99 downTo 80) controller.observe(cost.toLong())
        assertTrue(
            controller.level < before,
            "level did not decay on strict improvements: $before -> ${controller.level}",
        )
        assertTrue(controller.level >= 0.5, "level escaped minLevel: ${controller.level}")
    }

    @Test
    fun `noise controller in ewma mode tracks smoothed cost trend`() {
        val controller = NoiseController(initial = 0.2, theta = 5, phi = 0.2, ewmaAlpha = 0.2)
        for (cost in 20 downTo 5) controller.observe(cost.toLong())
        assertTrue(
            controller.level <= 0.3,
            "level should stay near baseline under steady improvement; got ${controller.level}",
        )
    }
}
