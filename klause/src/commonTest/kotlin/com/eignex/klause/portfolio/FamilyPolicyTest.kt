package com.eignex.klause.portfolio

import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FamilyPolicyTest {

    private val pair = listOf(ArmFamily.Backtrack, ArmFamily.LocalSearch)

    private fun choicesOf(policy: FamilyPolicy, family: ArmFamily, rounds: Int = 1_000): Int =
        (0 until rounds).count { policy.choose(pair) == family }

    @Test
    fun `a plateaued local search hands its share to backtrack`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = false, plateau = true) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) > 800)
    }

    @Test
    fun `local search searching for a first solution keeps its share`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = false, plateau = false) }

        assertTrue(choicesOf(policy, ArmFamily.LocalSearch) in 400..600)
    }

    @Test
    fun `backtrack showing no progress keeps its share`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = false, plateau = true) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) in 400..600)
    }

    @Test
    fun `local search wins its share back once it progresses again`() {
        val policy = FamilyPolicy(Random(3))
        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = false, plateau = true) }

        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = true, plateau = true) }

        assertTrue(choicesOf(policy, ArmFamily.LocalSearch) > 600)
    }

    @Test
    fun `a continuous model's prior leans towards backtrack before any evidence`() {
        val profile = ProblemProfile(ProblemClass.Continuous, optimizing = true, wide = false, scheduling = false)
        val policy = FamilyPolicy(Random(3), FamilyPrior.of(profile))

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) > 800)
    }

    @Test
    fun `a leaning prior still gives the other family turns`() {
        val profile = ProblemProfile(ProblemClass.Continuous, optimizing = true, wide = false, scheduling = false)
        val policy = FamilyPolicy(Random(3), FamilyPrior.of(profile))

        assertTrue(choicesOf(policy, ArmFamily.LocalSearch) > 30)
    }

    @Test
    fun `a finite optimization model leans no family`() {
        val profile = ProblemProfile(ProblemClass.FiniteCp, optimizing = true, wide = false, scheduling = false)
        val prior = FamilyPrior.of(profile)

        for (family in ArmFamily.entries) {
            assertEquals(1.0 to 1.0, prior.successes(family) to prior.failures(family), "$family")
        }
    }

    @Test
    fun `only an eligible family is chosen`() {
        val policy = FamilyPolicy(Random(3))
        repeat(8) { policy.record(ArmFamily.Lns, progressed = true, plateau = true) }

        assertEquals(setOf(ArmFamily.Backtrack), List(100) { policy.choose(listOf(ArmFamily.Backtrack)) }.toSet())
    }
}
