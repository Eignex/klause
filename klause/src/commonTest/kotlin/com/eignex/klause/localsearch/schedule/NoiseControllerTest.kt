package com.eignex.klause.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun costRound(cost: Long) = RoundLog(
    proposed = 1,
    accepted = 0,
    costMean = 0.0,
    costVariance = 0.0,
    bestCost = cost.toDouble(),
    temperature = 1.0,
    incumbentCost = cost.toDouble(),
)

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
    fun `noise controller on the round channel matches the per-step path`() {
        val perStep = NoiseController(initial = 0.2, theta = 3, phi = 0.2)
        val perRound = NoiseController(initial = 0.2, theta = 3, phi = 0.2)
        val costs = longArrayOf(10, 10, 10, 10, 9, 9, 8, 12, 12, 12, 12, 7)
        for (c in costs) {
            perStep.observe(c)
            perRound.observe(costRound(c))
        }
        assertEquals(perStep.level, perRound.level, 1e-12)
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

    @Test
    fun `noise controller in ewma mode bumps level when cost rises above smoothed`() {
        val controller = NoiseController(initial = 0.1, theta = 3, phi = 0.3, ewmaAlpha = 0.5)
        repeat(5) { controller.observe(10) }
        val baseline = controller.level
        repeat(10) { controller.observe(20) }
        assertTrue(
            controller.level > baseline,
            "level should grow on sustained rise above smoothed avg; got $baseline -> ${controller.level}",
        )
    }

    @Test
    fun `auto ewma alpha scales window with problem size and flip budget`() {
        assertEquals(0.2, NoiseController.autoEwmaAlpha(numVars = 4, flipBudget = 100_000), 1e-9)
        assertEquals(0.1, NoiseController.autoEwmaAlpha(numVars = 100, flipBudget = 100_000), 1e-9)
        assertEquals(0.02, NoiseController.autoEwmaAlpha(numVars = 10_000, flipBudget = 1_000_000), 1e-9)
        assertEquals(0.2, NoiseController.autoEwmaAlpha(numVars = 400, flipBudget = 100), 1e-9)
        val alpha = NoiseController.autoEwmaAlpha(numVars = 1_000_000, flipBudget = 1)
        assertTrue(alpha in 0.02..0.5, "alpha out of clip range: $alpha")
    }
}
