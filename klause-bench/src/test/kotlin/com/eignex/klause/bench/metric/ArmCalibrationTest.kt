package com.eignex.klause.bench.metric

import com.eignex.klause.bench.metric.ArmCalibration.ArmRun
import com.eignex.klause.bench.metric.ArmCalibration.Instance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArmCalibrationTest {

    private fun arm(name: String, objective: Double?, feasibleMs: Long?): ArmRun =
        ArmRun(name, feasible = objective != null, finalObjective = objective, timeToFeasibleMs = feasibleMs)

    @Test
    fun `a sole win scores one and a shared win splits`() {
        // p1: A alone has the best objective. p2: A and B share the best, beating C (a discriminating tie).
        val p1 = Instance(
            "p1",
            maximize = false,
            runs = listOf(arm("A", 1.0, 50), arm("B", 5.0, 99), arm("C", 9.0, 99)),
        )
        val p2 = Instance(
            "p2",
            maximize = false,
            runs = listOf(arm("A", 2.0, 99), arm("B", 2.0, 99), arm("C", 8.0, 99)),
        )
        val report = ArmCalibration.score(listOf(p1, p2))
        val a = report.scores.first { it.arm == "A" }
        // A: sole quality win on p1 (1.0) + shared quality win on p2 (0.5) + sole speed win on p1 (1.0).
        assertEquals(2, a.qualityWins, "A wins quality on p1 and shares it on p2")
        assertTrue(a.winShare > report.scores.first { it.arm == "B" }.winShare, "A's unique wins outscore B")
    }

    @Test
    fun `a problem every arm ties on is dropped as non-discriminating`() {
        // All three arms reach the same objective at the same time — tells us nothing; no winners.
        val tied = Instance(
            "p",
            maximize = false,
            runs = listOf(arm("A", 4.0, 50), arm("B", 4.0, 50), arm("C", 4.0, 50)),
        )
        val report = ArmCalibration.score(listOf(tied))
        assertEquals(0, report.totalUnits, "an all-tie problem yields no scoring units")
        assertTrue(report.scores.all { it.winShare == 0.0 }, "nobody scores on a non-discriminating problem")
    }

    @Test
    fun `an infeasible arm never wins and contributes nothing`() {
        val p = Instance("p", maximize = false, runs = listOf(arm("good", 5.0, 100), arm("none", null, null)))
        val report = ArmCalibration.score(listOf(p))
        assertEquals(0.0, report.scores.first { it.arm == "none" }.winShare)
        // good leads the ranking with real coverage; none is ranked last adding zero.
        assertEquals("good", report.diverse.first().arm)
        assertTrue(report.diverse.first().newlyCovered > 0)
        assertEquals(0, report.diverse.first { it.arm == "none" }.newlyCovered)
    }

    @Test
    fun `the marginal-contribution ranking puts complementary specialists first and the dud last`() {
        // fast: always first-feasible but poor objective. deep: best objective but slow. dud: neither.
        val p1 = Instance(
            "p1",
            maximize = false,
            runs = listOf(arm("fast", 9.0, 10), arm("deep", 1.0, 900), arm("dud", 5.0, 500)),
        )
        val p2 = Instance(
            "p2",
            maximize = false,
            runs = listOf(arm("fast", 8.0, 10), arm("deep", 2.0, 900), arm("dud", 5.0, 500)),
        )
        val report = ArmCalibration.score(listOf(p1, p2))
        // The first two slots (the diverse k=2 set) are the two specialists; their cumulative coverage
        // reaches all four units. The dud is ranked last with zero marginal contribution.
        assertEquals(setOf("fast", "deep"), report.diverse.take(2).map { it.arm }.toSet())
        assertEquals(report.totalUnits, report.diverse[1].cumulativeCovered, "k=2 covers every unit")
        assertEquals("dud", report.diverse.last().arm)
        assertEquals(0, report.diverse.last().newlyCovered)
    }

    @Test
    fun `maximize direction is honoured for the quality lens`() {
        val p = Instance("p", maximize = true, runs = listOf(arm("hi", 100.0, 100), arm("lo", 10.0, 100)))
        val report = ArmCalibration.score(listOf(p))
        assertEquals(1, report.scores.first { it.arm == "hi" }.qualityWins, "higher objective wins when maximizing")
        assertEquals(0, report.scores.first { it.arm == "lo" }.qualityWins)
    }
}
