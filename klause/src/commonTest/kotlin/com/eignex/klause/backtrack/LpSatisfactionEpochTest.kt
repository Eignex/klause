package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LpSatisfactionEpochTest {
    @Test
    fun `satisfaction uses root tidy and epoch controls through its production owner`() {
        val problem = Problem(
            1,
            2,
            Array(2) { IntDomain(0, 4) },
            arrayOf(ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        val modes = listOf(BacktrackParams(), BacktrackParams(lpRootTidy = true), BacktrackParams(lpEpochs = true))
        for ((index, params) in modes.withIndex()) {
            val result = assertIs<SolveResult.Sat>(BacktrackSolver(problem.bake()).solve(params))

            assertEquals(result.assignment.ints[0] + result.assignment.ints[1] <= 3L, result.assignment.bools[0])
            if (index == 0) {
                assertTrue(result.stats.lp.epochs.isEmpty())
            } else {
                assertEquals(1L, result.stats.lp.epochs["root_opportunities"])
            }
        }
    }
}
