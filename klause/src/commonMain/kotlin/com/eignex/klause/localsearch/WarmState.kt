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
     *  first, int ids offset by `numBoolVars`), or `null` if no call has completed yet. Read by
     *  the ALNS `activityBiased` destroy operator. Survives [LocalSearchState.restart]. */
    internal var activityTouches: IntArray? = null

    /** Lowest [LocalSearchState.cost] observed across every call made through the session.
     *  Persisted so `AspirationCriterion.OrImprovesBestEver` sees the full-history low,
     *  not just the current call's watermark. `Long.MAX_VALUE` until the first apply. */
    internal var bestCostSeen: Long = Long.MAX_VALUE

    /** Activity counts, or an empty array when none have been captured yet. */
    fun activityTouches(): IntArray = activityTouches ?: EmptyIntArray

    /** `Long.MAX_VALUE` until the first apply through this session. */
    fun bestCostSeen(): Long = bestCostSeen

    /** Discard all warm state so the next session call starts from defaults. */
    fun reset() {
        factorWeights = null
        activityTouches = null
        bestCostSeen = Long.MAX_VALUE
    }

    /** Sync warm weights, activity counts, and the best-cost watermark into [state]
     *  before the search loop starts. Warm arrays whose size does not match the current
     *  problem's factor or variable count are dropped. */
    internal fun applyTo(state: LocalSearchState) {
        applySteps(state).forEach { }
    }

    internal fun applySteps(state: LocalSearchState): Sequence<Unit> = sequence {
        factorWeights?.let { w ->
            yieldAll(state.weights.preparation())
            val target = state.weights.factorWeights
            if (w.size == target.size) {
                for (from in w.indices step INITIALIZATION_BATCH) {
                    w.copyInto(target, from, from, minOf(from + INITIALIZATION_BATCH, w.size))
                    yield(Unit)
                }
            }
        }
        activityTouches?.let { t ->
            val target = state.tabu.touchCount
            if (t.size == target.size) {
                for (from in t.indices step INITIALIZATION_BATCH) {
                    t.copyInto(target, from, from, minOf(from + INITIALIZATION_BATCH, t.size))
                    yield(Unit)
                }
            }
        }
        if (bestCostSeen < state.bestCostSeen) state.bestCostSeen = bestCostSeen
    }

    /** Capture the strategy's learned weights, per-variable touch counts, and best-cost
     *  watermark at the end of a search session. Touch counts and the watermark survive
     *  restart, so they reflect the whole call regardless of restart cadence.
     *
     *  Skips the weight copy when the state never allocated [FactorWeightBook.factorWeights]
     *  (weight-blind strategies such as WalkSat or ProbSat); capturing the all-1.0 default
     *  would force that allocation. */
    internal fun captureFrom(state: LocalSearchState) {
        if (state.weights.allocated) {
            factorWeights = state.weights.factorWeights.copyOf()
        }
        activityTouches = state.tabu.touchCount.copyOf()
        if (state.bestCostSeen < bestCostSeen) bestCostSeen = state.bestCostSeen
    }
}
