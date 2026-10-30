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
    fun `low fractional progress should not earn full family success`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = true, plateau = true, reward = 0.1) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) > 700)
    }

    @Test
    fun `silent complete search should preserve family balance after a witness`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = false, plateau = true) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) in 400..600)
    }

    @Test
    fun `priced silent complete segments should yield discretionary family share`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = false, plateau = true, reward = 0.0) }

        assertTrue(choicesOf(policy, ArmFamily.LocalSearch) > 800)
    }

    @Test
    fun `priced silence should preserve complete family balance before a witness`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = false, plateau = false, reward = 0.0) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) in 400..600)
    }

    @Test
    fun `priced complete search should recover family share after fresh progress`() {
        val policy = FamilyPolicy(Random(3))
        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = false, plateau = true, reward = 0.0) }

        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = true, plateau = true, reward = 1.0) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) > 600)
    }

    @Test
    fun `equal fractional progress should preserve family balance`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) {
            for (family in pair) policy.record(family, progressed = true, plateau = true, reward = 0.5)
        }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) in 400..600)
    }

    @Test
    fun `complete search deductions should preserve family balance before a witness`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = true, plateau = false) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) in 400..600)
    }

    @Test
    fun `complete search progress should earn family share after a witness`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.Backtrack, progressed = true, plateau = true) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) > 800)
    }

    @Test
    fun `a plateaued local search should hand its share to backtrack`() {
        val policy = FamilyPolicy(Random(3))

        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = false, plateau = true) }

        assertTrue(choicesOf(policy, ArmFamily.Backtrack) > 800)
    }

    @Test
    fun `local search should win its share back once it progresses again`() {
        val policy = FamilyPolicy(Random(3))
        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = false, plateau = true) }

        repeat(8) { policy.record(ArmFamily.LocalSearch, progressed = true, plateau = true) }

        assertTrue(choicesOf(policy, ArmFamily.LocalSearch) > 600)
    }

    @Test
    fun `a leaning prior should still give the other family turns`() {
        val profile = ProblemProfile(ProblemClass.Continuous, optimizing = true, wide = false, scheduling = false)
        val policy = FamilyPolicy(Random(3), FamilyPrior.of(profile))

        assertTrue(choicesOf(policy, ArmFamily.LocalSearch) > 30)
    }

    @Test
    fun `policy should choose only an eligible family`() {
        val policy = FamilyPolicy(Random(3))
        repeat(8) { policy.record(ArmFamily.Lns, progressed = true, plateau = true) }

        assertEquals(setOf(ArmFamily.Backtrack), List(100) { policy.choose(listOf(ArmFamily.Backtrack)) }.toSet())
    }
}
