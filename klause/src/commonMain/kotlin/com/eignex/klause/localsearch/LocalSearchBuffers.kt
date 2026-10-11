package com.eignex.klause.localsearch

import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.IntSwapSet

internal const val INITIALIZATION_BATCH: Int = 256

internal class LocalSearchBuffers(
    val assignment: Assignment,
    val violated: IntSwapSet,
    val factorDegree: IntArray,
    val intPayload: IntArray,
    val longPayload: LongArray,
    val refPayload: Array<Any?>,
    val doublePayload: DoubleArray,
    val tabu: TabuBook,
    val cachedBoolBreakCount: IntArray,
    val cachedBoolMakeCount: IntArray,
    val boolConfChange: BooleanArray,
    val intConfChange: BooleanArray,
    val confNeighbours: BooleanArray,
    val probeTouched: BooleanArray,
    val probeWasViolated: BooleanArray,
) {
    companion object {
        fun preparation(problem: Problem): Sequence<LocalSearchBuffers?> = sequence {
            var assignment: Assignment? = null
            for (part in Assignment.preparation(problem.numBoolVars, problem.numIntVars, problem.numRealVars)) {
                if (part == null) yield(null) else assignment = part
            }
            yield(null)
            var violated: IntSwapSet? = null
            for (part in IntSwapSet.preparation(problem.numFactors, INITIALIZATION_BATCH)) {
                if (part == null) yield(null) else violated = part
            }
            yield(null)
            val factorDegree = IntArray(problem.numFactors)
            yield(null)
            val intPayload = IntArray(problem.numFactors)
            yield(null)
            val longPayload = LongArray(problem.numFactors)
            yield(null)
            val refPayload = arrayOfNulls<Any>(problem.numFactors)
            yield(null)
            val doublePayload = if (problem.numRealVars == 0) EmptyDoubleArray else DoubleArray(problem.numFactors)
            yield(null)
            var tabu: TabuBook? = null
            for (part in TabuBook.preparation(problem)) {
                if (part == null) yield(null) else tabu = part
            }
            yield(null)
            val cachedBoolBreakCount = IntArray(problem.numBoolVars)
            yield(null)
            val cachedBoolMakeCount = IntArray(problem.numBoolVars)
            yield(null)
            val boolConfChange = BooleanArray(problem.numBoolVars)
            yield(null)
            val intConfChange = BooleanArray(problem.numIntVars)
            yield(null)
            val confNeighbours = BooleanArray(problem.numFactors)
            yield(null)
            val probeTouched = BooleanArray(problem.numFactors)
            yield(null)
            val probeWasViolated = BooleanArray(problem.numFactors)
            yield(null)
            for (from in 0 until problem.numBoolVars step INITIALIZATION_BATCH) {
                boolConfChange.fill(true, from, minOf(from + INITIALIZATION_BATCH, problem.numBoolVars))
                yield(null)
            }
            for (from in 0 until problem.numIntVars step INITIALIZATION_BATCH) {
                intConfChange.fill(true, from, minOf(from + INITIALIZATION_BATCH, problem.numIntVars))
                yield(null)
            }
            for (from in 0 until problem.numFactors step INITIALIZATION_BATCH) {
                val end = minOf(from + INITIALIZATION_BATCH, problem.numFactors)
                for (i in from until end) confNeighbours[i] = problem.factors[i] !is ObjectiveBoundFactor
                yield(null)
            }
            yield(LocalSearchBuffers(
                checkNotNull(assignment),
                checkNotNull(violated),
                factorDegree,
                intPayload,
                longPayload,
                refPayload,
                doublePayload,
                checkNotNull(tabu),
                cachedBoolBreakCount,
                cachedBoolMakeCount,
                boolConfChange,
                intConfChange,
                confNeighbours,
                probeTouched,
                probeWasViolated,
            ))
        }
    }
}
