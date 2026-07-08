package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.factor.bool.internals.propagatePbBounds
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

/** CP propagator for [PseudoBoolean]: bounds propagation for `Σ weights_i * lit_i ⟨op⟩ bound`. */
internal class PseudoBooleanPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val weights: LongArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Long,
) : Propagator {

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagatePbBounds(state, weights, literals, op, bound.toLong())

    /** Clause-form nogood when propagation fails: the disjunction of each pinned
     *  literal's false-form. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = 0)
}
