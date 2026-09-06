package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The relaxation participates in a satisfaction search, where it refutes by infeasibility rather than by
 * bounding an objective it does not have. What it must never do is change the verdict.
 */
class LpSatisfactionFeasibilityTest {

    private fun solve(problem: Problem, lp: LpConfig?): SolveResult =
        BacktrackSolver(problem.bake()).solve(BacktrackParams(lpConfig = lp, maxDecisions = 200_000L))

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
        repeat(200) {
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
        assertEquals(true, refuted in 1..199, "the corpus produced only one verdict ($refuted refuted)")
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
