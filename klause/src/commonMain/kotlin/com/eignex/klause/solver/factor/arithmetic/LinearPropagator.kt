package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearDirAntecedents
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.factor.arithmetic.internals.linearSumRange
import com.eignex.klause.solver.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagator for [Linear]: bounds propagation and conflict reasons. */
internal class LinearPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val coeffs: IntArray,
    private val vars: IntArray,
    private val op: LinearOp,
    private val bound: Int,
) : Propagator {

    /**
     * Advisor subscription (#623): `propagateLinearBounds` derives everything from the interval
     * `[c·min, c·max]` of each term — it reads only `min`/`max` and never inspects interior holes.
     * So the propagator subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] on each variable
     * and is not woken by interior `VALUE_REMOVED`. Terms are coalesced, so [vars] is duplicate-free.
     */
    override val initialIntEventWatches: IntArray = IntArray(vars.size * 2).also { out ->
        var w = 0
        for (v in vars) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagateLinearBounds(state, coeffs, vars, op, bound.toLong())

    /** Reason set when [propagate] returns false. The conflict comes from exactly one sum
     *  extreme breaching `bound`: `LE` / `EQ`-with-`sumLo>bound` from the lo side (`Σ rLo`),
     *  `GE` / `EQ`-with-`sumHi<bound` from the hi side (`Σ rHi`). Cite only that side's
     *  driving bounds (see [collectLinearDirAntecedents]) — those alone prove infeasibility,
     *  so the nogood is sharper and more reusable than citing both bounds of every var.
     *  `NE` (sum pinned to `bound`) needs both bounds, so it keeps the dense reason. Sound;
     *  analyzer 1UIP minimisation trims any remaining redundancy. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        if (op == LinearOp.NE) return collectLinearTightenAntecedents(state, vars, excludeIdx = -1, extraLit = 0)
        val range = linearSumRange(state, coeffs, vars) // [sumLo, sumHi]
        val useLo = when (op) {
            LinearOp.LE -> true
            LinearOp.GE -> false
            else -> range[0] > bound.toLong() // EQ: lo side (mins too big) vs hi side
        }
        return collectLinearDirAntecedents(state, coeffs, vars, excludeIdx = -1, extraLit = 0, useLo = useLo)
    }
}
