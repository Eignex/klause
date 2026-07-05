package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.arithmetic.internals.linearSumRange
import com.eignex.klause.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.factor.arithmetic.internals.reifiedAuxTail
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Lit

/** CP propagator for [ReifiedLinear]: reification propagation and conflict reasons. */
internal class ReifiedLinearPropagator(
    private val auxBoolVar: Int,
    val boolVars: IntArray,
    val intVars: IntArray,
    private val coeffs: IntArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Int,
) : Propagator {

    /**
     * Advisor subscription (#623): like [Linear], the integer reasoning is purely interval-based.
     * Subscribe term variables to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED]; the indicator
     * [auxBoolVar] keeps its separate Boolean wakeup.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxValue = state.boolValues[auxBoolVar]
        val extraLit = auxValue?.let { Lit.make(auxBoolVar, !it) } ?: 0
        val includeExtraLit = auxValue != null
        // A single-term equality body (`c·v == bound`) can be infeasible because its required value
        // is an *interior hole* of v, with v's bounds unchanged from root (the [eqTargetUnreachable]
        // path). A bounds-only reason then cites nothing and degenerates to the bare indicator lit —
        // an unsound unit nogood that forbids the indicator even on assignments where the hole is
        // absent. Use the hole-aware collector so the carved value's eq-atom joins the reason. Other
        // failure paths are bound-driven (the sum range is computed from bounds; a hole crossed by a
        // body tighten is already chained through that bound atom's own reason), so they stay on the
        // tighter bounds-only collector.
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
        val range = linearSumRange(state, coeffs, vars)
        val sumLo = range[0]
        val sumHi = range[1]
        val bnd = bound.toLong()
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
            pinAntecedent = { state.composeIntVarAtomAntecedents(vars) },
            // Bounds alone miss the case where a single-term EQ targets a value that is unreachable
            // *inside* the bound interval — an interior domain hole, or a bound not divisible by the
            // coefficient. The equality can then never hold, so pin the aux false now with a
            // hole-aware antecedent. Without this the aux stays free, search may set it true, and the
            // resulting empty-domain conflict carries a bounds-only (hole-blind) reason that yields an
            // unsound learned clause — the latent false-UNSAT of #121.
            extraFalsePin = {
                if (op == LinearOp.EQ && vars.size == 1 && eqTargetUnreachable(state)) {
                    state.pinBool(auxBoolVar, false, collectHoleAndBoundAntecedents(state, vars))
                } else {
                    null
                }
            },
            propagateTrue = { a ->
                propagateLinearBounds(state, coeffs, vars, op, bnd, extraLit = a, includeExtraLit = true)
            },
            propagateFalse = { a ->
                when (op) {
                    LinearOp.LE -> propagateLinearBounds(state, coeffs, vars, LinearOp.GE, bnd + 1, a, true)
                    LinearOp.GE -> propagateLinearBounds(state, coeffs, vars, LinearOp.LE, bnd - 1, a, true)
                    LinearOp.EQ -> propagateLinearBounds(state, coeffs, vars, LinearOp.NE, bnd, a, true)
                    LinearOp.NE -> propagateLinearBounds(state, coeffs, vars, LinearOp.EQ, bnd, a, true)
                }
            },
        )
    }

    /** For a single-term `c·x = bound`, true when `bound/c` is not an integer in `x`'s current
     *  domain — i.e. the equality is unsatisfiable even though `bound` lies within `x`'s bounds
     *  (an interior hole) or `bound` is not divisible by `c`. */
    private fun eqTargetUnreachable(state: PropagationState): Boolean {
        val c = coeffs[0].toLong()
        val b = bound.toLong()
        if (c == 0L) return b != 0L
        if (b % c != 0L) return true
        val value = b / c
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) return true
        return value.toInt() !in state.intDomains[vars[0]]
    }
}
