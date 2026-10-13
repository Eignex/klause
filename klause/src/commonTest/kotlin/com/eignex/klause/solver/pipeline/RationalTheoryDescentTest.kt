package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RationalTheoryDescentTest {
    @Test
    fun `an attained real optimum requires exclusion of strict improvements`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))

        val result = RationalTheoryDescent(source, objective, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
        }

        assertEquals(-2.0, assertIs<MinimizeResult.Optimal>(result).objective)
    }

    @Test
    fun `an unattained real infimum is never an optimum`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0, strict = true)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))

        val result = RationalTheoryDescent(source, objective, TheoryParams(openWorkLimit = 12)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
        }

        assertIs<MinimizeResult.BestFound>(result)
    }

    @Test
    fun `a retained linear descent carries a limit outside scalar completion`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0, strict = true)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val descent = RationalTheoryDescent(source, objective, TheoryParams())

        val result = descent.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> } }

        assertIs<MinimizeResult.BestFound>(result)
        assertIs<SourceClaim.Limit>(assertNotNull(descent.linearResult?.terminal).claim)
    }
}
