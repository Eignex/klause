package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/** LS contract for [ArrayMinMax]: violation tracking and repair for `result = max/min(xs)`. */
interface ArrayMinMaxInvariant : Invariant {

    /** Result variable id. */
    val result: Int

    /** Operand variable ids. */
    val xs: IntArray

    /** `true` for max, `false` for min. */
    val max: Boolean

    private fun cmp(a: Int, b: Int): Boolean = if (max) a > b else a < b

    private fun computeBest(state: LocalSearchState): Int {
        var best = state.assignment.intValue(xs[0])
        for (i in 1 until xs.size) {
            val v = state.assignment.intValue(xs[i])
            if (cmp(v, best)) best = v
        }
        return best
    }

    private fun degreeFor(resultValue: Int, best: Int, softCap: Int): Int {
        val d = resultValue.toLong() - best
        return compressViolation(if (d < 0) -d else d, softCap)
    }

    private fun simulateBest(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var best = Int.MIN_VALUE
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

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as ArrayMinMaxState
        val oldDeg = degreeFor(state.assignment.intValue(result), s.bestValue, state.violationSoftCap)
        val newBest = simulateBest(state, intVar, newValue)
        val newResult = if (intVar == result) newValue else state.assignment.intValue(result)
        return degreeFor(newResult, newBest, state.violationSoftCap) - oldDeg
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
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

/** Mutable state object for the current best extremum across `xs`. */
class ArrayMinMaxState(
    /** The current min or max value across the operand variables. */
    var bestValue: Int,
)
