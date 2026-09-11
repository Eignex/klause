package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchTheoryAtom
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.SearchValueKey
import com.ionspin.kotlin.bignum.integer.BigInteger

/** One exact coefficient over an existing source integer or real variable id. */
data class SourceBoundTerm(
    /** A [SearchIntValue] or [SearchRealValue]; other source kinds decline registration. */
    val source: SearchValueKey,
    /** Exact coefficient in source coordinates. */
    val coefficient: BigFraction,
)

/** Bounded normalization effort for source expressions and exact thresholds. */
data class SourceBoundLimits(
    /** Maximum input terms, including duplicates and zeros. */
    val maxTerms: Int = 256,
    /** Maximum numerator or denominator bits in inputs and normalized results. */
    val maxBits: Int = 4096,
) {
    init {
        require(maxTerms >= 0)
        require(maxBits > 0)
    }
}

/**
 * An immutable source-linear bound, independent of LP columns, transformations and finite CP domains.
 *
 * Terms are combined and sorted by source kind/id; constants are moved into [threshold]. Structural
 * equality is canonical within that convention, without identifying positive scalar multiples.
 * The caller verifies that typed source ids belong to the session's immutable source model.
 */
class SourceBoundAtom private constructor(
    private val sourceTerms: List<SourceBoundTerm>,
    /** Exact bound on the source-linear activity. */
    val threshold: BigFraction,
    /** True for an upper bound; false for a lower bound. */
    val upper: Boolean,
    /** Whether equality at the threshold is excluded. */
    val strict: Boolean,
) : SearchTheoryDecision {
    /** Source coefficients, returned as a copy to preserve registered meaning. */
    val terms: List<SourceBoundTerm> get() = sourceTerms.toList()

    override fun equals(other: Any?): Boolean = other is SourceBoundAtom &&
        sourceTerms == other.sourceTerms && threshold == other.threshold && upper == other.upper &&
        strict == other.strict

    override fun hashCode(): Int = ((sourceTerms.hashCode() * 31 + threshold.hashCode()) * 31 + upper.hashCode()) *
        31 + strict.hashCode()

    /** Exact source branch constructors; unsupported inputs and resource limits decline atomically. */
    companion object {
        /**
         * Register `activity + constant <= floor(value)` and `activity + constant >= floor(value) + 1`.
         *
         * Every retained coefficient and the constant must be integral, and every retained variable
         * must be a source integer. This syntactic lattice proof makes the alternatives exhaustive;
         * arbitrary transformed rational terms can instead use [rationalSplit]. No narrowing occurs.
         */
        fun integerSplit(
            context: SearchContext,
            terms: List<SourceBoundTerm>,
            value: BigFraction,
            constant: BigFraction = BigFraction.ZERO,
            limits: SourceBoundLimits = SourceBoundLimits(),
        ): SearchTheoryAtom? {
            if (!value.fits(limits) || !constant.fits(limits) || constant.den != BigInteger.ONE) return null
            val normalized = normalize(terms, limits) ?: return null
            if (normalized.any { it.source !is SearchIntValue || it.coefficient.den != BigInteger.ONE }) return null
            val quotient = value.num / value.den
            val floor = if (value.num < BigInteger.ZERO && value.num % value.den != BigInteger.ZERO) {
                quotient - BigInteger.ONE
            } else {
                quotient
            }
            val upper = BigFraction.of(floor, BigInteger.ONE) - constant
            val lower = upper + BigFraction.ONE
            if (!upper.fits(limits) || !lower.fits(limits)) return null
            return context.registerAtom(
                SourceBoundAtom(normalized, upper, upper = true, strict = false),
                SourceBoundAtom(normalized, lower, upper = false, strict = false),
            )
        }

        /** Register an exact rational upper bound and its lower complement with opposite strictness. */
        fun rationalSplit(
            context: SearchContext,
            terms: List<SourceBoundTerm>,
            threshold: BigFraction,
            strict: Boolean = false,
            constant: BigFraction = BigFraction.ZERO,
            limits: SourceBoundLimits = SourceBoundLimits(),
        ): SearchTheoryAtom? {
            if (!threshold.fits(limits) || !constant.fits(limits)) return null
            val normalized = normalize(terms, limits) ?: return null
            val shifted = threshold - constant
            if (!shifted.fits(limits)) return null
            return context.registerAtom(
                SourceBoundAtom(normalized, shifted, upper = true, strict = strict),
                SourceBoundAtom(normalized, shifted, upper = false, strict = !strict),
            )
        }

        private fun normalize(terms: List<SourceBoundTerm>, limits: SourceBoundLimits): List<SourceBoundTerm>? {
            if (terms.size > limits.maxTerms) return null
            val coefficients = HashMap<SearchValueKey, BigFraction>()
            for (term in terms) {
                val variable = when (val source = term.source) {
                    is SearchIntValue -> source.variable
                    is SearchRealValue -> source.variable
                    else -> return null
                }
                if (variable < 0 || !term.coefficient.fits(limits)) return null
                val sum = (coefficients[term.source] ?: BigFraction.ZERO) + term.coefficient
                if (!sum.fits(limits)) return null
                if (sum.isZero) coefficients.remove(term.source) else coefficients[term.source] = sum
            }
            return coefficients.map { SourceBoundTerm(it.key, it.value) }.sortedWith(
                compareBy<SourceBoundTerm> { it.source is SearchRealValue }.thenBy {
                    when (val source = it.source) {
                        is SearchIntValue -> source.variable
                        is SearchRealValue -> source.variable
                        else -> error("only scalar source variables are normalized")
                    }
                },
            )
        }

        private fun BigFraction.fits(limits: SourceBoundLimits): Boolean =
            num.bitLength() <= limits.maxBits && den.bitLength() <= limits.maxBits
    }
}
