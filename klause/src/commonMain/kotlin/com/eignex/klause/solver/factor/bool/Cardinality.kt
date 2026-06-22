package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits

/**
 * `[min] ≤ (#true [literals]) ≤ [max]`. Payload at `longPayload(factorId)` is the count of true
 * literals. AtMostOne, AtLeastOne, ExactlyOne are special cases.
 */
class Cardinality(val literals: IntArray, val min: Int, val max: Int) : Factor {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
    }

    override val intVars: IntArray = EmptyIntArray

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.CARDINALITY) {
        int(min)
        int(max)
        sortedInts(literals)
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Cardinality(literals.remapLits(boolMap), min, max)

    override val boolVars: IntArray = literals.litVars()

    override fun asPropagator(): Propagator = CardinalityPropagator(boolVars, intVars, literals, min, max)

    override fun asInvariant(): Invariant = CardinalityInvariant(boolVars, literals, min, max)

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
