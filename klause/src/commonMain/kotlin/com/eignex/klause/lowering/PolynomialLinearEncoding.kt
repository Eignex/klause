package com.eignex.klause.lowering

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.Term
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction

// A non-null [slack] widens the comparison the row states while active, an equality to the band of that width
// around its right-hand side. A false activator still states the exact complement.
internal fun polynomialRow(
    row: LinearRow,
    integer: (Int) -> PolynomialExpression,
    real: (Int) -> PolynomialExpression,
    conditional: (PolynomialRealFormula, PolynomialExpression, PolynomialExpression) -> PolynomialExpression,
    slack: BigFraction? = null,
): PolynomialRealFormula {
    require(slack == null || (slack.signum() >= 0 && row.relation != LinearOp.NE)) {
        "only a nonnegative slack widens a comparison"
    }
    val terms = List(row.size) { index ->
        val ref = row.ref(index)
        val value = when {
            Term.isInt(ref) -> integer(Term.intVar(ref))
            Term.isReal(ref) -> real(Term.realVar(ref))
            else -> {
                val literal = Term.lit(ref)
                val boolean = PolynomialRealFormula.BooleanVariable(SourceBoolId(literal ushr 1))
                val condition = if (literal and 1 == 0) boolean else PolynomialRealFormula.Not(boolean)
                conditional(condition, constant(BigFraction.ONE), constant(BigFraction.ZERO))
            }
        }
        val coefficient = when (val constants = row.constants) {
            is IntegralConstants -> BigFraction.of(constants.exactCoeff(index), BIG_ONE)
            is RealConstants -> requireNotNull(BigFraction.ofDouble(
                if (index < constants.intCoefficients.size) constants.intCoefficients.at(index)
                else constants.realCoefficients.at(index - constants.intCoefficients.size),
            ))
        }
        if (coefficient == BigFraction.ONE) value
        else PolynomialExpression.Product(listOf(constant(coefficient), value))
    }
    val rhs = when (val constants = row.constants) {
        is IntegralConstants -> BigFraction.of(constants.exactBound, BIG_ONE)
        is RealConstants -> requireNotNull(BigFraction.ofDouble(constants.bound))
    }
    val relation = when (row.relation) {
        LinearOp.EQ -> PolynomialRelation.EQ
        LinearOp.LE -> if (row.strict) PolynomialRelation.LT else PolynomialRelation.LE
        LinearOp.GE -> if (row.strict) PolynomialRelation.GT else PolynomialRelation.GE
        LinearOp.NE -> PolynomialRelation.NE
    }
    val expression = if (terms.size == 1) terms.single() else PolynomialExpression.Sum(terms)
    val atom = PolynomialRealFormula.Atom(expression, relation, constant(rhs))
    val active = if (slack == null) atom else when (row.relation) {
        LinearOp.LE -> PolynomialRealFormula.Atom(expression, relation, constant(rhs + slack))
        LinearOp.GE -> PolynomialRealFormula.Atom(expression, relation, constant(rhs - slack))
        else -> PolynomialRealLowering.and(listOf(
            PolynomialRealFormula.Atom(expression, PolynomialRelation.GE, constant(rhs - slack)),
            PolynomialRealFormula.Atom(expression, PolynomialRelation.LE, constant(rhs + slack)),
        ))
    }
    if (row.activator == LinearRow.ALWAYS) return active
    val activator = PolynomialRealFormula.BooleanVariable(SourceBoolId(row.activator))
    return if (slack == null) PolynomialRealLowering.equivalent(activator, atom)
    else PolynomialRealLowering.conditional(activator, active, PolynomialRealLowering.not(atom))
}

private fun constant(value: BigFraction): PolynomialExpression = PolynomialRealLowering.constant(value)
