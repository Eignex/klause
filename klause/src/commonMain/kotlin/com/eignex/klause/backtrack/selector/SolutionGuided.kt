package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Solution-guided value selection: wraps a [base] value heuristic and, once a solution is observed
 * via [onSolution], tries the snapshot's value for each variable first, falling back to [base]'s
 * order for the rest. The snapshot is refreshed on each new solution; in optimisation successive
 * incumbents are similar, so staying near the last one finds the next one faster. See Beck,
 * "Solution-Guided Multi-Point Constructive Search for Job Shop Scheduling", JAIR 2007
 * (https://doi.org/10.1613/jair.2169), and Demirović, Chu and Stuckey, "Solution-Based Phase Saving
 * for CP", CP 2018 (https://doi.org/10.1007/978-3-319-98334-9_7).
 *
 *  - Before any solution the order is purely [base].
 *  - After a solution: the saved value is tried first; if propagation has removed it from the
 *    current domain, [base]'s order is used in full.
 *  - Snapshots **persist** across [onRestart], so the bias toward the last incumbent survives
 *    restarts. The base's `onRestart` is still forwarded so stateful base heuristics see it.
 *
 * Composes with [Impact] / [MaxSd] / [IndomainRandom] as the inner choice: if the saved-value
 * branch proves infeasible, the inner heuristic's order takes over.
 */
class SolutionGuided(private val base: ValueSelector) : ValueSelector {

    override fun fresh() = SolutionGuided(base.fresh())

    private var bools: BooleanArray? = null
    private var ints: LongArray? = null

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> =
        guide(session, varRef) { base.values(session, varRef, rng) }

    internal fun guide(
        session: PropagationSession,
        varRef: VarRef,
        fallback: Sequence<Long>,
    ): Sequence<Long> = guide(session, varRef) { fallback }

    private inline fun guide(
        session: PropagationSession,
        varRef: VarRef,
        fallback: () -> Sequence<Long>,
    ): Sequence<Long> {
        val savedBools = bools
        val savedInts = ints
        if (savedBools == null || savedInts == null) return fallback()
        val saved: Long = when (varRef) {
            is VarRef.Bool -> if (varRef.varId < savedBools.size && savedBools[varRef.varId]) 1L else 0L

            is VarRef.IntVar -> {
                if (varRef.varId >= savedInts.size) return fallback()
                savedInts[varRef.varId]
            }
        }
        val savedFeasible = when (varRef) {
            // Bool: saved is always 0 or 1 — feasible iff the var is still unpinned.
            is VarRef.Bool -> session.boolValue(varRef.varId) == null

            is VarRef.IntVar -> saved in session.intDomain(varRef.varId)
        }
        return if (savedFeasible) {
            sequenceOf(saved) + fallback().filter { it != saved }
        } else {
            fallback()
        }
    }

    override fun onConflict(varRef: VarRef, value: Long) = base.onConflict(varRef, value)
    override fun onCommit(varRef: VarRef, value: Long) = base.onCommit(varRef, value)
    override fun onRestart() = base.onRestart()

    override fun onSolution(snapshot: Sample) {
        bools = snapshot.bools
        ints = snapshot.ints
        base.onSolution(snapshot)
    }
}
