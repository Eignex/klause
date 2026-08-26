package com.eignex.klause.propagation

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet

/**
 * Propagation-engine projection of an immutable [Problem]. It owns the propagator table and every
 * propagation wakeup index, keeping those engine allocations out of the model object.
 */
class PropagationProblem(val problem: Problem) {
    val propagators: Array<out Propagator> = Array(problem.numFactors) { problem.factors[it].asPropagator() }

    val boolOccurrences: Array<IntArray> = invert(
        problem.numBoolVars,
        { propagators[it] !== NoPropagator },
    ) { it.boolVars }

    val intOccurrences: Array<IntArray> = invert(
        problem.numIntVars,
        { propagators[it] !== NoPropagator },
    ) { it.intVars }

    val nonBoolWatcherBoolOccurrences: Array<IntArray> = run {
        val watcherFid = BooleanArray(problem.numFactors)
        var any = false
        for (fid in propagators.indices) {
            if (propagators[fid].initialBoolWatchers != null) {
                watcherFid[fid] = true
                any = true
            }
        }
        if (!any) {
            boolOccurrences
        } else {
            Array(problem.numBoolVars) { v ->
            retain(boolOccurrences[v]) { fid -> !watcherFid[fid] }
        }
        }
    }

    val usesIntEventWatchers: Boolean = propagators.any { it.initialIntEventWatches != null }

    val usesIntEventDeltaConsumers: Boolean = propagators.any { it.consumesIntEventDelta }

    val nonIntEventWatcherIntOccurrences: Array<IntArray> = if (!usesIntEventWatchers) {
        intOccurrences
    } else {
        val watchedVarsByFactor = arrayOfNulls<IntHashSet>(problem.numFactors)
        for (fid in propagators.indices) {
            val watches = propagators[fid].initialIntEventWatches ?: continue
            val watched = IntHashSet(watches.size)
            for (watch in watches) watched.add(IntEvent.intVarOf(watch))
            watchedVarsByFactor[fid] = watched
        }
        Array(problem.numIntVars) { v ->
            retain(intOccurrences[v]) { fid -> watchedVarsByFactor[fid]?.contains(v) != true }
        }
    }

    internal val clauseArena: ClauseArena by lazy(LazyThreadSafetyMode.NONE) { ClauseArena.of(problem) }

    private inline fun retain(src: IntArray, keep: (Int) -> Boolean): IntArray {
        var kept = 0
        for (fid in src) if (keep(fid)) kept++
        if (kept == 0) return EmptyIntArray
        val out = IntArray(kept)
        var k = 0
        for (fid in src) if (keep(fid)) out[k++] = fid
        return out
    }

    private inline fun invert(slots: Int, include: (Int) -> Boolean, vars: (Factor) -> IntArray): Array<IntArray> {
        val counts = IntArray(slots)
        problem.factors.forEachIndexed { fid, factor -> if (include(fid)) for (v in vars(factor)) counts[v]++ }
        val out = Array(slots) { if (counts[it] == 0) EmptyIntArray else IntArray(counts[it]) }
        val cursor = IntArray(slots)
        problem.factors.forEachIndexed { fid, factor ->
            if (include(fid)) for (v in vars(factor)) out[v][cursor[v]++] = fid
        }
        return out
    }
}

internal val Problem.clauseArena: ClauseArena get() = PropagationProblem(this).clauseArena
