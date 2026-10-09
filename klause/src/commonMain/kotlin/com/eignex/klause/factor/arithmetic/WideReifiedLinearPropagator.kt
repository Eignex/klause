package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.integralQuotientOrNull
import com.eignex.klause.factor.arithmetic.internals.reifiedAuxTail
import com.eignex.klause.factor.arithmetic.internals.unreachableEqualityReason
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
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus

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

    private val singleEquality = vars.size == 1 && (op == LinearOp.EQ || op == LinearOp.NE)
    private val equalityTarget: Long? =
        if (singleEquality) integralQuotientOrNull(bound, coeffs[0]) else null

    // A single-term equality also reads membership of its target, including under negated reification.
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars).let {
        if (singleEquality && equalityTarget != null) it + IntEvent.pack(vars[0], IntEvent.VALUE_REMOVED) else it
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxValue = state.boolValues[auxBoolVar]
        val extraLit = auxValue?.let { Lit.make(auxBoolVar, !it) } ?: 0
        val includeExtraLit = auxValue != null
        if (singleEquality && eqTargetUnreachable(state)) {
            return unreachableEqualityReason(state, vars[0], equalityTarget, extraLit.takeIf { includeExtraLit })
        }
        // With the indicator set, the body (or its negation) fails on one side of the activity range.
        val (sumLo, sumHi) = wideSumRange(state, vars, coeffs)
        val side = auxValue?.let { wideSettlingSide(op, sumLo, sumHi, bound, holds = !it) }
        return if (side != null) {
            wideSideReason(state, vars, coeffs, side, -1, if (includeExtraLit) extraLit else null)
        } else {
            collectLinearTightenAntecedents(
                state, vars, excludeIdx = -1, extraLit = extraLit, includeExtraLit = includeExtraLit,
            )
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
            // Target absence decides EQ and NE even when the interval still straddles the target.
            extraPin = {
                if (singleEquality && eqTargetUnreachable(state)) {
                    state.pinBool(
                        auxBoolVar, op == LinearOp.NE, unreachableEqualityReason(state, vars[0], equalityTarget),
                    )
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

    private fun eqTargetUnreachable(state: PropagationState): Boolean {
        if (coeffs[0] == BIG_ZERO) return bound != BIG_ZERO
        val value = equalityTarget ?: return true
        return value !in state.intDomains[vars[0]]
    }
}
