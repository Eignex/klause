package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LeafRealCompletionTest {

    // x + k = 2.5 and x ≤ 1 over a continuous x in [0, 10] and an integer k in [0, 5].
    private val problem = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 2.5),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(10.0),
    )

    private fun candidate(k: Long, x: Double) = Sample(BooleanArray(0), longArrayOf(k), doubleArrayOf(x))

    @Test
    fun `a candidate whose discrete part admits a completion gets exact reals`() {
        val completion = LeafRealCompletion(
            problem,
            objective = null,
        ).complete(candidate(2, 0.4999999), Cancellation.Never)

        val witness = assertIs<Completion.Witness>(completion)
        assertEquals(BigFraction.ofDouble(0.5), witness.sample.exactReals?.get(0))
        assertEquals(2L, witness.sample.ints[0])
    }

    @Test
    fun `a candidate whose discrete part admits no completion is refuted`() {
        assertIs<Completion.Refuted>(
            LeafRealCompletion(problem, objective = null).complete(candidate(0, 1.0), Cancellation.Never),
        )
    }

    @Test
    fun `a refutation names the rows its proof combines`() {
        // k = 0 forces x = 2.5 through the first row, which the second row's x ≤ 1 forbids.
        val completion = LeafRealCompletion(problem, objective = null).complete(candidate(0, 1.0), Cancellation.Never)

        val refuted = assertIs<Completion.Refuted>(completion)
        assertEquals(setOf(0, 1), refuted.factors.toSet())
    }
}
