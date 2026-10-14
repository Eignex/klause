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
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE

internal fun polynomialRow(
    row: LinearRow,
    integer: (Int) -> PolynomialExpression,
    real: (Int) -> PolynomialExpression,
    conditional: (PolynomialRealFormula, PolynomialExpression, PolynomialExpression) -> PolynomialExpression,
): PolynomialRealFormula {
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
        PolynomialExpression.Product(listOf(constant(coefficient), value))
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
    val atom = PolynomialRealFormula.Atom(PolynomialExpression.Sum(terms), relation, constant(rhs))
    return if (row.activator == LinearRow.ALWAYS) atom else PolynomialRealLowering.equivalent(
        PolynomialRealFormula.BooleanVariable(SourceBoolId(row.activator)), atom,
    )
}

private fun constant(value: BigFraction): PolynomialExpression = PolynomialRealLowering.constant(value)
