package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.lowering.wideDivModRows
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

internal class WideDivModPropagator(private val relation: WideDivMod) : Propagator {
    private val rows = wideDivModRows(relation).map { row ->
        val constants = checkNotNull(row.integralConstants)
        WideLinearPropagator(row.intVars, row.vars, Array(row.vars.size) { constants.exactCoeff(it) },
            row.op, constants.exactBound)
    }
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(relation.intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        rows.all { it.propagate(state, factorId) }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, relation.intVars, excludeIdx = -1, extraLit = 0)
}
