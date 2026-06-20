package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.solver.factor.bool.internals.propagatePbBounds
import com.eignex.klause.solver.propagation.PropagationState

/** CP contract for [PseudoBoolean]: bounds propagation for `Σ weights_i * lit_i ⟨op⟩ bound`. */
interface PseudoBooleanPropagator : Propagator {

    /** Weights, parallel to [literals]. */
    val weights: IntArray

    /** Boolean literals contributing their weight when true. */
    val literals: IntArray

    /** Relation between the weighted sum and [bound]. */
    val op: PbOp

    /** Right-hand-side bound. */
    val bound: Int

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagatePbBounds(state, weights, literals, op, bound.toLong())

    /** Clause-form nogood when propagation fails: the disjunction of each pinned
     *  literal's false-form. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = 0)
}
