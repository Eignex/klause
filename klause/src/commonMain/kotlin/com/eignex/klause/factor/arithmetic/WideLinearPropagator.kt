package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.wideEnforceRow
import com.eignex.klause.factor.arithmetic.internals.wideSideReason
import com.eignex.klause.factor.arithmetic.internals.wideSumRange
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo

/**
 * CP propagator for a [Linear] row whose coefficients or bound exceed the 64-bit range (the wide form).
 * All arithmetic is exact arbitrary precision ([BigInt]) — see
 * [com.eignex.klause.factor.arithmetic.internals.wideEnforceRow] — so there is no overflow to guard against
 * and no `unknown` degrade: the row is enforced exactly, including at a fully pinned leaf.
 *
 * The integer variables keep their ordinary `Long` domains and are branched normally; this propagator is
 * the only place the wide coefficients are read, and the wide value never reaches the domains, the trail,
 * or the LP relaxation (a wide row is excluded from the relaxation projection).
 */
internal class WideLinearPropagator(
    val intVars: IntArray,
    private val vars: IntArray,
    private val coeffs: Array<BigInt>,
    private val op: LinearOp,
    private val bound: BigInt,
) : Propagator {

    /** Interval reasoning reads only `min`/`max` (see [LinearPropagator]); subscribe to bound moves. */
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
