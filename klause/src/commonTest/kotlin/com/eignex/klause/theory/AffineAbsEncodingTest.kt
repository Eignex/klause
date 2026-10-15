package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.AbsAffineValue
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AffineAbsEncodingTest {
    @Test
    fun `wide absolute value rejects a joint conflict admitted by its implied inequalities`() {
        val source = SmtLib.parse("(declare-const x Int) " +
            "(assert (= (abs (* 9223372036854775808 x)) 9223372036854775808)) " +
            "(assert (distinct x (- 1))) (assert (distinct x 1))").model
        val relation = source.factors.filterIsInstance<AffineAbs>().single()

        val result = BacktrackSolver.source(source).solve()

        assertIs<LinearForm.Relaxation>(relation.linearForm)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `wide absolute value conditions retract across refutation cancellation and feasible siblings`() {
        val input = SmtLib.parse("(declare-const b Bool) (declare-const x Int) " +
            "(assert (= (abs (* 9223372036854775808 x)) 9223372036854775808)) " +
            "(assert (or b (= x 0)))")
        val source = input.model
        val prepared = source.prepareExecutionPlan()
        val binding = EngineInvocation(prepared, ModelIdentity.of(source))
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = prepared, invocation = { binding })
        queries.use {
            val refuted = ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap())
            val feasible = ExactNeighborhoodCondition(mapOf(0 to true), emptyMap(), emptyMap())
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(refuted, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(feasible, Cancellation { true }))

            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(feasible, Cancellation.Never)).sample

            assertTrue(sample.boolValue(0))
            assertTrue(sample.exactIntValue(input.intVarNames.getValue("x")).toString() in setOf("-1", "1"))
            assertTrue(source.factors.filterIsInstance<AffineAbs>().single().isSatisfied(sample::exactIntValue))
            assertEquals(SourceEvidenceCheck.Checked, binding.checkWitness(sample, Cancellation.Never))
            val rejected = sample.copy(bools = sample.bools.also { values -> values[0] = false })
            assertIs<SourceEvidenceCheck.Rejected>(binding.checkWitness(rejected, Cancellation.Never))
        }
    }

    @Test
    fun `wide absolute value polynomial participation preserves the complete relation`() {
        val scale = parseBigInt("9223372036854775808")
        val source = Problem(0, 2, Array(2) { IntDomain(-2, 2) }, arrayOf<Factor>(
            AffineAbs(AbsAffineValue(mapOf(0 to scale), BIG_ZERO), AbsAffineValue(mapOf(1 to scale), BIG_ZERO)),
            RealProduct(0, 0, 1, 0.0, 0.0),
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

            assertEquals(if (kotlin.math.abs(input) == output) PolynomialFormula.TRUE else PolynomialFormula.FALSE,
                truth)
        }
    }
}
