package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.isClausal
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet

/**
 * Propagation-engine projection of an immutable `Problem`. It owns the propagator table and every
 * propagation wakeup index, keeping those engine allocations out of the model object.
 */
class PropagationProblem private constructor(
    /** Immutable model data compiled by this projection. */
    val problem: Problem,
    preparedPropagators: Array<out Propagator>?,
) {
    /** Construct a private propagation projection over [problem]. */
    constructor(problem: Problem) : this(problem, null)

    /** Whether this projection can use the packed native-SAT propagation lane. */
    val isNativeSatEligible: Boolean = problem.isClausal()

    /** One propagator per model factor, materialized only by consumers of the general CP lane. */
    val propagators: Array<out Propagator> by lazy {
        preparedPropagators ?: Array(problem.numFactors) { problem.factors[it].propagatorProjection() }
    }

    /** Propagator occurrences indexed by Boolean variable. */
    val boolOccurrences: Array<IntArray> by lazy {
        if (isNativeSatEligible) {
            invert(problem.numBoolVars, { true }) { it.boolVars }
        } else {
            invert(problem.numBoolVars, { propagators[it] !== NoPropagator }) { it.boolVars }
        }
    }

    /** Propagator occurrences indexed by integer variable. */
    val intOccurrences: Array<IntArray> by lazy {
        if (problem.numIntVars == 0) {
            emptyArray()
        } else {
            invert(problem.numIntVars, { propagators[it] !== NoPropagator }) { it.intVars }
        }
    }

    /** Boolean occurrences excluding factors with literal watchers. */
    val nonBoolWatcherBoolOccurrences: Array<IntArray> by lazy {
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

    /** Whether any propagator subscribes to typed integer-domain events. */
    val usesIntEventWatchers: Boolean by lazy {
        !isNativeSatEligible && propagators.any { it.initialIntEventWatches != null }
    }

    /** Whether any propagator consumes its dirty integer-variable delta. */
    val usesIntEventDeltaConsumers: Boolean by lazy {
        !isNativeSatEligible && propagators.any { it.consumesIntEventDelta }
    }

    /** Integer occurrences excluding factors with typed event subscriptions for that variable. */
    val nonIntEventWatcherIntOccurrences: Array<IntArray> by lazy {
        if (!usesIntEventWatchers) {
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
    }

    internal val clauseArena: ClauseArena by lazy { ClauseArena.of(problem) }

    internal companion object {
        fun preparation(problem: Problem): Iterator<PropagationProblem?> = sequence {
            val propagators = Array<Propagator>(problem.numFactors) { NoPropagator }
            for (fid in propagators.indices) {
                propagators[fid] = problem.factors[fid].propagatorProjection()
                if ((fid + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            yield(PropagationProblem(problem, propagators))
        }.iterator()
    }

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
