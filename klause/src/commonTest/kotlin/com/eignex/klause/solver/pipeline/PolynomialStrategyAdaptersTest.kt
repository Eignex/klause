package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialStrategyAdaptersTest {
    @Test
    fun `finite strategies compose exact irrational constraints with scheduling and all different`() {
        val formula = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (* x x) 2))
            (assert (> x 0))
        """.trimIndent())
        val source = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
                RealProduct(0, 0, 1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
                Linear(intVars = intArrayOf(), intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0, 1), realCoeffs = doubleArrayOf(1.0, -1.0),
                    op = LinearOp.EQ, bound = 0.0),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )
        val shared = assertNotNull(SharedPolynomialModel.assemble(source, formula.formula))

        val engines = listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)
        for (engine in engines) {
            shared.service.invocation().use { invocation ->
                val result = PolynomialStrategyAdapters(
                    shared, invocation,
                    PolynomialStrategyParams(
                        backtrack = BacktrackParams(randomSeed = 17),
                        localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 100),
                        alnsIterations = 2,
                    ), Cancellation.Never,
                ).solve(engine)

                val sample = assertIs<SolveResult.Sat>(result).assignment
                assertEquals(1L, sample.intValue(0))
                assertTrue(sample.intValue(0) != sample.intValue(1))
                val exact = assertNotNull(sample.algebraicReals)
                assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), exact[0]))
                assertEquals(0, AlgebraicArithmetic().compare(exact[0], exact[1]))
                assertTrue(invocation.stats.workSpent > 0)
            }
        }
    }

    @Test
    fun `complete finite exhaustion refutes every conditioned polynomial branch`() {
        val formula = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val source = Problem(
            numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 0)),
            factors = arrayOf(RealProduct(0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val shared = assertNotNull(SharedPolynomialModel.assemble(source, formula.formula))

        shared.service.invocation().use { invocation ->
            assertIs<SolveResult.Unsat>(PolynomialStrategyAdapters(
                shared, invocation, PolynomialStrategyParams(), Cancellation.Never,
            ).solve(FiniteEngine.BACKTRACK))
        }
    }

    @Test
    fun `heuristic failure carries no global refutation`() {
        val formula = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) (- 1)))")
        val source = Problem(
            numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val shared = assertNotNull(SharedPolynomialModel.assemble(source, formula.formula))

        shared.service.invocation().use { invocation ->
            assertIs<SolveResult.Unknown>(PolynomialStrategyAdapters(
                shared, invocation,
                PolynomialStrategyParams(localSearch = LocalSearchParams(maxFlips = 2)), Cancellation.Never,
            ).solve(FiniteEngine.LOCAL_SEARCH))
        }
    }

    @Test
    fun `finite assumptions cannot leak across polynomial invocations`() {
        val formula = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val source = Problem(
            numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf(RealProduct(0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val shared = assertNotNull(SharedPolynomialModel.assemble(source, formula.formula))

        for (pin in listOf(0L, 1L, 0L)) {
            shared.service.invocation(QfNraParams()).use { invocation ->
                val params = PolynomialStrategyParams(assumptions = Assumptions(ints = mapOf(0 to pin)))
                val result = PolynomialStrategyAdapters(shared, invocation, params, Cancellation.Never)
                    .solve(FiniteEngine.BACKTRACK)
                if (pin == 0L) assertIs<SolveResult.Unsat>(result) else assertIs<SolveResult.Sat>(result)
            }
        }
    }
}
