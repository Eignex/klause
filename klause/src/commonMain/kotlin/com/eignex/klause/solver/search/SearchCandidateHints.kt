package com.eignex.klause.solver.search

import com.eignex.util.collections.MutableIntIntMap

/**
 * Unverified branch-order steering from candidate producers such as local search, partial theory points
 * or cached solutions. A hint only chooses which side of a split is tried first; it is never an
 * assumption or a model value, so completeness never depends on it being right.
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
         * Hints from encoded Boolean [literals], in the encoding [SearchDecision.Bool] uses. A variable
         * hinted both ways is left unhinted.
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
 * A [BooleanBranching] that tries the hinted polarity of a split first. [delegate] still chooses the
 * variable and both alternatives; only a split over exactly the two polarities of one Boolean variable is
 * reordered.
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

internal fun BooleanBranching.preferring(hints: SearchCandidateHints): BooleanBranching =
    if (hints === SearchCandidateHints.None) this else HintedBooleanBranching(this, hints)

internal fun SearchCandidateHints.followedBy(fallback: SearchCandidateHints): SearchCandidateHints = when {
    this === SearchCandidateHints.None -> fallback
    fallback === SearchCandidateHints.None -> this
    else -> SearchCandidateHints { variable -> preferredBool(variable) ?: fallback.preferredBool(variable) }
}
