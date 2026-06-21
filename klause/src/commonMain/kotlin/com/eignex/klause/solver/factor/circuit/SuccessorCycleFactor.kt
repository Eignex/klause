package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntIntMap

/** Shared scaffolding for the successor-array cycle factors [Circuit] and [Subcircuit]: LS cost
 *  plumbing plus the domain-range / pigeonhole / cycle-scan pruning helpers. */
abstract class SuccessorCycleFactor(
    /** Successor variable id per node. */
    val succ: IntArray,
) : Factor {

    /** Number of nodes; equal to `succ.size`. */
    val n: Int = succ.size

    private val positionOfVar: IntIntMap = IntIntMap.build(succ, IntArray(n) { it }, absent = -1)

    final override val boolVars: IntArray = EmptyIntArray
    final override val intVars: IntArray = succ

    /**
     * Conflict reason: the bound atoms of every successor var. Both [Circuit] and [Subcircuit]
     * reason globally over the whole successor array — range / self-loop shaving, the
     * AllDifferent pigeonhole, and the sub-tour / unreachability scan — and prune only at domain
     * endpoints (no interior holes), so the currently-tightened `succ` bounds are a sound
     * clause-form nogood over the successor atoms. Without this the failure falls through to the
     * coarse default bool-pins reason, which is suppressed once an int decision is on the trail.
     */
    final override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, succ, excludeIdx = -1, extraLit = 0)

    protected abstract fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Int

    final override fun initialize(state: LocalSearchState, factorId: Int) {
        state.intPayload[factorId] = computeCost(state, replaceAt = -1, replaceWith = 0)
    }

    final override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    final override fun violationDegree(state: LocalSearchState, factorId: Int): Int = state.intPayload[factorId]

    final override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar]
        if (pos < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = pos, replaceWith = newValue)
        return newCost - oldCost
    }

    final override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        if (positionOfVar[intVar] < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = -1, replaceWith = 0)
        state.intPayload[factorId] = newCost
        return newCost - oldCost
    }
}
