package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.simplex.exact.BigFraction

internal fun Compiler.Builder.assertDistinct(args: List<SExpr>) {
    val values = args.map { argument ->
        sourceCancellation.polynomialSourceCheckpoint(1)
        evalTerm(argument, when {
            isBoolExpr(argument) -> Sort.BOOL
            isRealExpr(argument) -> Sort.REAL
            else -> Sort.INT
        })
    }
    if (values.any { it is Res.B } && values.any { it !is Res.B }) {
        smtUnsupported("distinct requires operands of the same sort")
    }
    val variables = values.mapNotNull { value ->
        ((value as? Res.I)?.term as? IntComb.Narrow)?.lin?.asSimpleVar()
    }
    if (values.size < 2) return
    if (variables.size == values.size) {
        factors.add(AllDifferent(variables.toIntArray()))
    } else {
        factors.add(Distinct(values.map { distinctOperand(it) }))
    }
}

internal fun Compiler.Builder.distinctFromArgs(args: List<Res>): Int {
    if (args.any { it is Res.B } && args.any { it !is Res.B }) {
        smtUnsupported("distinct requires operands of the same sort")
    }
    if (args.size < 2) return trueLit()
    val variable = newBool()
    val factor = Distinct(args.map { distinctOperand(it) }, variable)
    factors.add(factor)
    conditionalEqualities.definePredicate(variable, listOf(factor))
    return Lit.make(variable, true)
}

private fun Compiler.Builder.distinctOperand(value: Res): Distinct.Operand {
    sourceCancellation.polynomialSourceCheckpoint(1)
    val term = when (value) {
        is Res.B -> return Distinct.Operand(intArrayOf(Term.ofLit(value.lit)), listOf(BigFraction.ONE))
        is Res.R -> value.comb
        is Res.I -> when (val integer = value.term) {
            is IntComb.Narrow -> integer.lin.toRealComb()
            is IntComb.Wide -> integer.lin.toRealComb()
        }
    }
    val references = ArrayList<Int>()
    val coefficients = ArrayList<BigFraction>()
    for ((variable, coefficient) in term.intCoeffs) {
        sourceCancellation.polynomialSourceCheckpoint(1)
        references += Term.ofIntVar(variable)
        coefficients += coefficient
    }
    for ((variable, coefficient) in term.realCoeffs) {
        sourceCancellation.polynomialSourceCheckpoint(1)
        references += Term.ofRealVar(variable)
        coefficients += coefficient
    }
    return Distinct.Operand(references.toIntArray(), coefficients, term.constant)
}
