package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TheoryCompletionTest {

    // 0 < x < 1 over an open continuous x: both rows strict.
    private val model = Problem(
        numBoolVars = 0,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0, strict = true),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, 0.0, strict = true),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
        realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
    )

    @Test
    fun `a candidate over strict rows completes to an exact point strictly inside them`() {
        val candidate = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))

        val completion = TheoryCompletion(model, TheoryParams()).complete(candidate, Cancellation.Never)

        val x = assertNotNull(assertIs<Completion.Witness>(completion).sample.exactReals)[0]
        assertTrue(x > BigFraction.ZERO && x < BigFraction.ONE, "x=$x")
    }
}
