package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SharedPolynomialStrategyTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<Array<FiniteEngine>> = listOf(
            FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED,
        ).map { arrayOf(it) }
    }

    @Test
    fun `finite strategies compose exact irrational constraints with scheduling and all different`() {
        val source = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf(
                ContinuousProduct(0, 0, 2),
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
                RealProduct(0, 0, 1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
                Linear(intVars = intArrayOf(), intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0, 1), realCoeffs = doubleArrayOf(1.0, -1.0),
                    op = LinearOp.EQ, bound = 0.0),
            ),
            numRealVars = 3,
            realLower = doubleArrayOf(0.0, Double.NEGATIVE_INFINITY, 2.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 2.0),
        )
        val request = source.pipelineRequest()
        val shared = request.resolvedModel()
        kotlin.test.assertSame(source, request.preparedPlan.originalSource)
        assertTrue(request.preparedPlan.capabilities.admission(engine).runnable)

        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(randomSeed = 17),
            localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 100), alnsIterations = 2,
        ))
        val scope = PolynomialInvocationScope(shared.service, params)
        val result = PolynomialStrategyAdapters(request.preparedPlan, shared, scope, params.strategy,
            Cancellation.Never, parallelism = 1).solve(engine)

        val sample = assertIs<SolveResult.Sat>(result).assignment
        assertEquals(1L, sample.intValue(0))
        assertTrue(sample.intValue(0) != sample.intValue(1))
        val exact = assertNotNull(sample.algebraicReals)
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), exact[0]))
        assertEquals(0, AlgebraicArithmetic().compare(exact[0], exact[1]))
        assertTrue(scope.stats.workSpent > 0)
    }

}
