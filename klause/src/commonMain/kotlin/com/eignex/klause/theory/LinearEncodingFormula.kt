package com.eignex.klause.theory

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.simplex.exact.BigFraction

/** A private affine value, allocated and defined by one receiving installation scope. */
class EncodingAuxiliary internal constructor(
    internal val owner: EncodingNamespace,
    /** Position in this installation's immutable reconstruction definitions. */
    val index: Int,
)

/** A private existential real coordinate, constrained by one installation's complete formula. */
class EncodingRealAuxiliary internal constructor(
    internal val owner: EncodingNamespace,
    /** Typed private coordinate retained in the installed attribution. */
    val coordinate: TheoryRealId,
)

/** A private Boolean formula name, allocated and defined by one receiving installation scope. */
class EncodingGuard internal constructor(
    internal val owner: EncodingNamespace,
    /** Position in this installation's immutable Boolean definitions. */
    val index: Int,
)

/** An exact term in a private affine definition or comparison. */
class EncodingLinearTerm private constructor(
    /** Tagged source term, absent for a private affine value. */
    val reference: Int?,
    /** Scoped affine value, absent for a tagged source term. */
    val auxiliary: EncodingAuxiliary?,
    /** Private existential real, absent for a source or affine term. */
    val realAuxiliary: EncodingRealAuxiliary?,
    /** Exact coefficient, independent of numerical execution. */
    val coefficient: BigFraction,
) {
    /** Source terms use the typed namespaces represented by [com.eignex.klause.ir.Term]. */
    companion object {
        /** Reference a tagged source coordinate or literal. */
        fun source(reference: Int, coefficient: BigFraction): EncodingLinearTerm =
            EncodingLinearTerm(reference, null, null, coefficient)
        /** Reference an auxiliary allocated by the receiving scope. */
        fun auxiliary(auxiliary: EncodingAuxiliary, coefficient: BigFraction): EncodingLinearTerm =
            EncodingLinearTerm(null, auxiliary, null, coefficient)
        /** Reference an existential real allocated by the receiving scope. */
        fun auxiliary(auxiliary: EncodingRealAuxiliary, coefficient: BigFraction): EncodingLinearTerm =
            EncodingLinearTerm(null, null, auxiliary, coefficient)
    }
}

/** Immutable exact affine expression; private definitions are expanded before consumer installation. */
class EncodingAffineExpression(
    terms: List<EncodingLinearTerm>,
    /** Exact additive constant. */
    val constant: BigFraction = BigFraction.ZERO,
) {
    private val capturedTerms = terms.toList()
    /** Owned term list. */
    val terms: List<EncodingLinearTerm> get() = capturedTerms.toList()
}

/** Complete Boolean structure over exact comparisons, including private formula names. */
sealed class LinearEncodingFormula {
    /** A source-coordinate conjunction or disjunction with its existing reification semantics. */
    class Relation(
        /** Complete relation in source coordinates. */
        val form: LinearForm,
    ) : LinearEncodingFormula()
    /** An affine comparison, with any private terms resolved in this scope. */
    class Comparison(
        /** Exact expression with scoped private terms. */
        val expression: EncodingAffineExpression,
        /** Comparison against the bound. */
        val relation: LinearOp,
        /** Exact right-hand side. */
        val bound: BigFraction,
        /** Whether an inequality excludes equality. */
        val strict: Boolean = false,
    ) : LinearEncodingFormula()
    /** A private guard's complete defining formula. */
    class Guard(
        /** Name allocated and defined by this installation. */
        val guard: EncodingGuard,
    ) : LinearEncodingFormula()
    /** All operands must hold. */
    class And(operands: List<LinearEncodingFormula>) : LinearEncodingFormula() {
        private val values = operands.toTypedArray()
        /** Captured complete operands. */
        val operands: List<LinearEncodingFormula> get() = values.toList()
    }
    /** At least one operand must hold. */
    class Or(operands: List<LinearEncodingFormula>) : LinearEncodingFormula() {
        private val values = operands.toTypedArray()
        /** Captured complete operands. */
        val operands: List<LinearEncodingFormula> get() = values.toList()
    }
}
