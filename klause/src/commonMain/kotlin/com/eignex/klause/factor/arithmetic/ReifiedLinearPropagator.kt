package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearLiftedAntecedents
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.explainLinearBound
import com.eignex.klause.factor.arithmetic.internals.integralQuotientOrNull
import com.eignex.klause.factor.arithmetic.internals.linearLazyReason
import com.eignex.klause.factor.arithmetic.internals.linearSumRange
import com.eignex.klause.factor.arithmetic.internals.predecessorOrNull
import com.eignex.klause.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.factor.arithmetic.internals.reifiedAuxTail
import com.eignex.klause.factor.arithmetic.internals.successorOrNull
import com.eignex.klause.factor.arithmetic.internals.unreachableEqualityReason
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

/** CP propagator for [ReifiedLinear]: reification propagation and conflict reasons. */
internal class ReifiedLinearPropagator(
    private val auxBoolVar: Int,
    val boolVars: IntArray,
    val intVars: IntArray,
    private val coeffs: LongArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Long,
) : Propagator {

    private val singleEquality = vars.size == 1 && (op == LinearOp.EQ || op == LinearOp.NE)
    private val equalityTarget: Long? =
        if (singleEquality) integralQuotientOrNull(bound, coeffs[0]) else null

    // A single-term equality also reads membership of its target, including under negated reification.
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars).let {
        if (singleEquality && equalityTarget != null) it + IntEvent.pack(vars[0], IntEvent.VALUE_REMOVED) else it
    }

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        explainLinearBound(state, coeffs, vars, payload, atTrail, atLevel)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxValue = state.boolValues[auxBoolVar]
        val extraLit = auxValue?.let { Lit.make(auxBoolVar, !it) } ?: 0
        val includeExtraLit = auxValue != null
        if (singleEquality && eqTargetUnreachable(state)) {
            return unreachableEqualityReason(state, vars[0], equalityTarget, extraLit.takeIf { includeExtraLit })
        }
        // With the indicator set, the conflict is the body (indicator true) or its negation (false) failing on
        // one side of the sum, which [settlingSide] names for the bounds as they stand.
        val range = linearSumRange(state, coeffs, vars)
        val side = auxValue?.let { settlingSide(range[0], range[1], holds = !it) }
        return if (side != null) {
            collectLinearLiftedAntecedents(
                state,
                coeffs,
                vars,
                useLo = side.useLo,
                slack = side.slack,
                extraLit = extraLit,
                includeExtraLit = true,
            )
        } else {
            collectLinearTightenAntecedents(
                state, vars, excludeIdx = -1, extraLit = extraLit, includeExtraLit = includeExtraLit,
            )
        }
    }

    /** The side of the sum that settles the body and how far it may loosen; see [settlingSide]. */
    private class Side(val useLo: Boolean, val slack: Long)

    /**
     * The one side of the sum that makes the body always hold ([holds]) or never hold for [sumLo]..[sumHi], with
     * the slack it has to spare, or null when the body is not settled that way or needs both sides (an equality
     * always holding, a disequality never holding, or an overflowed range).
     */
    private fun settlingSide(sumLo: Long, sumHi: Long, holds: Boolean): Side? {
        if (sumLo == Long.MIN_VALUE && sumHi == Long.MAX_VALUE) return null
        val b = bound
        return if (holds) {
            when (op) {
                LinearOp.LE -> if (sumHi <= b) Side(useLo = false, slack = b - sumHi) else null

                LinearOp.GE -> if (sumLo >= b) Side(useLo = true, slack = sumLo - b) else null

                LinearOp.NE -> when {
                    sumHi < b -> Side(useLo = false, slack = b - 1 - sumHi)
                    sumLo > b -> Side(useLo = true, slack = sumLo - b - 1)
                    else -> null
                }

                LinearOp.EQ -> null
            }
        } else {
            when (op) {
                LinearOp.LE -> if (sumLo > b) Side(useLo = true, slack = sumLo - b - 1) else null

                LinearOp.GE -> if (sumHi < b) Side(useLo = false, slack = b - sumHi - 1) else null

                LinearOp.EQ -> when {
                    sumLo > b -> Side(useLo = true, slack = sumLo - b - 1)
                    sumHi < b -> Side(useLo = false, slack = b - sumHi - 1)
                    else -> null
                }

                LinearOp.NE -> null
            }
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val range = linearSumRange(state, coeffs, vars)
        val sumLo = range[0]
        val sumHi = range[1]
        val bnd = bound
        val alwaysHolds = when (op) {
            LinearOp.LE -> sumHi <= bnd
            LinearOp.GE -> sumLo >= bnd
            LinearOp.EQ -> sumLo == bnd && sumHi == bnd
            LinearOp.NE -> sumHi < bnd || sumLo > bnd
        }
        val neverHolds = when (op) {
            LinearOp.LE -> sumLo > bnd
            LinearOp.GE -> sumHi < bnd
            LinearOp.EQ -> sumLo > bnd || sumHi < bnd
            LinearOp.NE -> sumLo == bnd && sumHi == bnd
        }
        // Aux pin antecedents: union of the int-fact antecedents that drove sumLo/sumHi
        // into the always/never-holds region. LCG-style transitive reasoning — each int
        // bound's recorded `intMinAntecedents` / `intMaxAntecedents` traces back to the
        // bool decisions that established it.
        // Thread the aux's current pinning as an extra antecedent for every implied int
        // tighten — the body-propagation path was selected by this pin, so any subsequent
        // conflict must trace back through it.
        return state.reifiedAuxTail(
            auxBoolVar,
            alwaysHolds,
            neverHolds,
            // Pinning the indicator rests on the side of the sum that settles the body, lifted by its slack.
            pinAntecedent = {
                settlingSide(sumLo, sumHi, holds = alwaysHolds)?.let {
                    if (state.undoLogging && factorId >= 0 && state.currentLevel > 0) {
                        linearLazyReason(state, factorId, -1, it.useLo, it.slack, 0, false)
                    } else {
                        collectLinearLiftedAntecedents(state, coeffs, vars, useLo = it.useLo, slack = it.slack)
                    }
                } ?: state.composeIntVarAtomAntecedents(vars)
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
            propagateTrue = { a ->
                propagateLinearBounds(state, coeffs, vars, op, bnd, a, includeExtraLit = true, factorId = factorId)
            },
            propagateFalse = { a ->
                when (op) {
                    LinearOp.LE -> successorOrNull(bnd)?.let {
                        propagateLinearBounds(state, coeffs, vars, LinearOp.GE, it, a, true, factorId)
                    } ?: false

                    LinearOp.GE -> predecessorOrNull(bnd)?.let {
                        propagateLinearBounds(state, coeffs, vars, LinearOp.LE, it, a, true, factorId)
                    } ?: false

                    LinearOp.EQ -> propagateLinearBounds(state, coeffs, vars, LinearOp.NE, bnd, a, true, factorId)

                    LinearOp.NE -> propagateLinearBounds(state, coeffs, vars, LinearOp.EQ, bnd, a, true, factorId)
                }
            },
        )
    }

    private fun eqTargetUnreachable(state: PropagationState): Boolean {
        if (coeffs[0] == 0L) return bound != 0L
        val value = equalityTarget ?: return true
        return value !in state.intDomains[vars[0]]
    }
}
