package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Solution-guided value selection (Demoen-Garcia-de-la-Banda 2009 / Beck-Davenport). Wraps
 * a [base] value heuristic: once a SAT leaf is observed via [onSolution], the heuristic
 * snapshots the assignment, and on every subsequent pick it tries the snapshot's value for
 * the var first (falling back to [base]'s order for everything else). The snapshot is
 * refreshed on each new solution — typical use is optimisation, where successive incumbents
 * are similar and a search biased to stay near the previous incumbent finds the next one
 * faster than starting from scratch.
 *
 *  - First descent (before any solution) is purely [base] — no bias.
 *  - After a solution: saved value tried first; if the saved value is no longer in the
 *    current domain (the search has propagated it away), falls through to [base] in full.
 *  - Snapshots **persist** across [onRestart] — that's the whole point: cross-restart
 *    bias toward the last-seen incumbent. The base's `onRestart` is still forwarded so
 *    activity-based wrappers like [Vsids] still decay as expected.
 *
 * Composes naturally with [Impact] / [MaxSd] / [IndomainRandom] / phase-saving as the
 * inner choice — the engine still runs through the saved value first, but if that branch
 * proves infeasible, the inner heuristic's order takes over.
 */
class SolutionGuided(private val base: ValueSelector) : ValueSelector {

    private var bools: BooleanArray? = null
    private var ints: IntArray? = null

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> {
        val savedBools = bools
        val savedInts = ints
        if (savedBools == null || savedInts == null) return base.values(session, varRef, rng)
        val saved: Int = when (varRef) {
            is VarRef.Bool -> if (varRef.varId < savedBools.size && savedBools[varRef.varId]) 1 else 0

            is VarRef.IntVar -> {
                if (varRef.varId >= savedInts.size) return base.values(session, varRef, rng)
                savedInts[varRef.varId]
            }
        }
        val savedFeasible = when (varRef) {
            // Bool: saved is always 0 or 1 — feasible iff the var is still unpinned.
            is VarRef.Bool -> session.boolValue(varRef.varId) == null

            is VarRef.IntVar -> saved in session.intDomain(varRef.varId)
        }
        return if (savedFeasible) {
            sequenceOf(saved) + base.values(session, varRef, rng).filter { it != saved }
        } else {
            base.values(session, varRef, rng)
        }
    }

    override fun onConflict(varRef: VarRef, value: Int) = base.onConflict(varRef, value)
    override fun onCommit(varRef: VarRef, value: Int) = base.onCommit(varRef, value)
    override fun onRestart() = base.onRestart()

    override fun onSolution(snapshot: Sample) {
        bools = snapshot.bools.copyOf()
        ints = snapshot.ints.copyOf()
        base.onSolution(snapshot)
    }
}
