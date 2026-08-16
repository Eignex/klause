package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.reifiedAuxTail
import com.eignex.klause.factor.arithmetic.internals.wideAlwaysHolds
import com.eignex.klause.factor.arithmetic.internals.wideEnforceRow
import com.eignex.klause.factor.arithmetic.internals.wideNeverHolds
import com.eignex.klause.factor.arithmetic.internals.wideSumRange
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Lit
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * CP propagator for a wide [ReifiedLinear]: `auxBoolVar ↔ (Σ wideCoeffs·vars ⟨op⟩ bound)`, where the
 * coefficients or bound exceed the 64-bit range. Mirrors [ReifiedLinearPropagator]'s reification structure
 * but does every interval/feasibility computation in exact [BigInteger] via
 * [com.eignex.klause.factor.arithmetic.internals.wideEnforceRow], so there is no overflow, degrade, or
 * `unknown` — the row is enforced exactly, including at a fully pinned leaf, and the wide value never
 * reaches the domains, the trail, or the LP.
 */
internal class WideReifiedLinearPropagator(
    private val auxBoolVar: Int,
    val boolVars: IntArray,
    val intVars: IntArray,
    private val coeffs: Array<BigInteger>,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: BigInteger,
) : Propagator {

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxValue = state.boolValues[auxBoolVar]
        val extraLit = auxValue?.let { Lit.make(auxBoolVar, !it) } ?: 0
        val includeExtraLit = auxValue != null
        // A single-term equality body can be infeasible because its target is an interior hole; use the
        // hole-aware collector there so the carved value's eq-atom joins the reason.
        return if (op == LinearOp.EQ && vars.size == 1) {
            collectHoleAndBoundAntecedents(state, vars, extraLit = extraLit, includeExtraLit = includeExtraLit)
        } else {
            collectLinearTightenAntecedents(
                state,
                vars,
                excludeIdx = -1,
                extraLit = extraLit,
                includeExtraLit = includeExtraLit,
            )
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val (sumLo, sumHi) = wideSumRange(state, vars, coeffs)
        val always = wideAlwaysHolds(op, sumLo, sumHi, bound)
        val never = wideNeverHolds(op, sumLo, sumHi, bound)
        return state.reifiedAuxTail(
            auxBoolVar,
            always,
            never,
            pinAntecedent = { state.composeIntVarAtomAntecedents(vars) },
            extraFalsePin = {
                if (op == LinearOp.EQ && vars.size == 1 && eqTargetUnreachable(state)) {
                    state.pinBool(auxBoolVar, false, eqUnreachableReason(state))
                } else {
                    null
                }
            },
            propagateTrue = { a -> wideEnforceRow(state, vars, coeffs, op, bound, a) },
            propagateFalse = { a ->
                when (op) {
                    LinearOp.LE -> wideEnforceRow(state, vars, coeffs, LinearOp.GE, bound + BigInteger.ONE, a)
                    LinearOp.GE -> wideEnforceRow(state, vars, coeffs, LinearOp.LE, bound - BigInteger.ONE, a)
                    LinearOp.EQ -> wideEnforceRow(state, vars, coeffs, LinearOp.NE, bound, a)
                    LinearOp.NE -> wideEnforceRow(state, vars, coeffs, LinearOp.EQ, bound, a)
                }
            },
        )
    }

    /** For a single-term `c·x = bound`, true when `bound/c` is not an integer in `x`'s current domain
     *  (an interior hole or a non-divisible bound), so the equality can never hold. */
    private fun eqTargetUnreachable(state: PropagationState): Boolean {
        val c = coeffs[0]
        if (c == BigInteger.ZERO) return bound != BigInteger.ZERO
        if (bound - bound / c * c != BigInteger.ZERO) return true
        val value = bound / c
        if (!value.fitsLong()) return true
        return value.longValue() !in state.intDomains[vars[0]]
    }

    /** Reason for pinning the indicator false on an unreachable single-term `c·x == bound` (see
     *  [ReifiedLinearPropagator.eqUnreachableReason] for the original-vs-current distinction). */
    private fun eqUnreachableReason(state: PropagationState): IntArray? {
        val c = coeffs[0]
        if (c == BigInteger.ZERO || bound - bound / c * c != BigInteger.ZERO) return null
        val k = bound / c
        if (!k.fitsLong()) return null
        val kl = k.longValue()
        val v = vars[0]
        val d = state.intDomains[v]
        val orig = state.rootDomains[v]
        return when {
            kl < orig.min || kl > orig.max -> null
            kl < d.min -> intArrayOf(Lit.make(state.atomVarGe(v, d.min), false))
            kl > d.max -> intArrayOf(Lit.make(state.atomVarLe(v, d.max), false))
            else -> intArrayOf(Lit.make(state.atomVarEq(v, kl), true))
        }
    }

    private companion object {
        val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)
        val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)
        fun BigInteger.fitsLong(): Boolean = this in LONG_MIN..LONG_MAX
    }
}
