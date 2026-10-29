package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceScope

/** Exact authority and coordinate identity for Boolean coverage observations and tasks. */
data class BooleanProofContext(
    /** Original source, complete caller restrictions and objective direction. */
    val scope: SourceScope,
    /** Identity of the prepared Boolean coordinate mapping, including its generation. */
    val mapping: String,
    /** Strict improving cutoff in original objective units; null means unrestricted. */
    val cutoff: BigFraction? = null,
) {
    init {
        require(mapping.isNotBlank())
        require(cutoff == null || scope.objective != null)
    }
}

/** Immutable conjunction in one context's Boolean coordinates. Contradictory pins are rejected. */
class BooleanCube(literals: List<Int>) {
    private val pins = literals.also { require(it.size <= 4096) }.distinct().sorted()

    init {
        require(pins.size <= 4096 && pins.all { it >= 0 })
        require(pins.zipWithNext().none { (a, b) -> Lit.variable(a) == Lit.variable(b) })
    }

    /** Canonical literals, owned by the caller of this accessor. */
    val literals: List<Int> get() = pins.toList()

    override fun equals(other: Any?): Boolean = other is BooleanCube && pins == other.pins
    override fun hashCode(): Int = pins.hashCode()
}
