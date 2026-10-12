package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.exactInteger
import com.eignex.klause.arithmetic.quadratic.CheckedQuadraticUnbounded
import com.eignex.klause.arithmetic.quadratic.QuadraticCandidates
import com.eignex.klause.arithmetic.quadratic.QuadraticCertification
import com.eignex.klause.arithmetic.quadratic.QuadraticControl
import com.eignex.klause.arithmetic.quadratic.QuadraticModel
import com.eignex.klause.arithmetic.quadratic.QuadraticRow
import com.eignex.klause.arithmetic.quadratic.QuadraticRowRelation
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.times

internal class PolynomialIntegerRecession(
    val formula: PolynomialRealFormula,
    val objective: PolynomialExpression,
    val integers: List<Int>,
    val certificate: CheckedQuadraticUnbounded,
)

internal fun tryPolynomialIntegerRecession(
    formula: PolynomialRealFormula,
    objective: PolynomialExpression,
    assignment: QfNraAssignment,
    integers: List<Int>,
    arithmetic: PolynomialArithmetic,
    control: QuadraticControl,
): PolynomialIntegerRecession? {
    val algebraic = AlgebraicArithmetic(arithmetic)
    val base = assignment.reals.map { value ->
        value.rational ?: if (value.definingCoefficients.size == 2) {
            value.definingCoefficients[0].negated() * value.definingCoefficients[1].reciprocal()
        } else algebraic.exactInteger(value)?.let { BigFraction.of(it, BIG_ONE) } ?: return null
    }
    if (integers.any { base[it].den != BIG_ONE }) return null
    val bools = (0 until assignment.boolCount).associateWith(assignment::boolValue)
    val model = polynomialQuadraticModel(formula, objective, base.size, bools, arithmetic, control) ?: return null
    val rows = model.rows.map { row ->
        arithmetic.step()
        QuadraticRow(row.coefficients, BigFraction.ZERO,
            if (row.relation == QuadraticRowRelation.EQ) QuadraticRowRelation.EQ else QuadraticRowRelation.LE)
    }
    val checker = QuadraticCertification(control)
    val candidates = QuadraticCandidates(control)
    fun certify(direction: List<BigFraction>): PolynomialIntegerRecession? {
        var scale = BIG_ONE
        for (id in integers) { arithmetic.step(); scale *= direction[id].den }
        val scaled = direction.map { arithmetic.step(); it * BigFraction.of(scale, BIG_ONE) }
        val certificate = checker.unbounded(model, base, scaled) ?: return null
        check(integers.all { certificate.direction[it].den == BIG_ONE })
        arithmetic.step()
        return PolynomialIntegerRecession(formula, objective, integers.toList(), certificate)
    }
    val targets = buildList {
        add(model.linear.map { it.negated() })
        for (id in base.indices) for (sign in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            arithmetic.step()
            add(List(base.size) { if (it == id) sign else BigFraction.ZERO })
        }
    }
    for (target in targets) {
        arithmetic.step()
        certify(target)?.let { return it }
        val projection = QuadraticModel(
            target.map { it * BigFraction.ofLong(-2) },
            List(base.size) { i -> List(base.size) { j -> if (i == j) BigFraction.ofLong(2) else BigFraction.ZERO } },
            rows = rows,
        )
        val selections = buildList {
            candidates.proposeActiveRows(projection, target.map(BigFraction::toDouble))?.let(::add)
            add(emptyList())
            for (id in rows.indices.take(64)) add(listOf(id))
        }.distinct()
        for (active in selections) {
            val stationary = candidates.stationary(projection, active) ?: continue
            certify(stationary.anchor)?.let { return it }
        }
    }
    return null
}
