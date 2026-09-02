package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.BranchingState
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.solver.search.VariableBranching
import kotlin.random.Random
import com.eignex.klause.solver.search.Vsids as NeutralVsids

/**
 * Bind a substrate-neutral branching to the finite lane.
 *
 * The heuristic keeps its own state and sees only [BranchingState]; the finite-only hooks a
 * [VariableSelector] additionally receives are dropped here, since a heuristic that wanted them would
 * not have been written against the neutral view in the first place.
 */
fun VariableBranching<BranchingState>.asSelector(): VariableSelector = NeutralVariableSelector(this)

/**
 * [com.eignex.klause.solver.search.Vsids] bound to the finite lane.
 *
 * @param decay Per-conflict multiplicative decay of every prior activity.
 * @param rescaleThreshold Increment at which every activity is scaled back down.
 */
@Suppress("FunctionNaming")
fun Vsids(decay: Double = 0.95, rescaleThreshold: Double = 1e100): VariableSelector =
    NeutralVsids(decay, rescaleThreshold).asSelector()

/** [BranchingState] over one finite propagation session. */
private class CpBranchingState(private val session: PropagationSession) : BranchingState {
    override val numBoolVars: Int get() = session.problem.numBoolVars

    override val numIntVars: Int get() = session.problem.numIntVars

    override fun boolValue(variable: Int): Boolean? = session.boolValue(variable)

    override fun intFixed(variable: Int): Boolean = session.intDomain(variable).isFixed
}

/**
 * Adapts a neutral [VariableBranching] to the finite [VariableSelector] contract.
 *
 * The view is cached per session rather than rebuilt per pick: a heuristic that keeps an order heap
 * detects a reused instance by state identity, so a fresh view each pick would look like a fresh solve
 * every time.
 */
private class NeutralVariableSelector(private val branching: VariableBranching<BranchingState>) : VariableSelector {
    private var view: CpBranchingState? = null
    private var viewOf: PropagationSession? = null

    override fun pick(session: PropagationSession, rng: Random): VarRef? = branching.pick(viewFor(session), rng)

    override fun fresh(): VariableSelector = NeutralVariableSelector(branching.fresh())

    override fun onConflict(varRef: VarRef) {
        branching.onConflict(varRef)
    }

    override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
        branching.onConflict(varRef, conflict)
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        branching.onConflict(varRef, BranchingConflict(unsat.conflictBools, unsat.conflictInts))
    }

    override fun onCommit(varRef: VarRef) {
        branching.onCommit(varRef)
    }

    override fun onRestart() {
        branching.onRestart()
    }

    override val tracksUnassign: Boolean get() = branching.tracksUnassign

    override fun onUnassign(varRef: VarRef) {
        branching.onUnassign(varRef)
    }

    private fun viewFor(session: PropagationSession): BranchingState {
        val cached = view
        if (cached != null && viewOf === session) return cached
        return CpBranchingState(session).also {
            view = it
            viewOf = session
        }
    }
}
