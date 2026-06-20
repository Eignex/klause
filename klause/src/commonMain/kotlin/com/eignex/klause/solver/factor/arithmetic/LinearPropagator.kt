package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearDirAntecedents
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.factor.arithmetic.internals.linearSumRange
import com.eignex.klause.solver.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.solver.propagation.PropagationState

/** CP interface for [Linear]: bounds propagation and conflict reasons. */
interface LinearPropagator : Propagator {

    /** Coefficients parallel to [vars]. */
    val coeffs: IntArray

    /** Integer variable ids. */
    val vars: IntArray

    /** Comparison operator. */
    val op: LinearOp

    /** Right-hand-side bound. */
    val bound: Int

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
        // Conflict: the driving extreme breaches `bound`; slack = how far it can fall back and
        // still breach (sumLo > bound ⇒ sumLo-bound-1; sumHi < bound ⇒ bound-sumHi-1).
        // Cite the *current* driving bounds (the trail-resident order literals), not a
        // history-derived weakest relaxation: the canonical LCG ladder stores levels/reasons
        // on the literals themselves, so the looser-bound relaxation (which needed the bound
        // histories) is gone. Current bounds are a sound, stronger reason for the breach.
        return collectLinearDirAntecedents(state, coeffs, vars, excludeIdx = -1, extraLit = 0, useLo = useLo)
    }
}
