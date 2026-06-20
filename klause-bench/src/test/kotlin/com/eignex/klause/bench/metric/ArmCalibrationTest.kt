package com.eignex.klause.bench.metric

import com.eignex.klause.bench.metric.ArmCalibration.ArmRun
import com.eignex.klause.bench.metric.ArmCalibration.Incumbent
import com.eignex.klause.bench.metric.ArmCalibration.Instance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArmCalibrationTest {

    private fun run(arm: String, feasible: Boolean, incs: List<Pair<Long, Double>>): ArmRun {
        val incumbents = incs.map { Incumbent(it.first, it.second) }
        return ArmRun(
            arm = arm,
            feasible = feasible,
            finalObjective = incumbents.lastOrNull()?.objective,
            timeToFeasibleMs = incumbents.firstOrNull()?.ms,
            incumbents = incumbents,
        )
    }

    @Test
    fun `a faster-to-feasible arm at equal objective has the lower primal integral`() {
        // Both reach objective 10 within a 1000ms budget; fast reaches it at 100ms, slow at 900ms.
        val inst = Instance(
            "p",
            maximize = false,
            budgetMs = 1000,
            runs = listOf(
                run("fast", true, listOf(100L to 10.0)),
                run("slow", true, listOf(900L to 10.0)),
            ),
        )
        val fast = ArmCalibration.primalIntegral(inst, inst.runs[0])
        val slow = ArmCalibration.primalIntegral(inst, inst.runs[1])
        assertTrue(fast < slow, "fast=$fast slow=$slow")
    }

    @Test
    fun `an infeasible arm has the worst primal integral and never wins`() {
        val inst = Instance(
            "p",
            maximize = false,
            budgetMs = 1000,
            runs = listOf(
                run("good", true, listOf(100L to 5.0)),
                run("none", false, emptyList()),
            ),
        )
        assertEquals(1.0, ArmCalibration.primalIntegral(inst, inst.runs[1]))
        assertTrue(ArmCalibration.primalIntegral(inst, inst.runs[0]) < 1.0)
        val report = ArmCalibration.score(listOf(inst))
        assertEquals("good", report.diverse.first().arm)
        assertTrue(report.diverse.none { it.arm == "none" }, "an infeasible arm is redundant")
    }

    @Test
    fun `the diverse palette keeps arms that win different instances`() {
        // Arm A wins instance 1 (fast + good), arm B wins instance 2; a dominated arm C wins neither.
        val i1 = Instance(
            "i1",
            maximize = false,
            budgetMs = 1000,
            runs = listOf(
                run("A", true, listOf(50L to 1.0)),
                run("B", true, listOf(800L to 5.0)),
                run("C", true, listOf(900L to 9.0)),
            ),
        )
        val i2 = Instance(
            "i2",
            maximize = false,
            budgetMs = 1000,
            runs = listOf(
                run("A", true, listOf(900L to 9.0)),
                run("B", true, listOf(50L to 1.0)),
                run("C", true, listOf(800L to 5.0)),
            ),
        )
        val report = ArmCalibration.score(listOf(i1, i2))
        val palette = report.diverse.map { it.arm }
        assertEquals(setOf("A", "B"), palette.toSet(), "A and B each win one instance; C is redundant")
        assertEquals(2, report.diverse.size, "two slots, one per covered instance")
    }

    @Test
    fun `maximize direction is honoured`() {
        // Higher objective is better when maximize; arm hi should beat lo.
        val inst = Instance(
            "p",
            maximize = true,
            budgetMs = 1000,
            runs = listOf(
                run("hi", true, listOf(100L to 100.0)),
                run("lo", true, listOf(100L to 10.0)),
            ),
        )
        assertTrue(
            ArmCalibration.primalIntegral(inst, inst.runs[0]) < ArmCalibration.primalIntegral(inst, inst.runs[1]),
        )
    }
}
