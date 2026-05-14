package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.localsearch.WarmState
import com.eignex.klause.solver.localsearch.SolverState
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchSession

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams

/**
 * Mutable container for per-strategy state that survives a [LocalSearchSolver] call
 * boundary. Owned by [LocalSearchSession]; never created directly by callers.
 *
 * Currently carries only [factorWeights] (DDFW-style learned weights). Other strategies
 * are stateless across calls today; adding fields here is the path to changing that.
 *
 * `null` fields mean "no warm state available — fall back to the strategy's default
 * initialisation." [reset] returns the state to that empty baseline.
 */
class WarmState {
    /** Per-factor weights, size = `problem.numFactors`, or `null` if not yet populated. */
    internal var factorWeights: DoubleArray? = null

    /** Discard all warm state. The next session call starts from defaults. */
    fun reset() {
        factorWeights = null
    }

    /** Sync warm weights into [state] before the search loop starts. Size-mismatched
     *  warm state is silently dropped — the new Problem's factor count overrides. */
    internal fun applyTo(state: SolverState) {
        val w = factorWeights ?: return
        if (w.size != state.factorWeights.size) return
        for (i in w.indices) state.factorWeights[i] = w[i]
    }

    /** Capture the strategy's learned weights at the end of a search session. */
    internal fun captureFrom(state: SolverState) {
        factorWeights = state.factorWeights.copyOf()
    }
}
