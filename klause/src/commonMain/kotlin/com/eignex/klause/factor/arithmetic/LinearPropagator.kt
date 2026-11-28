package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearDirAntecedents
import com.eignex.klause.factor.arithmetic.internals.collectLinearLiftedAntecedents
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.explainLinearBound
import com.eignex.klause.factor.arithmetic.internals.linearSumRange
import com.eignex.klause.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

internal class LinearPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    val coeffs: LongArray,
    val vars: IntArray,
    val op: LinearOp,
    val bound: Long,
) : Propagator {

    // Propagation reads only bounds, so interior VALUE_REMOVED wakes are skipped. Terms are coalesced,
    // so each variable is watched once.
    override val initialIntEventWatches: IntArray = IntArray(vars.size * 2).also { out ->
        var w = 0
        for (v in vars) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagateLinearBounds(state, coeffs, vars, op, bound, factorId = factorId)

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        explainLinearBound(state, coeffs, vars, payload, atTrail, atLevel)

    // Exactly one sum extreme breaches `bound`, so citing only that side's bounds, weakened by the excess,
    // gives a sharper nogood. NE needs both bounds of every variable.
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        if (op == LinearOp.NE) return collectLinearTightenAntecedents(state, vars, excludeIdx = -1, extraLit = 0)
        val range = linearSumRange(state, coeffs, vars) // [sumLo, sumHi]
        if (range[0] == Long.MIN_VALUE && range[1] == Long.MAX_VALUE) {
            // Overflow hides the breaching side, so cite both sides of every variable.
            return collectLinearTightenAntecedents(state, vars, excludeIdx = -1, extraLit = 0)
        }
        val useLo = when (op) {
            LinearOp.LE -> true
            LinearOp.GE -> false
            else -> range[0] > bound
        }
        val slack = if (useLo) range[0] - bound - 1 else bound - range[1] - 1
        return if (slack < 0) {
            collectLinearDirAntecedents(state, coeffs, vars, excludeIdx = -1, extraLit = 0, useLo = useLo)
        } else {
            collectLinearLiftedAntecedents(state, coeffs, vars, useLo, slack)
        }
    }
}
