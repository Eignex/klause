package com.eignex.klause.lowering

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.EncodingAffineExpression
import com.eignex.klause.theory.EncodingLinearTerm
import com.eignex.klause.theory.LinearEncodingFormula
import com.eignex.klause.util.Cancellation

internal fun allDifferentLinearFormula(
    factor: AllDifferent,
    cancellation: Cancellation,
): LinearEncodingFormula? {
    if (cancellation()) return null
    fun comparison(terms: List<Pair<Int, BigFraction>>, op: LinearOp, bound: BigFraction) =
        LinearEncodingFormula.Comparison(EncodingAffineExpression(terms.map { (term, coefficient) ->
            EncodingLinearTerm.source(term, coefficient)
        }), op, bound)
    val clauses = ArrayList<LinearEncodingFormula>()
    for (i in factor.vars.indices) {
        for (j in i + 1 until factor.vars.size) {
            cancellation.charge(1L + factor.exceptSet.size)
            if (cancellation()) return null
            val a = Term.ofIntVar(factor.vars[i])
            val b = Term.ofIntVar(factor.vars[j])
            val choices = arrayListOf<LinearEncodingFormula>(comparison(
                listOf(a to BigFraction.ONE, b to BigFraction.ONE.negated()), LinearOp.NE, BigFraction.ZERO,
            ))
            for (position in intArrayOf(i, j)) {
                if (factor.presents.isNotEmpty()) choices += comparison(
                    listOf(Term.ofLit(factor.presents[position]) to BigFraction.ONE), LinearOp.EQ, BigFraction.ZERO,
                )
            }
            for (value in factor.exceptSet) choices += comparison(
                listOf(a to BigFraction.ONE), LinearOp.EQ, BigFraction.ofLong(value),
            )
            clauses += LinearEncodingFormula.Or(choices)
        }
    }
    return LinearEncodingFormula.And(clauses)
}

internal fun allDifferentPolynomialFormula(
    factor: AllDifferent,
    integer: (Int) -> PolynomialExpression,
    cancellation: Cancellation,
): PolynomialRealFormula? {
    if (cancellation()) return null
    val clauses = ArrayList<PolynomialRealFormula>()
    for (i in factor.vars.indices) {
        for (j in i + 1 until factor.vars.size) {
            cancellation.charge(1L + factor.exceptSet.size)
            if (cancellation()) return null
            val a = integer(factor.vars[i])
            val b = integer(factor.vars[j])
            val choices = arrayListOf<PolynomialRealFormula>(PolynomialRealFormula.Atom(a, PolynomialRelation.NE, b))
            for (position in intArrayOf(i, j)) {
                if (factor.presents.isEmpty()) continue
                val literal = factor.presents[position]
                val boolean = PolynomialRealFormula.BooleanVariable(SourceBoolId(Lit.variable(literal)))
                choices += if (Lit.isPositive(literal)) PolynomialRealFormula.Not(boolean) else boolean
            }
            for (value in factor.exceptSet) choices += PolynomialRealFormula.Atom(
                a, PolynomialRelation.EQ, PolynomialRealLowering.constant(BigFraction.ofLong(value)),
            )
            clauses += PolynomialRealFormula.Or(choices)
        }
    }
    return PolynomialRealFormula.And(clauses)
}
