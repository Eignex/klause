package com.eignex.klause.bench.metric

import com.eignex.klause.bench.metric.ArmCalibration.ArmRun
import com.eignex.klause.bench.metric.ArmCalibration.Instance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArmCalibrationTest {

    private fun arm(name: String, objective: Double?, proven: Boolean = false, ms: Long = 100): ArmRun = ArmRun(
        name,
        feasible = objective != null,
        finalObjective = objective,
        proven = proven,
        timeToBestMs = if (objective != null) ms else null,
    )

    @Test
    fun `a proved optimum beats an equal unproved objective`() {
        val p = Instance(
            "p",
            maximize = false,
            runs = listOf(arm("A", 5.0, proven = true), arm("B", 5.0), arm("C", 9.0)),
        )
        val report = ArmCalibration.score(listOf(p))
        assertEquals(1, report.scores.first { it.arm == "A" }.wins, "the proved optimum wins outright")
        assertEquals(0, report.scores.first { it.arm == "B" }.wins, "the equal but unproved objective loses")
    }

    @Test
    fun `an equal objective is broken by speed`() {
        val p = Instance(
            "p",
            maximize = false,
            runs = listOf(arm("fast", 5.0, ms = 100), arm("slow", 5.0, ms = 500), arm("bad", 9.0)),
        )
        val report = ArmCalibration.score(listOf(p))
        assertEquals(1, report.scores.first { it.arm == "fast" }.wins, "faster wins the equal-objective tie")
        assertEquals(0, report.scores.first { it.arm == "slow" }.wins)
    }

    @Test
    fun `a problem every arm ties on is dropped as non-discriminating`() {
        val tied = Instance("p", maximize = false, runs = listOf(arm("A", 4.0), arm("B", 4.0), arm("C", 4.0)))
        val report = ArmCalibration.score(listOf(tied))
        assertEquals(0, report.totalWon, "an all-tie problem yields no scoring units")
        assertTrue(report.scores.all { it.winShare == 0.0 })
    }

    @Test
    fun `an infeasible arm never wins and is ranked last`() {
        val p = Instance("p", maximize = false, runs = listOf(arm("good", 5.0), arm("none", null)))
        val report = ArmCalibration.score(listOf(p))
        assertEquals(0.0, report.scores.first { it.arm == "none" }.winShare)
        assertEquals("good", report.diverse.first().arm)
        assertEquals(0, report.diverse.first { it.arm == "none" }.newlyCovered)
    }

    @Test
    fun `maximize direction is honoured`() {
        val p = Instance("p", maximize = true, runs = listOf(arm("hi", 100.0), arm("lo", 10.0)))
        val report = ArmCalibration.score(listOf(p))
        assertEquals(1, report.scores.first { it.arm == "hi" }.wins, "higher objective wins when maximizing")
        assertEquals(0, report.scores.first { it.arm == "lo" }.wins)
    }

    @Test
    fun `scoreWinnerSets ranks a palette from winner sets directly`() {
        // The portfolio-regime entry point: winner sets (best-holder per problem) fed straight in.
        val report = ArmCalibration.scoreWinnerSets(arms = listOf("A", "B", "C"), won = listOf(setOf("A"), setOf("B")))
        assertEquals(2, report.totalWon)
        assertEquals(setOf("A", "B"), report.diverse.take(2).map { it.arm }.toSet(), "A wins p1, B wins p2")
        assertEquals(0.0, report.scores.first { it.arm == "C" }.winShare, "a non-winner scores zero")
    }

    @Test
    fun `the marginal-contribution ranking puts complementary specialists first and the dud last`() {
        val p1 = Instance("p1", maximize = false, runs = listOf(arm("A", 1.0), arm("B", 5.0), arm("C", 9.0)))
        val p2 = Instance("p2", maximize = false, runs = listOf(arm("A", 9.0), arm("B", 1.0), arm("C", 5.0)))
        val report = ArmCalibration.score(listOf(p1, p2))
        assertEquals(setOf("A", "B"), report.diverse.take(2).map { it.arm }.toSet(), "A wins p1, B wins p2")
        assertEquals(report.totalWon, report.diverse[1].cumulativeCovered, "k=2 covers every discriminating problem")
        assertEquals("C", report.diverse.last().arm)
        assertEquals(0, report.diverse.last().newlyCovered)
    }
}
