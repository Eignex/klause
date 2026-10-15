package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BacktrackSolverLpTest {
    @Test
    fun `root shaving should fix an integer from continuous rows before a satisfaction decision`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 3L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )

        val result = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(lpPlan = LpPlan(bounding = true, variableShaving = true), maxDecisions = 1),
        )

        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(2L, sat.assignment.ints[0])
        assertEquals(0.0, sat.stats.search.nodes.sum)
    }

    @Test
    fun `an LP satisfaction root should retain preparation across a zero work slice`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 0L),
                Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val search = BacktrackSolver(problem.bake()).resumableSolve(
            BacktrackParams(lpConfig = LpConfig.AGGRESSIVE, lpPlan = LpPlan(variableShaving = true)),
        )

        search.use {
            assertNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L))
            assertIs<SolveResult.Unsat>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 1_000L))
        }
    }

    @Test
    fun `root Hall cuts should preserve an all-different satisfaction witness`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 1),
            ),
        )

        val result = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                lpPlan = LpPlan(
                    bounding = true,
                    cuts = true,
                    gomory = false,
                    mir = false,
                    boundMaxDepth = 0,
                    cutSearchMaxDepth = 0,
                ),
            ),
        )

        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(3, sat.assignment.ints.toSet().size)
        assertTrue(result.stats.lp.rootPasses.sum > 0.0)
        assertTrue(result.stats.lp.cuts.sum > 0.0)
    }

    @Test
    fun `the relaxation should refute an integer infeasible system`() {
        assertIs<SolveResult.Unsat>(
            BacktrackSolver(unsatisfiableSystem().bake())
                .solve(BacktrackParams(lpConfig = LpConfig.AGGRESSIVE, maxDecisions = 200_000L)),
        )
    }

    private fun unsatisfiableSystem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
        ),
    )
}
