package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.wideEnforceRow
import com.eignex.klause.factor.arithmetic.internals.wideSideReason
import com.eignex.klause.factor.arithmetic.internals.wideSumRange
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo

// Exact BigInt arithmetic enforces the row with no overflow degrade. This is the only CP reader of the wide
// coefficients; they never reach the domains or the trail.
internal class WideLinearPropagator(
    val intVars: IntArray,
    val vars: IntArray,
    val coeffs: Array<BigInt>,
    val op: LinearOp,
    val bound: BigInt,
) : Propagator {

    // Interval reasoning reads only bounds.
    override val initialIntEventWatches: IntArray = IntArray(vars.size * 2).also { out ->
        var w = 0
        for (v in vars) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        wideEnforceRow(state, vars, coeffs, op, bound, auxLit = null)

    // The row fails on one side of its activity range; NE fails with every term pinned, so it reads both.
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val (sumLo, sumHi) = wideSumRange(state, vars, coeffs)
        val useLo = when (op) {
            LinearOp.LE -> true
            LinearOp.GE -> false
            LinearOp.EQ -> sumLo > bound
            LinearOp.NE -> return collectLinearTightenAntecedents(state, vars, excludeIdx = -1, extraLit = 0)
        }
        if (!useLo && op == LinearOp.EQ && sumHi >= bound) return collectLinearTightenAntecedents(state, vars, -1, 0)
        return wideSideReason(state, vars, coeffs, useLo, -1, null)
    }
}
