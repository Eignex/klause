package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OptimizingCompletionTest {

    // An integer x and an open continuous y with x ≤ y ≤ x + 1, the lower side [strict]; the objective minimizes y.
    private fun model(strict: Boolean) = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(-1000, 1000)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, 0.0, strict),
            Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
        realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
    )

    private fun complete(strict: Boolean): Completion {
        val problem = model(strict)
        val completion = OptimizingCompletion(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            TheoryCompletion(problem, TheoryParams()),
        )
        return completion.complete(Sample(BooleanArray(0), longArrayOf(3), doubleArrayOf(3.7)), Cancellation.Never)
    }

    @Test
    fun `a candidate completes to the continuous point that minimizes the objective`() {
        val y = assertNotNull(assertIs<Completion.Witness>(complete(strict = false)).sample.exactReals)[0]

        assertEquals(BigFraction.ofLong(3), y)
    }

    @Test
    fun `an optimum on a strict row's boundary falls back to a point strictly inside it`() {
        val y = assertNotNull(assertIs<Completion.Witness>(complete(strict = true)).sample.exactReals)[0]

        assertTrue(y > BigFraction.ofLong(3) && y <= BigFraction.ofLong(4), "y=$y")
    }
}
