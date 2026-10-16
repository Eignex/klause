package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal fun Compiler.Builder.retainDeclarationBounds() {
    visitSmtDeclarationBounds(semantics, intNames.values, realNames.values, sourceCancellation) { id, real, lower, value ->
        factors.add(ExactLinear.of(
            intArrayOf(if (real) Term.ofRealVar(id) else Term.ofIntVar(id)),
            listOf(BigFraction.ONE), if (lower) LinearOp.GE else LinearOp.LE,
            BigFraction.of(value.numerator, value.denominator),
        ))
    }
}

internal fun smtPolynomialDeclarationBounds(
    semantics: EffectiveSourceSemantics,
    integers: Collection<Int>,
    reals: Collection<Int>,
    cancellation: Cancellation,
): List<PolynomialRealFormula> {
    val formulas = ArrayList<PolynomialRealFormula>()
    visitSmtDeclarationBounds(semantics, integers, reals, cancellation) { id, _, lower, value ->
        formulas.add(PolynomialRealFormula.Atom(
            PolynomialExpression.Variable(SourceRealId(id)),
            if (lower) PolynomialRelation.GE else PolynomialRelation.LE,
            PolynomialExpression.Constant.of(value.numerator, value.denominator),
        ))
    }
    return formulas
}

private inline fun visitSmtDeclarationBounds(
    semantics: EffectiveSourceSemantics,
    integers: Collection<Int>,
    reals: Collection<Int>,
    cancellation: Cancellation,
    bound: (Int, Boolean, Boolean, SemanticRational) -> Unit,
) {
    val integerBounds = requireNotNull(semantics.effective.undeclaredIntegers)
    val realBounds = requireNotNull(semantics.effective.undeclaredReals)
    for (id in integers) {
        cancellation.polynomialSourceCheckpoint()
        integerBounds.lower?.let { bound(id, false, true, SemanticRational.ofLong(it)) }
        integerBounds.upper?.let { bound(id, false, false, SemanticRational.ofLong(it)) }
    }
    for (id in reals) {
        cancellation.polynomialSourceCheckpoint()
        realBounds.lower?.let { bound(id, true, true, it) }
        realBounds.upper?.let { bound(id, true, false, it) }
    }
}
