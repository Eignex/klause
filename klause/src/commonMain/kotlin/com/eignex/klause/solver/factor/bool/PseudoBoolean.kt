package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits

/**
 * `Σ weights(i) * lit(i) ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Payload at `intPayload(factorId)` is the current weighted sum. Terms pair
 * [weights] with [literals]; the sum is compared by [op] against [bound].
 */
class PseudoBoolean(val weights: IntArray, val literals: IntArray, val op: PbOp, val bound: Int) : Factor {

    override val intVars: IntArray = EmptyIntArray

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.PSEUDO_BOOLEAN) {
        enum(op)
        int(bound)
        pairsByKey(literals) { weights[it].toLong() }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        PseudoBoolean(weights, literals.remapLits(boolMap), op, bound)

    override val boolVars: IntArray = literals.litVars()

    override fun asPropagator(): Propagator = PseudoBooleanPropagator(boolVars, intVars, weights, literals, op, bound)

    override fun asInvariant(): Invariant = PseudoBooleanInvariant(boolVars, weights, literals, op, bound)

    override fun asLinearizer(): Linearizer = PseudoBooleanLinearizer(weights, literals, op, bound)
}
