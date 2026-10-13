package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class QuadraticSourceWitnessTest {
    @Test
    fun `a polynomial portfolio admits original coordinates with private lowering auxiliaries`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val auxiliary = PolynomialExpression.Variable(SourceRealId(1))
        val formula = PolynomialRealFormula.And(listOf(
            PolynomialRealFormula.Atom(x, PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(BigFraction.ofLong(2).num)),
            PolynomialRealFormula.Atom(auxiliary, PolynomialRelation.EQ, x),
        ))
        val source = PolynomialRealProblem(formula, 2, 0, 1, 0, mapOf("x" to 0), emptyMap())
        val request = source.pipelineRequest()
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
            QuadraticCoefficient.ONE, QuadraticVariable.Real(SourceRealId(0)),
        )))

        val execution = OpenTheoryPipeline.minimize(request, objective, engine = FiniteEngine.MIXED)

        val point = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result).incumbent
        assertEquals(1, point.sample.numRealVars)
        assertEquals(BigFraction.ofLong(2), point.value.rational)
        val publication = assertNotNull(point.sample.witnessCertificate?.quadraticPublication)
        val expected = assertNotNull(request.preparedPlan.quadraticEngineInvocation(objective,
            ObjectiveSense.MINIMIZE, Assumptions.None))
        assertIs<SourceEvidenceCheck.Checked>(publication.verify(expected, point, AlgebraicArithmetic()) {
            checkQuadraticSourceWitness(request.preparedPlan, it, Cancellation.Never, PolynomialLimits())
        })
    }

    @Test
    fun `shared IR checking rejects a point that violates a continuous product`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 2.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0, 2.0),
            exactReals = listOf(BigFraction.ONE, BigFraction.ofLong(2)))

        val checked = checkQuadraticSourceWitness(source.pipelineRequest().preparedPlan, point,
            Cancellation.Never, PolynomialLimits())

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `shared IR checking retains original finite factors`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val point = Sample(booleanArrayOf(false), longArrayOf())

        val checked = checkQuadraticSourceWitness(source.preparePolynomialExecutionPlan(), point,
            Cancellation.Never, PolynomialLimits())

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `native integer checking retains interleaved original numeric coordinates`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const x Real) (declare-const n Int) (assert (= (* x x) (to_real n)))",
        )).problem
        val root = AlgebraicArithmetic().roots(RationalPolynomial(
            listOf(BigFraction.ofLong(-2), BigFraction.ZERO, BigFraction.ONE),
        )).last()
        val point = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(1.5), algebraicReals = listOf(root))

        val checked = checkQuadraticSourceWitness(source.pipelineRequest().preparedPlan, point,
            Cancellation.Never, PolynomialLimits())

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `native integer checking rejects a wrong original integer coordinate`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const x Real) (declare-const n Int) (assert (= (* x x) (to_real n)))",
        )).problem
        val point = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(1.0),
            exactReals = listOf(BigFraction.ONE))

        val checked = checkQuadraticSourceWitness(source.pipelineRequest().preparedPlan, point,
            Cancellation.Never, PolynomialLimits())

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `native integer producers publish their original wide point and exact value`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const n Int) (declare-const x Real) " +
                "(assert (= n 9223372036854775808)) (assert (= (* x x) 2))",
        )).problem
        val request = source.pipelineRequest()
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
            QuadraticCoefficient.ONE, QuadraticVariable.Int(SourceIntId(0)),
        )))

        val execution = OpenTheoryPipeline.minimize(request, objective, engine = FiniteEngine.MIXED)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        val point = result.incumbent
        val publication = assertNotNull(point.sample.witnessCertificate?.quadraticPublication)
        assertSame(point.sample, publication.incumbent.sample)
        assertEquals(parseBigInt("9223372036854775808"), point.sample.exactIntValue(0))
        assertEquals("9223372036854775808", point.value.rational.toString())
        val expected = assertNotNull(request.preparedPlan.quadraticEngineInvocation(objective,
            ObjectiveSense.MINIMIZE, Assumptions.None))
        assertIs<SourceEvidenceCheck.Checked>(publication.verify(expected, point, AlgebraicArithmetic()) {
            checkQuadraticSourceWitness(request.preparedPlan, it, Cancellation.Never, PolynomialLimits())
        })
    }
}
