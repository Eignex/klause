package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.wideEnforceRow
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * CP propagator for a [Linear] row whose coefficients or bound exceed the 64-bit range (the wide form).
 * All arithmetic is exact arbitrary precision ([BigInteger]) — see
 * [com.eignex.klause.factor.arithmetic.internals.wideEnforceRow] — so there is no overflow to guard against
 * and no `unknown` degrade: the row is enforced exactly, including at a fully pinned leaf.
 *
 * The integer variables keep their ordinary `Long` domains and are branched normally; this propagator is
 * the only place the wide coefficients are read, and the wide value never reaches the domains, the trail,
 * or the LP relaxation (a wide row is excluded from the relaxation — see [Linear.linearize]).
 */
internal class WideLinearPropagator(
    val intVars: IntArray,
    private val vars: IntArray,
    private val coeffs: Array<BigInteger>,
    private val op: LinearOp,
    private val bound: BigInteger,
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
        wideEnforceRow(state, vars, coeffs, op, bound, auxLit = 0)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, vars, excludeIdx = -1, extraLit = 0)
}
