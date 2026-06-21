package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntHashSet

/** LS invariant logic for `lex_less` / `lex_lesseq`. */
internal class LexLessInvariant(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
    private val strict: Boolean,
) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !satisfied(state)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = lexDegree(
        getX = { state.assignment.intValue(xs[it]) },
        getY = { state.assignment.intValue(ys[it]) },
        softCap = state.violationSoftCap,
    )

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val cap = state.violationSoftCap
        val before = lexDegree(
            getX = { state.assignment.intValue(xs[it]) },
            getY = { state.assignment.intValue(ys[it]) },
            softCap = cap,
        )
        val after = lexDegree(
            getX = {
                val v = xs[it]
                if (v == intVar) newValue else state.assignment.intValue(v)
            },
            getY = {
                val v = ys[it]
                if (v == intVar) newValue else state.assignment.intValue(v)
            },
            softCap = cap,
        )
        return after - before
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (satisfied(state)) return
        val len = minOf(xs.size, ys.size)
        var k = -1
        for (i in 0 until len) {
            val a = state.assignment.intValue(xs[i])
            val b = state.assignment.intValue(ys[i])
            if (a != b) {
                k = i
                break
            }
        }
        if (k < 0) {
            proposePrefixBreak(state, sink, 0)
            return
        }
        val a = state.assignment.intValue(xs[k])
        val b = state.assignment.intValue(ys[k])
        if (a < b) return
        val xV = xs[k]
        val yV = ys[k]
        val dx = state.problem.intDomains[xV]
        val dy = state.problem.intDomains[yV]
        val needXLE = if (strict) b - 1 else b
        val needYGE = if (strict) a + 1 else a
        if (needXLE in dx) {
            sink.addChannelingIntSet(state, xV, needXLE)
        } else if (dx.min <= needXLE) {
            sink.addChannelingIntSet(state, xV, dx.min)
        }
        if (needYGE in dy) {
            sink.addChannelingIntSet(state, yV, needYGE)
        } else if (dy.max >= needYGE) {
            sink.addChannelingIntSet(state, yV, dy.max)
        }
        if (xV != yV && b in dx && a in dy) {
            sink.addCompound(listOf(Move.IntSet(xV, b), Move.IntSet(yV, a)))
        }
        if (a > dx.min) sink.addChannelingIntSet(state, xV, a - 1)
        if (b < dy.max) sink.addChannelingIntSet(state, yV, b + 1)
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val len = minOf(xs.size, ys.size)
        var k = -1
        for (i in 0 until len) {
            if (state.assignment.intValue(xs[i]) != state.assignment.intValue(ys[i])) {
                k = i
                break
            }
        }
        if (k < 0) return
        val prefix = IntHashSet()
        for (i in 0..k) {
            prefix.add(xs[i])
            prefix.add(ys[i])
        }
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            val arr = if (state.rng.nextBoolean()) xs else ys
            if (k + 1 >= arr.size) continue
            val vId = arr[k + 1 + state.rng.nextInt(arr.size - (k + 1))]
            if (prefix.contains(vId)) continue
            val cur = state.assignment.intValue(vId)
            val d = state.problem.intDomains[vId]
            var pick = -1
            var seen = 0
            d.forEach { w ->
                if (w != cur) {
                    seen++
                    if (state.rng.nextInt(seen) == 0) pick = w
                }
            }
            if (pick < 0) continue
            sink.addChannelingIntSet(state, vId, pick)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        val len = minOf(xs.size, ys.size)
        if (len == 0) return false
        val x0 = xs[0]
        val y0 = ys[0]
        if (x0 == y0) return false
        val xv = if (state.assumptions.isFrozenInt(x0)) {
            state.assignment.intValue(x0)
        } else {
            state.problem.intDomains[x0].min
        }
        val yv = if (state.assumptions.isFrozenInt(y0)) {
            state.assignment.intValue(y0)
        } else {
            state.problem.intDomains[y0].max
        }
        if (xv >= yv) return false
        if (!state.assumptions.isFrozenInt(x0)) state.assignment.setInt(x0, xv)
        if (!state.assumptions.isFrozenInt(y0)) state.assignment.setInt(y0, yv)
        for (i in 1 until xs.size) {
            if (!state.assumptions.isFrozenInt(xs[i])) {
                state.assignment.setInt(xs[i], state.problem.intDomains[xs[i]].min)
            }
        }
        for (i in 1 until ys.size) {
            if (!state.assumptions.isFrozenInt(ys[i])) {
                state.assignment.setInt(ys[i], state.problem.intDomains[ys[i]].min)
            }
        }
        return true
    }

    private fun satisfied(state: LocalSearchState): Boolean = compare(
        getX = { state.assignment.intValue(xs[it]) },
        getY = { state.assignment.intValue(ys[it]) },
    )

    private inline fun compare(getX: (Int) -> Int, getY: (Int) -> Int): Boolean {
        val len = minOf(xs.size, ys.size)
        for (i in 0 until len) {
            val a = getX(i)
            val b = getY(i)
            if (a < b) return true
            if (a > b) return false
        }
        return when {
            xs.size == ys.size -> !strict
            xs.size < ys.size -> true
            else -> false
        }
    }

    private inline fun lexDegree(getX: (Int) -> Int, getY: (Int) -> Int, softCap: Int): Int {
        val len = minOf(xs.size, ys.size)
        for (i in 0 until len) {
            val a = getX(i)
            val b = getY(i)
            if (a < b) return 0
            if (a > b) return compressViolation(a.toLong() - b, softCap)
        }
        return when {
            xs.size == ys.size -> if (strict) 1 else 0
            xs.size < ys.size -> 0
            else -> 1
        }
    }

    private fun proposePrefixBreak(state: LocalSearchState, sink: MoveSink, startK: Int) {
        val len = minOf(xs.size, ys.size)
        for (i in startK until len) {
            val a = state.assignment.intValue(xs[i])
            val b = state.assignment.intValue(ys[i])
            val dx = state.problem.intDomains[xs[i]]
            val dy = state.problem.intDomains[ys[i]]
            var added = false
            if (a > dx.min) {
                sink.addChannelingIntSet(state, xs[i], a - 1)
                added = true
            }
            if (b < dy.max) {
                sink.addChannelingIntSet(state, ys[i], b + 1)
                added = true
            }
            if (added) return
        }
    }

    companion object {
        const val STRUCTURED_MOVE_CAP: Int = 4
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }
}
