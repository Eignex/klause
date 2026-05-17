package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.localsearch.WarmState
import com.eignex.klause.solver.localsearch.LocalSearchState
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

    /** Per-variable cumulative touch count, size = `numBoolVars + numIntVars` (bool ids
     *  first, int ids offset by `numBoolVars`). Larger values mean "touched more often
     *  during the search run". `null` if no call has completed yet. Used by ALNS destroy
     *  operators that pick high-activity variables for re-optimisation. Cumulative across
     *  restart epochs within a call — survives [LocalSearchState.restart]. */
    internal var activityTouches: IntArray? = null

    /** Read-only view onto activity counts for tests / destroy operators. Returns an
     *  empty array when no data has been captured yet. */
    fun activityTouches(): IntArray = activityTouches ?: IntArray(0)

    /** Discard all warm state. The next session call starts from defaults. */
    fun reset() {
        factorWeights = null
        activityTouches = null
    }

    /** Sync warm weights and activity counts into [state] before the search loop starts.
     *  Size-mismatched warm state is silently dropped — the new Problem's factor / var
     *  count overrides. Restoring [activityTouches] lets touch counts accumulate across
     *  session calls, which is what `activityBiased` ALNS destroy keys on. */
    internal fun applyTo(state: LocalSearchState) {
        factorWeights?.let { w ->
            if (w.size == state.factorWeights.size) {
                for (i in w.indices) state.factorWeights[i] = w[i]
            }
        }
        activityTouches?.let { t ->
            if (t.size == state.touchCount.size) {
                for (i in t.indices) state.touchCount[i] = t[i]
            }
        }
    }

    /** Capture the strategy's learned weights and per-variable touch counts at the end
     *  of a search session. Touch counts survive restart, so they reflect activity over
     *  the whole call regardless of restart cadence. */
    internal fun captureFrom(state: LocalSearchState) {
        factorWeights = state.factorWeights.copyOf()
        activityTouches = state.touchCount.copyOf()
    }
}
