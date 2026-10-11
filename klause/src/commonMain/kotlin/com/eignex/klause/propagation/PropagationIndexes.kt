package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet

internal class PropagationIndexes(
    val boolOccurrences: Array<IntArray>,
    val intOccurrences: Array<IntArray>,
    val nonBoolOccurrences: Array<IntArray>,
    val nonIntOccurrences: Array<IntArray>,
    val intEvents: Boolean,
    val intDeltas: Boolean,
    val nativeEligible: Boolean,
) {
    companion object {
        fun preparation(problem: Problem, propagators: Array<Propagator>): Sequence<PropagationIndexes?> = sequence {
            val boolCounts = IntArray(problem.numBoolVars)
            yield(null)
            val intCounts = IntArray(problem.numIntVars)
            yield(null)
            val nonBoolCounts = IntArray(problem.numBoolVars)
            yield(null)
            val nonIntCounts = IntArray(problem.numIntVars)
            yield(null)
            val watched = arrayOfNulls<IntHashSet>(problem.numFactors)
            yield(null)
            var clausal = problem.numIntVars == 0 && problem.numRealVars == 0 &&
                problem.numBoolVars > 0 && problem.factors.isNotEmpty()
            var events = false
            var deltas = false
            var visits = 0
            for (fid in propagators.indices) {
                clausal = clausal && problem.factors[fid] is com.eignex.klause.factor.bool.Clause
                val prop = propagators[fid]
                val watches = prop.initialIntEventWatches
                if (watches != null) {
                    events = true
                    val vars = IntHashSet(watches.size)
                    for (watch in watches) {
                        vars.add(IntEvent.intVarOf(watch))
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                    }
                    watched[fid] = vars
                }
                deltas = deltas || prop.consumesIntEventDelta
                if (prop !== NoPropagator) {
                    for (v in problem.factors[fid].boolVars) {
                        boolCounts[v]++
                        if (prop.initialBoolWatchers == null) nonBoolCounts[v]++
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                    }
                    for (v in problem.factors[fid].intVars) {
                        intCounts[v]++
                        if (watched[fid]?.contains(v) != true) nonIntCounts[v]++
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                    }
                }
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val bools = arrayOfNulls<IntArray>(boolCounts.size)
            yield(null)
            val ints = arrayOfNulls<IntArray>(intCounts.size)
            yield(null)
            val nonBools = arrayOfNulls<IntArray>(boolCounts.size)
            yield(null)
            val nonInts = arrayOfNulls<IntArray>(intCounts.size)
            yield(null)
            for (v in bools.indices) {
                bools[v] = if (boolCounts[v] == 0) EmptyIntArray else IntArray(boolCounts[v])
                nonBools[v] = if (nonBoolCounts[v] == 0) EmptyIntArray else IntArray(nonBoolCounts[v])
                boolCounts[v] = 0
                nonBoolCounts[v] = 0
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            for (v in ints.indices) {
                ints[v] = if (intCounts[v] == 0) EmptyIntArray else IntArray(intCounts[v])
                nonInts[v] = if (nonIntCounts[v] == 0) EmptyIntArray else IntArray(nonIntCounts[v])
                intCounts[v] = 0
                nonIntCounts[v] = 0
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            for (fid in propagators.indices) {
                val prop = propagators[fid]
                if (prop !== NoPropagator) {
                    for (v in problem.factors[fid].boolVars) {
                        checkNotNull(bools[v])[boolCounts[v]++] = fid
                        if (prop.initialBoolWatchers == null) checkNotNull(nonBools[v])[nonBoolCounts[v]++] = fid
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                    }
                    for (v in problem.factors[fid].intVars) {
                        checkNotNull(ints[v])[intCounts[v]++] = fid
                        if (watched[fid]?.contains(v) != true) checkNotNull(nonInts[v])[nonIntCounts[v]++] = fid
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                    }
                }
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            @Suppress("UNCHECKED_CAST")
            yield(PropagationIndexes(
                bools as Array<IntArray>, ints as Array<IntArray>,
                nonBools as Array<IntArray>, nonInts as Array<IntArray>, events, deltas, clausal,
            ))
        }
    }
}
