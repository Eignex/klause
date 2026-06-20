package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt
import com.eignex.klause.solver.propagation.RevIntArray
import com.eignex.klause.solver.propagation.RevLongArray

/**
 * CP contract for [GaussianXor]: incremental Gauss-Jordan elimination over GF(2) parity
 * constraints.
 *
 * A *system* of parity (XOR) constraints propagated jointly by Gauss-Jordan elimination over
 * GF(2). Each constraint is `XOR(vars) == rhs`; the factor owns all of them as one matrix.
 *
 * Unlike a single [Xor] factor — which can only force a variable once a constraint has exactly
 * one unassigned variable left — Gaussian elimination *combines* equations, so it detects an
 * inconsistency (`0 = 1`) or forces a variable as soon as the linear system implies it.
 *
 * Each [propagate] substitutes the current partial assignment, reduces the residual system to
 * row-echelon form, and pins every variable the system forces. Conflicts and forced pins are
 * explained sharply: every row carries a reason bitset of the assigned variables feeding it,
 * xor-combined through each elimination step, so even-occurrence variables cancel and a derived
 * row's reason is exactly its odd-occurrence assigned support.
 *
 * This factor is **propagation-only**: it inherits the [com.eignex.klause.solver.Factor]
 * local-search defaults (always-satisfied, zero deltas). The Gaussian system is redundant with
 * the per-row [Xor] factors posted alongside it, which carry the same parity semantics *with*
 * real LS support.
 */
interface GaussianXorPropagator : Propagator {

    /** The individual parity constraints forming this Gaussian system. */
    val constraints: List<Xor>

    /**
     * Per-[PropagationState] reversible incremental Gauss-Jordan state. The reduced matrix is
     * maintained *across* fires on the engine undo trail instead of being rebuilt every fire.
     */
    class IncrState(state: PropagationState, rows: Int, cols: Int, words: Int) {
        internal val mask = RevLongArray(state, rows * words)
        internal val reason = RevLongArray(state, rows * words)
        internal val rhs = RevIntArray(state, rows)
        internal val basicCol = RevIntArray(state, rows, -1)
        internal val pivotRow = RevIntArray(state, cols, -1)
        internal val seenVal = RevIntArray(state, cols, -1)
        internal val valid = RevInt(state, 0)

        /** Variables involved in the latest conflict row, or null if no conflict. */
        var conflictVars: IntArray? = null
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? IncrState)?.conflictVars
}
