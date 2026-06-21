package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits

/**
 * `min ≤ (#true literals) ≤ max`. Payload at `longPayload(factorId)` is the count of true
 * literals. AtMostOne, AtLeastOne, ExactlyOne are special cases.
 */
class Cardinality(literals: IntArray, min: Int, max: Int) :
    CardinalitySumFactor(literals, min, max, excludedVar = -1) {

    override fun structuralKey(): String = "card:$min:$max:" + literals.sorted().joinToString(",")

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Cardinality(literals.remapLits(boolMap), min, max)

    override val boolVars: IntArray = literals.litVars()

    override fun asPropagator(): Propagator = CardinalityPropagator(boolVars, intVars, literals, min, max)

    override fun asInvariant(): Invariant = CardinalityInvariant(boolVars, intVars, literals, min, max)

    /** Factory methods for this factor. */
    companion object {
        /** At-most-one: at most one of [literals] is true. */
        fun atMostOne(literals: IntArray): Cardinality = Cardinality(literals, min = 0, max = 1)

        /** At-least-one: at least one of [literals] is true. */
        fun atLeastOne(literals: IntArray): Cardinality = Cardinality(literals, min = 1, max = literals.size)

        /** Exactly-one: exactly one of [literals] is true. */
        fun exactlyOne(literals: IntArray): Cardinality = Cardinality(literals, min = 1, max = 1)
    }
}
