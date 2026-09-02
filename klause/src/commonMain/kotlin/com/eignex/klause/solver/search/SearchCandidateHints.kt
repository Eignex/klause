package com.eignex.klause.solver.search

import com.eignex.klause.util.MutableIntIntMap

/**
 * Unverified branch-order steering produced outside the shared search.
 *
 * A hint is a guess — a local-search assignment, a cached solution, a caller annotation — and carries
 * no proof, so it is never an assumption, a sample, or a model value. Its only effect is which side of
 * a split is tried first. The complement stays on the frame, propagation may fix the variable the other
 * way before the split is reached, and a hint that is missing, contradictory, or inapplicable leaves the
 * default order alone. Completeness therefore never depends on the producer being right, and only the
 * composed [SearchSession] decides what the hinted order actually proves.
 */
fun interface SearchCandidateHints {
    /** Preferred first value for source Boolean [variable], or null when no hint applies to it. */
    fun preferredBool(variable: Int): Boolean?

    /** Supply nothing, the branch order of a search with no hint producer attached. */
    data object None : SearchCandidateHints {
        override fun preferredBool(variable: Int): Boolean? = null
    }

    /** Builders for the partial Boolean hints a producer can express. */
    companion object {
        private const val UNHINTED = 0
        private const val HINTED_TRUE = 1
        private const val HINTED_FALSE = 2
        private const val CONTRADICTED = 3

        /**
         * Hints from encoded Boolean [literals], in the encoding [SearchDecision.Bool] uses.
         *
         * The hint is partial by construction: a producer that assigned only part of the problem passes
         * only what it assigned. Repeating a literal is harmless, while a variable hinted both ways is
         * dropped rather than resolved, since a producer that could not decide a variable is exactly the
         * case the default order already covers.
         */
        fun ofLiterals(literals: IntArray): SearchCandidateHints {
            if (literals.isEmpty()) return None
            val polarity = MutableIntIntMap(literals.size)
            for (literal in literals) {
                val variable = literal ushr 1
                val hint = if (literal and 1 == 0) HINTED_TRUE else HINTED_FALSE
                when (polarity.getOrDefault(variable, UNHINTED)) {
                    UNHINTED, hint -> polarity.put(variable, hint)
                    else -> polarity.put(variable, CONTRADICTED)
                }
            }
            return SearchCandidateHints { variable ->
                when (polarity.getOrDefault(variable, UNHINTED)) {
                    HINTED_TRUE -> true
                    HINTED_FALSE -> false
                    else -> null
                }
            }
        }
    }
}

/**
 * A [BooleanBranching] that tries the hinted polarity of a split first.
 *
 * Ordering is the entire effect. [delegate] still chooses which variable is split and still supplies
 * both of its alternatives, so refuting the hinted branch resumes the ordinary traversal on the
 * complement and the search space is unchanged. A hint applies only to a split that is exactly the two
 * polarities of one Boolean variable; anything else — a component-shaped split, a one-sided list, or a
 * variable [hints] declines — keeps the delegate's own order.
 */
class HintedBooleanBranching(private val delegate: BooleanBranching, private val hints: SearchCandidateHints) :
    BooleanBranching {
    override fun alternatives(context: SearchContext): List<SearchDecision>? {
        val alternatives = delegate.alternatives(context) ?: return null
        if (alternatives.size != 2) return alternatives
        val first = alternatives[0] as? SearchDecision.Bool ?: return alternatives
        val second = alternatives[1] as? SearchDecision.Bool ?: return alternatives
        val variable = first.literal ushr 1
        if (second.literal ushr 1 != variable || first.literal == second.literal) return alternatives
        val preferred = hints.preferredBool(variable) ?: return alternatives
        val preferredLiteral = if (preferred) variable shl 1 else (variable shl 1) or 1
        return if (first.literal == preferredLiteral) alternatives else listOf(second, first)
    }
}

/** Steer this branching with [hints], or keep it as it is when the producer supplied none. */
internal fun BooleanBranching.preferring(hints: SearchCandidateHints): BooleanBranching =
    if (hints === SearchCandidateHints.None) this else HintedBooleanBranching(this, hints)
