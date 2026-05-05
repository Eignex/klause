package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/**
 * `a * b = result`. Operates on signed integer domains (any min/max). The bit-blaster lowers
 * via an unsigned shift-and-add multiplier on absolute values, then conditionally negates the
 * product based on the operand sign bits.
 *
 * No payload: the product is recomputed in O(1) from the current assignment on each query.
 */
class Product(
    val a: Int,
    val b: Int,
    val result: Int,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = intArrayOf(a, b, result)

    override fun initialize(state: SolverState, factorId: Int) {}

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        return av * bv != rv
    }

    override fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        val was = av * bv != rv
        val will = when (intVar) {
            a -> newValue * bv != rv
            b -> av * newValue != rv
            result -> av * bv != newValue
            else -> return 0
        }
        return (if (will) 1 else 0) - (if (was) 1 else 0)
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        val now = av * bv != rv
        val was = when (intVar) {
            a -> oldValue * bv != rv
            b -> av * oldValue != rv
            result -> av * bv != oldValue
            else -> return 0
        }
        return (if (now) 1 else 0) - (if (was) 1 else 0)
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val av = state.assignment.intValue(a)
        val bv = state.assignment.intValue(b)
        val rv = state.assignment.intValue(result)
        if (av * bv == rv) return
        // Candidate 1: snap result = a*b.
        val rTarget = av * bv
        val rClamped = state.problem.intDomains[result].clamp(rTarget)
        if (rClamped == rTarget && rClamped != rv) sink.addIntSet(result, rClamped)
        // Candidate 2: if b ≠ 0 and result divisible by b, snap a = result/b.
        if (bv != 0 && rv % bv == 0) {
            val aTarget = rv / bv
            val aClamped = state.problem.intDomains[a].clamp(aTarget)
            if (aClamped == aTarget && aClamped != av) sink.addIntSet(a, aClamped)
        }
        // Candidate 3: if a ≠ 0 and result divisible by a, snap b = result/a.
        if (av != 0 && rv % av == 0) {
            val bTarget = rv / av
            val bClamped = state.problem.intDomains[b].clamp(bTarget)
            if (bClamped == bTarget && bClamped != bv) sink.addIntSet(b, bClamped)
        }
        // Fall back to ±1 nudges if none of the snap candidates apply.
        for (v in intArrayOf(a, b, result)) {
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            if (cur < d.max) sink.addIntSet(v, cur + 1)
            if (cur > d.min) sink.addIntSet(v, cur - 1)
        }
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
