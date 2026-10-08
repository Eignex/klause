package com.eignex.klause.solver.search

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus

// Strict endpoints outside Long remain source predicates; they cannot wrap into a finite CP bound.
@ConsistentCopyVisibility
internal data class SearchIntegerBound private constructor(
    val variable: Int,
    val threshold: Long,
    val upper: Boolean,
    val strict: Boolean = false,
) : SearchTheoryDecision {
    fun complement(): SearchIntegerBound = when {
        strict -> copy(upper = !upper, strict = false)
        upper && threshold < Long.MAX_VALUE -> copy(threshold = threshold + 1L, upper = false)
        !upper && threshold > Long.MIN_VALUE -> copy(threshold = threshold - 1L, upper = true)
        else -> copy(upper = !upper, strict = true)
    }

    fun decision(): SearchDecision? = if (strict) {
        null
    } else if (upper) {
        SearchDecision.IntAtMost(variable, threshold)
    } else {
        SearchDecision.IntAtLeast(variable, threshold)
    }

    fun exactThreshold(): BigInt = if (!strict) {
        bigIntOf(threshold)
    } else if (upper) {
        bigIntOf(threshold) - BIG_ONE
    } else {
        bigIntOf(threshold) + BIG_ONE
    }

    companion object {
        fun of(decision: SearchDecision): SearchIntegerBound? = when (decision) {
            is SearchDecision.IntAtMost -> SearchIntegerBound(decision.variable, decision.upper, upper = true)
            is SearchDecision.IntAtLeast -> SearchIntegerBound(decision.variable, decision.lower, upper = false)
            else -> null
        }?.takeIf { it.variable >= 0 }
    }
}
