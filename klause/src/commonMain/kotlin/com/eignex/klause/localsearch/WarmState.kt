package com.eignex.klause.localsearch

import com.eignex.klause.util.EmptyIntArray

/**
 * Mutable container for per-strategy state that survives a [LocalSearchSolver] call boundary. Owned
 * by [LocalSearchSession]; never created directly by callers.
 *
 * `null` fields mean "no warm state available — fall back to the strategy's default initialisation."
 * [reset] returns the state to that empty baseline.
 */
internal class WarmState {
    /** Per-factor weights, size = `problem.numFactors`, or `null` if not yet populated. */
    internal var factorWeights: DoubleArray? = null

    /** Per-variable cumulative touch count, size = `numBoolVars + numIntVars` (bool ids
     *  first, int ids offset by `numBoolVars`). Larger values mean "touched more often
     *  during the search run". `null` if no call has completed yet. Used by ALNS destroy
     *  operators that pick high-activity variables for re-optimisation. Cumulative across
     *  restart epochs within a call — survives [LocalSearchState.restart]. */
    internal var activityTouches: IntArray? = null

    /** Lowest [LocalSearchState.cost] observed across every call made through the session.
     *  Persisted so `AspirationCriterion.OrImprovesBestEver` sees the full-history low,
     *  not just the current call's watermark. `Long.MAX_VALUE` until the first apply. */
    internal var bestCostSeen: Long = Long.MAX_VALUE

    /** Read-only view onto activity counts for tests / destroy operators. Returns an
     *  empty array when no data has been captured yet. */
    fun activityTouches(): IntArray = activityTouches ?: EmptyIntArray

    /** Read-only handle for tests / diagnostics. Returns `Long.MAX_VALUE` if no apply has
     *  happened yet through this session. */
    fun bestCostSeen(): Long = bestCostSeen

    /** Discard all warm state. The next session call starts from defaults. */
    fun reset() {
        factorWeights = null
        activityTouches = null
        bestCostSeen = Long.MAX_VALUE
    }

    /** Sync warm weights, activity counts, and the best-cost watermark into [state]
     *  before the search loop starts. Size-mismatched warm state is silently dropped —
     *  the new Problem's factor / var count overrides. Restoring [activityTouches] lets
     *  touch counts accumulate across session calls (which is what `activityBiased`
     *  ALNS destroy keys on); restoring [bestCostSeen] lets `OrImprovesBestEver`
     *  aspiration see the full-history low. */
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
        if (bestCostSeen < state.bestCostSeen) state.bestCostSeen = bestCostSeen
    }

    /** Capture the strategy's learned weights, per-variable touch counts, and best-cost
     *  watermark at the end of a search session. Touch counts and the watermark survive
     *  restart, so they reflect the whole call regardless of restart cadence.
     *
     *  Skips the weight copy when the state never allocated [LocalSearchState.factorWeights]
     *  — happens whenever the strategy was weight-blind (WalkSat, ProbSat, etc.).
     *  Capturing a freshly-allocated all-1.0 default would force the allocation we just
     *  avoided. */
    internal fun captureFrom(state: LocalSearchState) {
        if (state.factorWeightsAllocated) {
            factorWeights = state.factorWeights.copyOf()
        }
        activityTouches = state.touchCount.copyOf()
        // Monotone-decreasing: never let the warm watermark go up between calls.
        if (state.bestCostSeen < bestCostSeen) bestCostSeen = state.bestCostSeen
    }
}
