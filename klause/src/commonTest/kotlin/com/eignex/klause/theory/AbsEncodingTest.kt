package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbsEncodingTest {
    @Test
    fun `polynomial participation agrees with exact native absolute value checks`() {
        val source = Problem(0, 2, Array(2) { IntDomain(-2, 2) }, arrayOf<Factor>(
            Abs(0, 1), RealProduct(0, 0, 1, 0.0, 0.0),
        ), numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(0.0, 0.0))
        val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
        assertEquals(0, model.encodingAttributions.first().sourceFactorIndex)
        for (input in -2L..2L) for (output in -2L..2L) {
            val arithmetic = PolynomialArithmetic()
            val values = longArrayOf(input, output)
            val fixed = mutableMapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
            for ((id, column) in model.integerColumns) fixed[column] = BigFraction.ofLong(values[id])
            val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic, fixed)
            val signs = normalized.atoms.map { atom ->
                assertTrue(atom.polynomial.variables.isEmpty())
                val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                    .compareTo(BigFraction.ZERO)
                if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
            }.toIntArray()

            val truth = normalized.truth(intArrayOf(), signs, arithmetic)

            assertEquals(if (kotlin.math.abs(input) == output) PolynomialFormula.TRUE
                else PolynomialFormula.FALSE, truth)
        }
    }

    @Test
    fun `absolute value installs a complete linear relation beyond its relaxation`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (= (abs x) 2)) (assert (< x (- 2)))").model
        val relation = source.factors.filterIsInstance<Abs>().single()

        val result = BacktrackSolver.source(source).solve()

        assertIs<LinearForm.Relaxation>(relation.linearForm)
        assertEquals(EncoderSupport.EQUIVALENT, BuiltInEncoderProvider.select(relation, EncodingTarget.LINEAR).support)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `sign conditions retract across exact absolute value queries`() {
        val input = SmtLib.parse("(declare-const b Bool) (declare-const x Int) " +
            "(assert (= (abs x) 2)) (assert (= b (>= x 0)))")
        val source = input.model
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())

        queries.use {
            for ((positive, expected) in listOf(true to "2", false to "-2", true to "2")) {
                val outcome = it.check(ExactNeighborhoodCondition(mapOf(0 to positive), emptyMap(), emptyMap()),
                    Cancellation.Never)
                val sample = assertIs<ExactNeighborhoodResult.Witness>(outcome).sample

                assertEquals(expected, sample.exactIntValue(input.intVarNames.getValue("x")).toString())
            }
        }
        assertNotNull(source.factors.filterIsInstance<Abs>().singleOrNull())
    }
}
