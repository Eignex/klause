package com.eignex.klause.schema

import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.ContinuousRealEquality
import com.eignex.klause.model.ContinuousRealExpr

/** Capture this affine float term as an exact continuous source expression. */
fun FloatTerm.toContinuousRealExpr(): ContinuousRealExpr {
    val expression = toFloatExpr()
    require(expression.terms.keys.map { it.name }.distinct().size == expression.terms.size) {
        "Continuous expressions require unique source names"
    }
    return ContinuousRealExpr.Affine(expression.terms.mapKeys { it.key.name }, expression.offset)
}

/** Exact continuous product, including squares. */
operator fun FloatTerm.times(other: FloatTerm): ContinuousRealExpr =
    ContinuousRealExpr.Multiply(toContinuousRealExpr(), other.toContinuousRealExpr())

/** Append a continuous operand to a product chain. */
operator fun ContinuousRealExpr.times(other: FloatTerm): ContinuousRealExpr = this * other.toContinuousRealExpr()

/** Prepend a continuous operand to a product chain. */
operator fun FloatTerm.times(other: ContinuousRealExpr): ContinuousRealExpr = toContinuousRealExpr() * other

/** Exact multiplication of continuous source expressions. */
operator fun ContinuousRealExpr.times(other: ContinuousRealExpr): ContinuousRealExpr =
    ContinuousRealExpr.Multiply(this, other)

/** Exact multiplication by a finite binary64 rational constant. */
operator fun ContinuousRealExpr.times(constant: Double): ContinuousRealExpr =
    this * ContinuousRealExpr.Affine(emptyMap(), constant)

/** Exact top-level equality of continuous expressions. */
infix fun ContinuousRealExpr.eq(other: ContinuousRealExpr): BoolExpr = ContinuousRealEquality(this, other)

/** Exact top-level equality to an affine float term. */
infix fun ContinuousRealExpr.eq(other: FloatTerm): BoolExpr = this eq other.toContinuousRealExpr()

/** Exact top-level equality to a finite binary64 rational constant. */
infix fun ContinuousRealExpr.eq(constant: Double): BoolExpr = this eq ContinuousRealExpr.Affine(emptyMap(), constant)

/** Exact top-level equality of an affine term and a product. */
infix fun FloatTerm.eq(other: ContinuousRealExpr): BoolExpr = toContinuousRealExpr() eq other
