package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArmCalibrationTest {

    @Test
    fun `an arm winning more problems should rank first by win share`() {
        val report = ArmCalibration.scoreWinnerSets(
            arms = listOf("A", "B"),
            won = listOf(setOf("A"), setOf("A"), setOf("B")),
        )
        assertEquals("A", report.scores.first().arm)
        assertEquals(2.0, report.scores.first { it.arm == "A" }.winShare)
        assertEquals(1.0, report.scores.first { it.arm == "B" }.winShare)
    }

    @Test
    fun `co-winners should split the win share of a shared problem`() {
        val report = ArmCalibration.scoreWinnerSets(arms = listOf("A", "B"), won = listOf(setOf("A", "B")))
        assertEquals(0.5, report.scores.first { it.arm == "A" }.winShare)
        assertEquals(0.5, report.scores.first { it.arm == "B" }.winShare)
        assertEquals(1, report.scores.first { it.arm == "A" }.wins)
    }

    @Test
    fun `an arm that never wins should score zero and rank last`() {
        val report = ArmCalibration.scoreWinnerSets(arms = listOf("good", "dud"), won = listOf(setOf("good")))
        assertEquals(0.0, report.scores.first { it.arm == "dud" }.winShare)
        assertEquals(0, report.diverse.first { it.arm == "dud" }.newlyCovered)
    }

    @Test
    fun `marginal-contribution ranking should put complementary specialists first and the dud last`() {
        val report = ArmCalibration.scoreWinnerSets(
            arms = listOf("A", "B", "C"),
            won = listOf(setOf("A"), setOf("B")),
        )
        assertEquals(setOf("A", "B"), report.diverse.take(2).map { it.arm }.toSet(), "A wins p1, B wins p2")
        assertEquals(report.totalWon, report.diverse[1].cumulativeCovered, "k=2 covers every discriminating problem")
        assertEquals("C", report.diverse.last().arm)
        assertEquals(0, report.diverse.last().newlyCovered)
    }

    @Test
    fun `an empty winner set should yield no palette and no scoring units`() {
        val report = ArmCalibration.scoreWinnerSets(arms = listOf("A", "B"), won = emptyList())
        assertEquals(0, report.totalWon)
        assertTrue(report.scores.all { it.winShare == 0.0 })
        assertTrue(report.diverse.all { it.newlyCovered == 0 })
    }
}
