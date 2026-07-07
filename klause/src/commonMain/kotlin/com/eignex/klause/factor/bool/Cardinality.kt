package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.litVars
import com.eignex.klause.factor.remapLits
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray

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

    // `min == 0 && max == literals.size` accepts every assignment of the literals, so the constraint
    // is vacuous and drops (propagation never prunes it but keeps the factor around otherwise).
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction =
        if (min == 0 && max == literals.size) FactorReduction.Rewrite(emptyList()) else FactorReduction.Unchanged

    override val boolVars: IntArray = literals.litVars()

    override val extendsObjectiveCone: Boolean = true

    override fun asPropagator(): Propagator = CardinalityPropagator(boolVars, intVars, literals, min, max)

    override fun asInvariant(): Invariant = CardinalityInvariant(boolVars, literals, min, max)

    /** LP relaxation: the feasibility-defining bounds `min ≤ Σ literals ≤ max`. */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = min.toLong())
        builder.boolRow(literals, weights = null, op = LinearOp.LE, bound = max.toLong())
    }

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
