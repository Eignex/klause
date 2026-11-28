package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Solution-guided value selection: tries the last solution's value first while it is still in the domain,
 * then [base]'s order. The snapshot persists across restarts. See Beck, "Solution-Guided Multi-Point
 * Constructive Search for Job Shop Scheduling", JAIR 2007 (https://doi.org/10.1613/jair.2169), and
 * Demirović, Chu and Stuckey, "Solution-Based Phase Saving for CP", CP 2018
 * (https://doi.org/10.1007/978-3-319-98334-9_7).
 */
class SolutionGuided(private val base: ValueSelector) : ValueSelector {

    override fun fresh() = SolutionGuided(base.fresh())

    private var bools: BooleanArray? = null
    private var ints: LongArray? = null

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> =
        guide(session, varRef) { base.values(session, varRef, rng) }

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
