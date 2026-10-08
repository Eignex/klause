package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.reifiedAuxTail
import com.eignex.klause.factor.arithmetic.internals.wideAlwaysHolds
import com.eignex.klause.factor.arithmetic.internals.wideEnforceRow
import com.eignex.klause.factor.arithmetic.internals.wideNeverHolds
import com.eignex.klause.factor.arithmetic.internals.wideSideReason
import com.eignex.klause.factor.arithmetic.internals.wideSumRange
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

/**
 * CP propagator for a wide [ReifiedLinear]: `auxBoolVar ↔ (Σ wideCoeffs·vars ⟨op⟩ bound)`, where the
 * coefficients or bound exceed the 64-bit range. Mirrors [ReifiedLinearPropagator]'s reification structure
 * but does every interval/feasibility computation in exact [BigInt] via
 * [com.eignex.klause.factor.arithmetic.internals.wideEnforceRow], so there is no overflow, degrade, or
 * `unknown` — the row is enforced exactly, including at a fully pinned leaf, and the wide value never
 * reaches the domains, the trail, or the LP.
 */
internal class WideReifiedLinearPropagator(
    private val auxBoolVar: Int,
    val boolVars: IntArray,
    val intVars: IntArray,
    private val coeffs: Array<BigInt>,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: BigInt,
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
            // With the indicator set, the body (or its negation) fails on one side of the activity range.
            val (sumLo, sumHi) = wideSumRange(state, vars, coeffs)
            val side = auxValue?.let { wideSettlingSide(op, sumLo, sumHi, bound, holds = !it) }
            if (side != null) {
                wideSideReason(state, vars, coeffs, side, -1, if (includeExtraLit) extraLit else null)
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
    }

    /**
     * The side of the activity range that settles the body: whether it holds ([holds]) or fails, read off the lower
     * end (true) or the upper end (false); null when only both together settle it (an equality pinned on both
     * ends, or a disequality).
     */
    private fun wideSettlingSide(op: LinearOp, sumLo: BigInt, sumHi: BigInt, bound: BigInt, holds: Boolean): Boolean? =
        when (op) {
            LinearOp.LE -> if (holds) false else true

            LinearOp.GE -> if (holds) true else false

            LinearOp.EQ -> if (holds) {
                null
            } else {
                (
                if (sumLo > bound) {
                    true
                } else if (sumHi < bound) {
                    false
                } else {
                    null
                }
                )
            }

            LinearOp.NE -> if (holds) {
                (
                if (sumLo > bound) {
                    true
                } else if (sumHi < bound) {
                    false
                } else {
                    null
                }
                )
            } else {
                null
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
            pinAntecedent = {
                val (sumLo, sumHi) = wideSumRange(state, vars, coeffs)
                when (val side = wideSettlingSide(op, sumLo, sumHi, bound, holds = always)) {
                    null -> state.composeIntVarAtomAntecedents(vars)
                    else -> wideSideReason(state, vars, coeffs, side, -1, null)
                }
            },
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
                    LinearOp.LE -> wideEnforceRow(state, vars, coeffs, LinearOp.GE, bound + BIG_ONE, a)
                    LinearOp.GE -> wideEnforceRow(state, vars, coeffs, LinearOp.LE, bound - BIG_ONE, a)
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
        if (c == BIG_ZERO) return bound != BIG_ZERO
        if (bound - bound / c * c != BIG_ZERO) return true
        val value = bound / c
        if (!value.fitsLong()) return true
        return value.toLongExact() !in state.intDomains[vars[0]]
    }

    /** Reason for pinning the indicator false on an unreachable single-term `c·x == bound` (see
     *  [ReifiedLinearPropagator.eqUnreachableReason] for the original-vs-current distinction). */
    private fun eqUnreachableReason(state: PropagationState): IntArray? {
        val c = coeffs[0]
        if (c == BIG_ZERO || bound - bound / c * c != BIG_ZERO) return null
        val k = bound / c
        if (!k.fitsLong()) return null
        val kl = k.toLongExact()
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
}
