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

    /** Per-variable recency, size = `numBoolVars + numIntVars` (bool ids first, then int
     *  ids offset by `numBoolVars`). Smaller values mean "touched more recently in the
     *  last LS call". `Int.MAX_VALUE` means "never touched in the captured call". `null`
     *  if no call has completed yet. Used by ALNS destroy operators that pick high-
     *  activity variables for re-optimisation. */
    internal var activityRecency: IntArray? = null

    /** Read-only view onto recency for tests / destroy operators. Returns an empty array
     *  when no data has been captured yet. */
    fun activityRecency(): IntArray = activityRecency ?: IntArray(0)

    /** Discard all warm state. The next session call starts from defaults. */
    fun reset() {
        factorWeights = null
        activityRecency = null
    }

    /** Sync warm weights into [state] before the search loop starts. Size-mismatched
     *  warm state is silently dropped — the new Problem's factor count overrides. */
    internal fun applyTo(state: LocalSearchState) {
        val w = factorWeights ?: return
        if (w.size != state.factorWeights.size) return
        for (i in w.indices) state.factorWeights[i] = w[i]
    }

    /** Capture the strategy's learned weights and per-variable recency at the end of a
     *  search session. Recency is `state.step - lastTouched[v]` clamped to `Int`, with
     *  `Int.MAX_VALUE` for untouched vars (lastTouched == 0). */
    internal fun captureFrom(state: LocalSearchState) {
        factorWeights = state.factorWeights.copyOf()
        val total = state.problem.numBoolVars + state.problem.numIntVars
        val recency = IntArray(total)
        for (i in 0 until total) {
            val touched = state.lastTouched[i]
            recency[i] = if (touched == 0L) Int.MAX_VALUE
                         else (state.step - touched).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        activityRecency = recency
    }
}
