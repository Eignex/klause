package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.RealToleranceScope
import com.eignex.klause.solver.validation.readsAsRows
import com.eignex.klause.solver.validation.readsSlack
import com.eignex.klause.solver.validation.slackCoordinates
import com.eignex.klause.util.ExecutionControl

// The tolerance a polynomial encoding of [source] states. Each declaration and relation that [RealToleranceScope]
// reads within the slack is widened by the absolute slack the source declares: an active non-strict row by that
// slack around its right-hand side, and `r = f(x)` by stating `r' = f(x)` over a private `r'` with
// `abs(r - r') <= slack`. Every other relation and declaration stays exact. The scope's slack is never below the
// absolute one, so the widened region lies inside the source acceptance relation by relation, and a refutation of
// the widened formula refutes every point within the absolute slack.
internal class PolynomialTolerance private constructor(
    private val source: Problem,
    private val policy: SourceTolerance,
    private val scope: RealToleranceScope,
    private val slack: BigFraction,
) {
    // Whether this encoding widens [factor] rather than stating it exactly.
    fun widens(factor: Factor): Boolean {
        val reals = factor.variables.reals
        return reals.isNotEmpty() && factor !is Distinct && !scope.isExact(reals[0])
    }

    // The slack of real declaration [id], or null when it holds exactly.
    fun declaration(id: Int): BigFraction? = if (scope.isExact(id)) null else slack

    // The coordinates of widened [factor] that its exact encoding states over private reals, or null when its rows
    // are widened in place. A relation whose read coordinate also states an operand has neither and holds exactly.
    fun substitutions(factor: Factor): IntArray? = factor.slackCoordinates()

    // Whether widened [factor] is read as rows, so that [rows] states it.
    fun readsRows(factor: Factor): Boolean = factor.readsAsRows

    // [factor]'s rows with every active non-strict row widened by the slack.
    fun rows(factor: Factor, encoding: PolynomialEncodingContext): PolynomialRealFormula {
        fun row(row: LinearRow) = polynomialRow(row, { encoding.integer(SourceIntId(it)) },
            { encoding.real(SourceRealId(it)) }, encoding::conditional, slack.takeIf { row.readsSlack })
        if (factor is LinearIndicator) {
            val inactive = PolynomialRealFormula.Atom(encoding.integer(SourceIntId(factor.indicator)),
                PolynomialRelation.NE, PolynomialRealLowering.constant(
                    BigFraction.ofLong(if (factor.whenOne) 1 else 0)))
            return PolynomialRealFormula.Or(listOf(inactive, row(factor.row)))
        }
        val form = checkNotNull(factor.linearForm)
        val rows = form.rows.map(::row)
        return if (form is LinearForm.Disjunction) PolynomialRealFormula.Or(rows) else PolynomialRealFormula.And(rows)
    }

    // `abs(value - exact) <= slack`.
    fun band(value: PolynomialExpression, exact: PolynomialExpression): PolynomialRealFormula {
        val residual = PolynomialRealLowering.sum(listOf(value, PolynomialRealLowering.negate(exact)))
        return PolynomialRealFormula.And(listOf(
            PolynomialRealFormula.Atom(residual, PolynomialRelation.LE, PolynomialRealLowering.constant(slack)),
            PolynomialRealFormula.Atom(residual, PolynomialRelation.GE,
                PolynomialRealLowering.constant(slack.negated())),
        ))
    }

    // The source acceptance of a point with rational real coordinates, the one the finite lane applies.
    fun accepts(sample: Sample, cancellation: ExecutionControl): Verification<Sample, Unit> =
        scope.verify(source, sample, policy, cancellation, integerRows = false)

    companion object {
        // The widening [source] declares, or null when it declares no absolute slack or no relation reads it.
        fun of(source: Problem): PolynomialTolerance? {
            val policy = source.semantics.effective.feasibilityTolerance
            if (policy == null || policy.isExact || source.numRealVars == 0) return null
            val slack = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator)
            if (slack.isZero) return null
            val scope = RealToleranceScope.of(source)
            return if (scope.readsSlack) PolynomialTolerance(source, policy, scope, slack) else null
        }
    }
}
