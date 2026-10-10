package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relaxation participates in a satisfaction search, where it refutes by infeasibility rather than by
 * bounding an objective it does not have. What it must never do is change the verdict.
 */
class LpSatisfactionFeasibilityTest {

    private fun solve(problem: Problem, lp: LpConfig?): SolveResult =
        BacktrackSolver(problem.bake()).solve(BacktrackParams(lpConfig = lp, maxDecisions = 200_000L))

    @Test
    fun `root shaving fixes an integer from continuous rows before a satisfaction decision`() {
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
    fun `an LP satisfaction root retains preparation across a zero work slice`() {
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
        val search = BacktrackSolver(problem.bake()).resumableSolve(BacktrackParams(lpConfig = LpConfig.AGGRESSIVE))

        search.use {
            assertNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L))
            assertIs<SolveResult.Unsat>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 1_000L))
        }
    }

    @Test
    fun `root cut harvest refutes an integer equality without an objective`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(Linear(intArrayOf(2, 2, 2), intArrayOf(0, 1, 2), LinearOp.EQ, 3)),
        )

        val result = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(lpPlan = LpPlan(bounding = true, cuts = true)),
        )

        assertIs<SolveResult.Unsat>(result)
        assertTrue(result.stats.lp.rootPasses.sum > 0.0)
        assertTrue(result.stats.lp.cuts.sum > 0.0)
    }

    private fun randomLinearSystem(rng: Random): Problem {
        val n = rng.nextInt(3, 7)
        val factors = ArrayList<Factor>()
        repeat(rng.nextInt(2, 6)) {
            val k = rng.nextInt(2, n + 1)
            val vars = (0 until n).shuffled(rng).take(k).toIntArray()
            val coeffs = IntArray(k) { rng.nextInt(-3, 4) }
            val op = if (rng.nextBoolean()) LinearOp.LE else LinearOp.GE
            factors.add(Linear(coeffs, vars, op, rng.nextInt(-4, 9)))
        }
        return Problem(0, n, Array(n) { IntDomain(0, 6) }, factors.toTypedArray())
    }

    @Test
    fun `the relaxation never changes a satisfaction verdict`() {
        val rng = Random(20260906)
        var refuted = 0
        repeat(30) {
            val problem = randomLinearSystem(rng)

            val off = solve(problem, LpConfig.OFF)
            val on = solve(problem, LpConfig.AGGRESSIVE)

            if (off is SolveResult.Unsat) refuted++
            assertEquals(
                off::class,
                on::class,
                "the relaxation changed the verdict of ${problem.factors.toList()}",
            )
        }
        // Both verdicts have to occur, or the parity above is vacuous.
        assertIs<SolveResult.Unsat>(solve(unsatisfiableSystem(), LpConfig.AGGRESSIVE))
        assertEquals(true, refuted in 1..29, "the corpus produced only one verdict ($refuted refuted)")
    }

    /** `2x + 2y <= 3` with `x + y >= 2` over non-negative integers: the relaxation alone refutes it. */
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
