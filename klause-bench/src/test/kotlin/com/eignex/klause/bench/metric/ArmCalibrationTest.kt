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
    fun `incomplete scores objective quality only, sharing ties and ignoring optimality`() {
        // B proves its (worse) objective on p1 — incomplete ignores that. p2 is a shared best A,B beat C.
        val p1 = Instance(
            "p1",
            maximize = false,
            runs = listOf(arm("A", 1.0), arm("B", 5.0, proven = true), arm("C", 9.0)),
        )
        val p2 = Instance("p2", maximize = false, runs = listOf(arm("A", 2.0), arm("B", 2.0), arm("C", 8.0)))
        val report = ArmCalibration.score(listOf(p1, p2), complete = false)
        val a = report.scores.first { it.arm == "A" }
        assertEquals(2, a.wins, "A wins p1 outright and shares p2")
        assertEquals(1.5, a.winShare, "1.0 (sole) + 0.5 (shared)")
        assertEquals(0.5, report.scores.first { it.arm == "B" }.winShare, "B only shares p2; its proof is ignored")
    }

    @Test
    fun `complete rewards a proved optimum over an equal unproved objective`() {
        val p = Instance(
            "p",
            maximize = false,
            runs = listOf(arm("A", 5.0, proven = true), arm("B", 5.0), arm("C", 9.0)),
        )
        assertEquals(1, ArmCalibration.score(listOf(p), complete = true).scores.first { it.arm == "A" }.wins)
        assertEquals(0, ArmCalibration.score(listOf(p), complete = true).scores.first { it.arm == "B" }.wins)
        // Incomplete ignores the proof, so A and B share.
        assertEquals(0.5, ArmCalibration.score(listOf(p), complete = false).scores.first { it.arm == "B" }.winShare)
    }

    @Test
    fun `complete breaks an equal objective by speed`() {
        val p = Instance(
            "p",
            maximize = false,
            runs = listOf(arm("fast", 5.0, ms = 100), arm("slow", 5.0, ms = 500), arm("bad", 9.0)),
        )
        val report = ArmCalibration.score(listOf(p), complete = true)
        assertEquals(1, report.scores.first { it.arm == "fast" }.wins, "faster wins the equal-objective tie")
        assertEquals(0, report.scores.first { it.arm == "slow" }.wins)
    }

    @Test
    fun `a problem every arm ties on is dropped as non-discriminating`() {
        val tied = Instance("p", maximize = false, runs = listOf(arm("A", 4.0), arm("B", 4.0), arm("C", 4.0)))
        val report = ArmCalibration.score(listOf(tied), complete = false)
        assertEquals(0, report.totalWon, "an all-tie problem yields no scoring units")
        assertTrue(report.scores.all { it.winShare == 0.0 })
    }

    @Test
    fun `an infeasible arm never wins and is ranked last`() {
        val p = Instance("p", maximize = false, runs = listOf(arm("good", 5.0), arm("none", null)))
        val report = ArmCalibration.score(listOf(p), complete = false)
        assertEquals(0.0, report.scores.first { it.arm == "none" }.winShare)
        assertEquals("good", report.diverse.first().arm)
        assertEquals(0, report.diverse.first { it.arm == "none" }.newlyCovered)
    }

    @Test
    fun `maximize direction is honoured`() {
        val p = Instance("p", maximize = true, runs = listOf(arm("hi", 100.0), arm("lo", 10.0)))
        val report = ArmCalibration.score(listOf(p), complete = false)
        assertEquals(1, report.scores.first { it.arm == "hi" }.wins, "higher objective wins when maximizing")
        assertEquals(0, report.scores.first { it.arm == "lo" }.wins)
    }

    @Test
    fun `the marginal-contribution ranking puts complementary specialists first and the dud last`() {
        val p1 = Instance("p1", maximize = false, runs = listOf(arm("A", 1.0), arm("B", 5.0), arm("C", 9.0)))
        val p2 = Instance("p2", maximize = false, runs = listOf(arm("A", 9.0), arm("B", 1.0), arm("C", 5.0)))
        val report = ArmCalibration.score(listOf(p1, p2), complete = false)
        assertEquals(setOf("A", "B"), report.diverse.take(2).map { it.arm }.toSet(), "A wins p1, B wins p2")
        assertEquals(report.totalWon, report.diverse[1].cumulativeCovered, "k=2 covers every discriminating problem")
        assertEquals("C", report.diverse.last().arm)
        assertEquals(0, report.diverse.last().newlyCovered)
    }
}
