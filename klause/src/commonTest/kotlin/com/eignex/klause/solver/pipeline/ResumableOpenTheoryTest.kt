package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResumableOpenTheoryTest {

    // b_i ⇔ x_i ≥ 5 over open x_i, every pair of neighbours with exactly one b true, and Σ x_i ≤ total.
    private fun chain(n: Int, total: Int): Problem {
        val open = Bits(n).also { bits -> for (v in 0 until n) bits.set(v) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors += ReifiedLinear(i, intArrayOf(1), intArrayOf(i), LinearOp.GE, 5)
        for (i in 0 until n - 1) {
            factors += Clause(intArrayOf(Lit.make(i, true), Lit.make(i + 1, true)))
            factors += Clause(intArrayOf(Lit.make(i, false), Lit.make(i + 1, false)))
        }
        for (i in 0 until n) factors += Linear(intArrayOf(1), intArrayOf(i), LinearOp.GE, 0)
        factors += Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.LE, total)
        return Problem(
            n,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), open, open),
            factors = factors.toTypedArray(),
        )
    }

    private fun sliced(problem: Problem): Pair<OpenTheoryResult, Int> {
        val request = OpenTheoryRequest(problem, componentPlan = problem.componentPlan())
        val search = ResumableOpenTheory(OpenTheoryPipeline.engineFor(request), TheoryParams())
        var slices = 0
        while (true) {
            slices++
            search.runSlice(
                Cancellation.Never,
                sliceMillis = Long.MAX_VALUE,
                sliceWork = 1,
            )?.let { return it to slices }
        }
    }

    @Test
    fun `a sliced search reaches a satisfiable verdict across several slices`() {
        val (result, slices) = sliced(chain(8, total = 20))

        assertIs<OpenTheoryResult.Sat>(result)
        assertTrue(slices > 1, "slices=$slices")
    }

    @Test
    fun `a paused search reports the glue clauses its theory conflicts taught it`() {
        // b_i ⇔ x_i ≥ 5 over open x_i, every pair summing to at most 9, and two clauses each wanting one b true.
        val n = 4
        val open = Bits(n).also { bits -> for (v in 0 until n) bits.set(v) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors += ReifiedLinear(i, intArrayOf(1), intArrayOf(i), LinearOp.GE, 5)
        for (i in 0 until n) factors += Linear(intArrayOf(1), intArrayOf(i), LinearOp.GE, 0)
        for (i in 0 until n) {
            for (j in i + 1 until n) factors += Linear(intArrayOf(1, 1), intArrayOf(i, j), LinearOp.LE, 9)
        }
        factors += Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        factors += Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true)))
        val problem = Problem(
            n,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), open, open),
            factors = factors.toTypedArray(),
        )
        val request = OpenTheoryRequest(problem, componentPlan = problem.componentPlan())
        val search = ResumableOpenTheory(OpenTheoryPipeline.engineFor(request), TheoryParams())
        var pausedGlue = 0.0

        while (search.runSlice(Cancellation.Never, sliceMillis = Long.MAX_VALUE, sliceWork = 1) == null) {
            pausedGlue = maxOf(pausedGlue, search.stats.search.glueClauses.sum)
        }

        assertTrue(pausedGlue > 0.0, "pausedGlue=$pausedGlue")
    }
}
