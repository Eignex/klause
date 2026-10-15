package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.EncodingAffineExpression
import com.eignex.klause.theory.EncodingLinearTerm
import com.eignex.klause.theory.LinearEncodingFormula
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate

internal fun divModLinearFormula(relation: DivMod, divisor: BigInt): LinearEncodingFormula {
    fun row(terms: List<Pair<Int, BigInt>>, op: LinearOp, bound: BigInt = bigIntOf(0)): LinearEncodingFormula =
        LinearEncodingFormula.Comparison(EncodingAffineExpression(terms.map { (v, coefficient) ->
            EncodingLinearTerm.source(Term.ofIntVar(v), BigFraction.of(coefficient, BIG_ONE))
        }), op, BigFraction.of(bound, BIG_ONE))
    fun unary(v: Int, op: LinearOp, bound: BigInt = bigIntOf(0)) = row(listOf(v to BIG_ONE), op, bound)
    if (divisor.isZero()) return row(emptyList(), LinearOp.EQ, BIG_ONE)
    val remainder = relation.remainder
    val limit = divisor.abs() - BIG_ONE
    val rows = arrayListOf(
        row(listOf(relation.dividend to BIG_ONE, relation.quotient to divisor.negate(),
            remainder to BIG_ONE.negate()), LinearOp.EQ),
        unary(remainder, LinearOp.LE, limit),
    )
    relation.divisor?.let { rows += unary(it, LinearOp.EQ, divisor) }
    if (relation.convention == DivisionConvention.EUCLIDEAN) {
        rows += unary(remainder, LinearOp.GE)
    } else {
        rows += unary(remainder, LinearOp.GE, limit.negate())
        rows += LinearEncodingFormula.Or(listOf(
            LinearEncodingFormula.And(listOf(unary(relation.dividend, LinearOp.GE), unary(remainder, LinearOp.GE))),
            LinearEncodingFormula.And(listOf(unary(relation.dividend, LinearOp.LE), unary(remainder, LinearOp.LE))),
        ))
    }
    return LinearEncodingFormula.And(rows)
}

internal fun divModPolynomialFormula(relation: DivMod, integer: (Int) -> PolynomialExpression): PolynomialRealFormula {
    fun constant(value: Long) = PolynomialExpression.Constant.of(bigIntOf(value))
    fun negate(value: PolynomialExpression) = PolynomialExpression.Product(listOf(constant(-1), value))
    fun atom(left: PolynomialExpression, op: PolynomialRelation, right: PolynomialExpression = constant(0)) =
        PolynomialRealFormula.Atom(left, op, right)
    val a = integer(relation.dividend)
    val b = relation.divisor?.let(integer) ?: PolynomialExpression.Constant.of(checkNotNull(relation.constantDivisor))
    val q = integer(relation.quotient)
    val r = integer(relation.remainder)
    val magnitude = PolynomialRealFormula.Or(listOf(
        PolynomialRealFormula.And(listOf(atom(b, PolynomialRelation.GT), atom(r, PolynomialRelation.LT, b),
            atom(r, PolynomialRelation.GT, negate(b)))),
        PolynomialRealFormula.And(listOf(atom(b, PolynomialRelation.LT), atom(r, PolynomialRelation.GT, b),
            atom(r, PolynomialRelation.LT, negate(b)))),
    ))
    val sign = if (relation.convention == DivisionConvention.EUCLIDEAN) atom(r, PolynomialRelation.GE) else
        PolynomialRealFormula.Or(listOf(
            PolynomialRealFormula.And(listOf(atom(a, PolynomialRelation.GE), atom(r, PolynomialRelation.GE))),
            PolynomialRealFormula.And(listOf(atom(a, PolynomialRelation.LE), atom(r, PolynomialRelation.LE))),
        ))
    return PolynomialRealFormula.And(listOf(atom(a, PolynomialRelation.EQ,
        PolynomialExpression.Sum(listOf(PolynomialExpression.Product(listOf(b, q)), r))), magnitude, sign))
}
