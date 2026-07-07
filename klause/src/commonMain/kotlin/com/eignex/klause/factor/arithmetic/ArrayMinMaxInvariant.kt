package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

/** Mutable state object for the current best extremum across `xs`. */
class ArrayMinMaxState(
    /** The current min or max value across the operand variables. */
    var bestValue: Long,
)

/** LS invariant for [ArrayMinMax]: violation tracking and repair for `result = max/min(xs)`. */
internal class ArrayMinMaxInvariant(private val result: Int, private val xs: IntArray, private val max: Boolean) :
    Invariant {

    private fun cmp(a: Long, b: Long): Boolean = if (max) a > b else a < b

    private fun computeBest(state: LocalSearchState): Long {
        var best = state.assignment.intValue(xs[0])
        for (i in 1 until xs.size) {
            val v = state.assignment.intValue(xs[i])
            if (cmp(v, best)) best = v
        }
        return best
    }

    private fun degreeFor(resultValue: Long, best: Long, softCap: Int): Int {
        val d = resultValue - best
        return compressViolation(if (d < 0) -d else d, softCap)
    }

    private fun simulateBest(state: LocalSearchState, intVar: Int, newValue: Long): Long {
        var best = Long.MIN_VALUE
        var init = false
        for (v in xs) {
            val current = if (v == intVar) newValue else state.assignment.intValue(v)
            if (!init) {
                best = current
                init = true
            } else if (cmp(current, best)) {
                best = current
            }
        }
        return best
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = ArrayMinMaxState(computeBest(state))
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as ArrayMinMaxState
        return state.assignment.intValue(result) != s.bestValue
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val s = state.refPayload[factorId] as ArrayMinMaxState
        return degreeFor(state.assignment.intValue(result), s.bestValue, state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val s = state.refPayload[factorId] as ArrayMinMaxState
        val oldDeg = degreeFor(state.assignment.intValue(result), s.bestValue, state.violationSoftCap)
        val newBest = simulateBest(state, intVar, newValue)
        val newResult = if (intVar == result) newValue else state.assignment.intValue(result)
        return degreeFor(newResult, newBest, state.violationSoftCap) - oldDeg
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val s = state.refPayload[factorId] as ArrayMinMaxState
        val resultNow = state.assignment.intValue(result)
        val oldResult = if (intVar == result) oldValue else resultNow
        val oldDeg = degreeFor(oldResult, s.bestValue, state.violationSoftCap)
        s.bestValue = computeBest(state)
        return degreeFor(resultNow, s.bestValue, state.violationSoftCap) - oldDeg
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as ArrayMinMaxState
        val best = s.bestValue
        val rDom = state.problem.intDomains[result]
        if (best in rDom) sink.addChannelingIntSet(state, result, best)
        val rv = state.assignment.intValue(result)
        if ((max && rv > best) || (!max && rv < best)) {
            for (v in xs) {
                if (rv in state.problem.intDomains[v] && rv != state.assignment.intValue(v)) {
                    sink.addChannelingIntSet(state, v, rv)
                    break
                }
            }
        }
    }
}
