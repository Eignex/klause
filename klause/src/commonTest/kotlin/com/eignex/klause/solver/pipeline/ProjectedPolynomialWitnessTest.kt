package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectedPolynomialWitnessTest {
    @Test
    fun `projected rational point should admit an irrational lowering auxiliary`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val auxiliary = PolynomialExpression.Variable(SourceRealId(1))
        val formula = PolynomialRealFormula.Atom(
            PolynomialExpression.Power(auxiliary, 2),
            PolynomialRelation.EQ,
            x,
        )
        val source = PolynomialRealProblem(formula, 2, 0, 1, 0, mapOf("x" to 0), emptyMap())
        val point = Sample(
            booleanArrayOf(),
            longArrayOf(),
            doubleArrayOf(2.0),
            exactReals = listOf(BigFraction.ofLong(2)),
        )

        val checked = checkProjectedPolynomialWitness(source, point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `projected algebraic point should admit its lowering extension`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val auxiliary = PolynomialExpression.Variable(SourceRealId(1))
        val formula = PolynomialRealFormula.Atom(
            auxiliary,
            PolynomialRelation.EQ,
            PolynomialExpression.Power(x, 2),
        )
        val source = PolynomialRealProblem(formula, 2, 0, 1, 0, mapOf("x" to 0), emptyMap())
        val root = AlgebraicArithmetic().roots(
            RationalPolynomial(
                listOf(BigFraction.ofLong(-2), BigFraction.ZERO, BigFraction.ONE),
            ),
        ).last()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.5), algebraicReals = listOf(root))

        val checked = checkProjectedPolynomialWitness(source, point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `another root of the same polynomial should not replace the source point`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val zero = PolynomialExpression.Constant.of(BigFraction.ZERO.num)
        val source = PolynomialRealProblem(
            PolynomialRealFormula.Atom(x, PolynomialRelation.GT, zero),
            1,
            0,
            1,
            0,
            mapOf("x" to 0),
            emptyMap(),
        )
        val root = AlgebraicArithmetic().roots(
            RationalPolynomial(
                listOf(BigFraction.ofLong(-2), BigFraction.ZERO, BigFraction.ONE),
            ),
        ).first()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(-1.5), algebraicReals = listOf(root))

        val checked = checkProjectedPolynomialWitness(source, point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `source point without a real lowering extension should be rejected`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val auxiliary = PolynomialExpression.Variable(SourceRealId(1))
        val source = PolynomialRealProblem(
            PolynomialRealFormula.Atom(
                PolynomialExpression.Power(auxiliary, 2),
                PolynomialRelation.EQ,
                x,
            ),
            2,
            0,
            1,
            0,
            mapOf("x" to 0),
            emptyMap(),
        )
        val point = Sample(
            booleanArrayOf(),
            longArrayOf(),
            doubleArrayOf(-1.0),
            exactReals = listOf(BigFraction.MINUS_ONE),
        )

        val checked = checkProjectedPolynomialWitness(source, point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `lowering Boolean auxiliaries should stay existential`() {
        val auxiliary = PolynomialRealFormula.BooleanVariable(SourceBoolId(0))
        val source = PolynomialRealProblem(auxiliary, 0, 1, 0, 0, emptyMap(), emptyMap())
        val point = Sample(booleanArrayOf(), longArrayOf())

        val checked = checkProjectedPolynomialWitness(source, point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `extension should not change original Boolean coordinates`() {
        val variable = PolynomialRealFormula.BooleanVariable(SourceBoolId(0))
        val source = PolynomialRealProblem(variable, 0, 1, 0, 1, emptyMap(), mapOf("b" to 0))
        val point = Sample(booleanArrayOf(false), longArrayOf())

        val checked = checkProjectedPolynomialWitness(source, point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `approximate source coordinates should withhold exact checking`() {
        val source = PolynomialRealProblem(
            PolynomialRealFormula.Constant(true),
            1,
            0,
            1,
            0,
            mapOf("x" to 0),
            emptyMap(),
        )
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0))

        val checked = checkProjectedPolynomialWitness(source, point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `checking should charge the containing work allowance without renewing it`() {
        var spent = 0L
        val cancellation = object : Cancellation, WorkMeter {
            override fun isCancelled(): Boolean = spent >= 4L
            override fun workMeter(): WorkMeter = this
            override fun charge(units: Long) {
                spent += units
            }
        }
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val auxiliary = PolynomialExpression.Variable(SourceRealId(1))
        val source = PolynomialRealProblem(
            PolynomialRealFormula.Atom(
                PolynomialExpression.Power(auxiliary, 2),
                PolynomialRelation.EQ,
                x,
            ),
            2,
            0,
            1,
            0,
            mapOf("x" to 0),
            emptyMap(),
        )
        val point = Sample(
            booleanArrayOf(),
            longArrayOf(),
            doubleArrayOf(2.0),
            exactReals = listOf(BigFraction.ofLong(2)),
        )

        val checked = checkProjectedPolynomialWitness(source, point, cancellation)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
        assertTrue(spent >= 4L)
    }
}
