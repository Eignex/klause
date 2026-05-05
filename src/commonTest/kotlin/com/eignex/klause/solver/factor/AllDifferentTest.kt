package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Solver
import kotlin.test.Test
import kotlin.test.assertTrue

class AllDifferentTest {

    @Test
    fun fourVarsPermutationOverFourValues() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(factor),
        )
        val solver = Solver(problem, maxFlipsBeforeRestart = 200)
        val samples = solver.sample(maxFlips = 5_000, randomSeed = 7).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.ints.toSet().size == 4, "duplicates in ${s.ints.toList()}")
        }
    }

    @Test
    fun threeVarsRoomForOneDuplicateRequiresUniqueValues() {
        // 3 vars over [0..3]: easy to satisfy.
        val factor = AllDifferent(intArrayOf(0, 1, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(factor),
        )
        val solver = Solver(problem, maxFlipsBeforeRestart = 200)
        val samples = solver.sample(maxFlips = 5_000, randomSeed = 13).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.ints.toSet().size == 3, "duplicates in ${s.ints.toList()}")
        }
    }
}
