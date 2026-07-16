package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.factor.bool.internals.propagatePbBounds
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.LearnedPropagator
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
}
