@file:Suppress("DEPRECATION")

package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchTheoryAtom
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.SearchValueKey
import com.eignex.klause.solver.search.SourceBoundPredicate
import com.eignex.klause.solver.search.SourceBoundAtom as SharedSourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundLimits as SharedSourceBoundLimits
import com.eignex.klause.solver.search.SourceBoundTerm as SharedSourceBoundTerm

/** Compatibility coefficient over a source integer or real variable id. */
@Deprecated("Use solver.search.SourceBoundTerm", ReplaceWith("SourceBoundTerm", "com.eignex.klause.solver.search.SourceBoundTerm"))
data class SourceBoundTerm(
    /** Source coordinate. */
    val source: SearchValueKey,
    /** Exact source coefficient. */
    val coefficient: BigFraction,
)

/** Compatibility limits for exact source normalization. */
@Deprecated("Use solver.search.SourceBoundLimits", ReplaceWith("SourceBoundLimits", "com.eignex.klause.solver.search.SourceBoundLimits"))
data class SourceBoundLimits(
    /** Maximum input terms, including duplicates and zeros. */
    val maxTerms: Int = 256,
    /** Maximum numerator or denominator bits. */
    val maxBits: Int = 4096,
) {
    init {
        require(maxTerms >= 0)
        require(maxBits > 0)
    }
}

/** Compatibility representation of a neutral exact source predicate. */
@Deprecated("Use solver.search.SourceBoundAtom", ReplaceWith("SourceBoundAtom", "com.eignex.klause.solver.search.SourceBoundAtom"))
class SourceBoundAtom private constructor(
    override val sourceBound: SharedSourceBoundAtom,
) : SourceBoundPredicate {
    /** Exact source coefficients, copied from the immutable meaning. */
    val terms: List<SourceBoundTerm> get() = sourceBound.terms.map { SourceBoundTerm(it.source, it.coefficient) }
    /** Exact source threshold. */
    val threshold: BigFraction get() = sourceBound.threshold
    /** True for an upper bound. */
    val upper: Boolean get() = sourceBound.upper
    /** Whether equality is excluded. */
    val strict: Boolean get() = sourceBound.strict

    override fun equals(other: Any?): Boolean = sourceBound == other
    override fun hashCode(): Int = sourceBound.hashCode()

    /** Compatibility constructors retaining canonical registration and exact source semantics. */
    companion object {
        /** Register exhaustive adjacent integer alternatives without narrowing thresholds. */
        fun integerSplit(
            context: SearchContext,
            terms: List<SourceBoundTerm>,
            value: BigFraction,
            constant: BigFraction = BigFraction.ZERO,
            limits: SourceBoundLimits = SourceBoundLimits(),
        ): SearchTheoryAtom? {
            if (terms.size > limits.maxTerms) return null
            return SharedSourceBoundAtom.integerSplit(
                compatibility(context), terms.map { SharedSourceBoundTerm(it.source, it.coefficient) }, value,
                constant, SharedSourceBoundLimits(limits.maxTerms, limits.maxBits),
            )
        }

        /** Register complementary rational alternatives with opposite strictness. */
        fun rationalSplit(
            context: SearchContext,
            terms: List<SourceBoundTerm>,
            threshold: BigFraction,
            strict: Boolean = false,
            constant: BigFraction = BigFraction.ZERO,
            limits: SourceBoundLimits = SourceBoundLimits(),
        ): SearchTheoryAtom? {
            if (terms.size > limits.maxTerms) return null
            return SharedSourceBoundAtom.rationalSplit(
                compatibility(context), terms.map { SharedSourceBoundTerm(it.source, it.coefficient) }, threshold,
                strict, constant, SharedSourceBoundLimits(limits.maxTerms, limits.maxBits),
            )
        }

        private fun compatibility(context: SearchContext): SearchContext = object : SearchContext by context {
            override fun registerAtom(positive: SearchTheoryDecision, negative: SearchTheoryDecision): SearchTheoryAtom? =
                context.registerAtom(
                    SourceBoundAtom(positive as SharedSourceBoundAtom),
                    SourceBoundAtom(negative as SharedSourceBoundAtom),
                )
        }
    }
}
