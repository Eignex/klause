package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.litVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.BoolVars
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.IntHashSet

/**
 * `[min] ≤ (#true [literals]) ≤ [max]`. Payload at `longPayload(factorId)` is the count of true
 * literals. AtMostOne, AtLeastOne, ExactlyOne are special cases.
 */
class Cardinality(literals: IntArray, val min: Int, val max: Int) : Factor {

    // Factors may outlive a frontend's scratch buffer.
    val literals: IntArray = literals.copyOf()

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
        val variables = IntHashSet(literals.size)
        require(literals.all { variables.add(com.eignex.klause.solver.Lit.variable(it)) }) {
            "Cardinality literals must reference distinct Boolean variables"
        }
    }

    override val variables: VarList = BoolVars(literals.litVars())

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.CARDINALITY, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.CARDINALITY, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.int(min)
        sink.int(max)
        sink.sortedBoolLits(literals)
    }

    override fun remap(mapping: VarRemap): Factor = Cardinality(mapping.lits(literals), min, max)

    // `min == 0 && max == literals.size` accepts every assignment of the literals, so the constraint
    // is vacuous and drops (propagation never prunes it but keeps the factor around otherwise).
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction =
        if (min == 0 && max == literals.size) FactorReduction.Rewrite(emptyList()) else FactorReduction.Unchanged

    override val extendsObjectiveCone: Boolean = true

    override fun asPropagator(): Propagator = CardinalityPropagator(boolVars, intVars, literals, min, max)

    override fun asInvariant(): Invariant = CardinalityInvariant(boolVars, literals, min, max)

    /** LP relaxation: the feasibility-defining bounds `min ≤ Σ literals ≤ max`. */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = min.toLong())
        builder.boolRow(literals, weights = null, op = LinearOp.LE, bound = max.toLong())
    }

    /** Exact linear view: the bounds `min ≤ Σ literals ≤ max` over its Boolean literals (unit-weight views). */
    override val linearRows: List<LinearRow>
        get() = listOf(
            LinearRow.ofBools(literals, LinearOp.GE, min.toLong()),
            LinearRow.ofBools(literals, LinearOp.LE, max.toLong()),
        )

    /** Factory methods for this factor. */
    companion object {
        /** At-most-one: at most one of [literals] is true; an empty input is vacuously true. */
        fun atMostOne(literals: IntArray): Cardinality = Cardinality(literals, min = 0, max = minOf(1, literals.size))

        /** At-least-one: at least one of [literals] is true. */
        fun atLeastOne(literals: IntArray): Cardinality = Cardinality(literals, min = 1, max = literals.size)

        /** Exactly-one: exactly one of [literals] is true. */
        fun exactlyOne(literals: IntArray): Cardinality = Cardinality(literals, min = 1, max = 1)
    }
}
