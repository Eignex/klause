package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.factor.bool.internals.propagatePbBounds
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.LearnedPropagator
import com.eignex.klause.propagation.PbAccumulator
import com.eignex.klause.propagation.PropagationState

/** CP propagator for [PseudoBoolean]: bounds propagation for `Σ weights_i * lit_i ⟨op⟩ bound`. */
internal class PseudoBooleanPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val weights: LongArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Long,
) : LearnedPropagator {

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagatePbBounds(state, weights, literals, op, bound)

    /** Clause-form nogood when propagation fails: the disjunction of each pinned
     *  literal's false-form. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = 0)

    /**
     * Load this constraint into [acc] as a coefficient-carrying `≥` reason for pseudo-Boolean
     * cutting-planes conflict analysis (#1119 Phase 3). `GE` loads directly; `LE` flips every literal's
     * polarity and sets the degree to `Σweights − bound`. `EQ` is not a single `≥` reason, so it returns
     * false and the analyzer falls back to the clause-form reason. Also returns false on overflow.
     */
    fun loadReason(acc: PbAccumulator): Boolean = when (op) {
        PbOp.GE -> acc.loadPb(weights, literals, geBound = bound)

        PbOp.LE -> {
            var sum = 0L
            for (w in weights) sum += w
            val flipped = IntArray(literals.size) { literals[it] xor 1 } // toggle the polarity bit
            acc.loadPb(weights, flipped, geBound = sum - bound)
        }

        PbOp.EQ -> false
    }
}
