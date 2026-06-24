package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * LS invariant for [Increasing]. Violation degree is the summed overshoot `Σ max(0, xs(i)+gap −
 * xs(i+1))`, so a move that flattens a steep inversion scores a real improvement. The repair
 * neighbourhood is the point of keeping the chain whole (#896): besides local snaps at the first
 * inversion, it offers two *cascading* compounds that re-monotonise the entire chain in one move —
 * pushing the suffix up or the prefix down — which single-variable moves on a decomposition cannot
 * express.
 */
internal class IncreasingInvariant(private val xs: IntArray, private val gap: Int) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        for (i in 0 until xs.size - 1) {
            if (state.assignment.intValue(xs[i]) + gap > state.assignment.intValue(xs[i + 1])) return true
        }
        return false
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degree(state.violationSoftCap) { state.assignment.intValue(xs[it]) }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val cap = state.violationSoftCap
        val before = degree(cap) { state.assignment.intValue(xs[it]) }
        val after = degree(cap) {
            val v = xs[it]
            if (v == intVar) newValue else state.assignment.intValue(v)
        }
        return after - before
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val cap = state.violationSoftCap
        val after = degree(cap) { state.assignment.intValue(xs[it]) }
        val before = degree(cap) {
            val v = xs[it]
            if (v == intVar) oldValue else state.assignment.intValue(v)
        }
        return after - before
    }

    private inline fun degree(softCap: Int, valueAt: (Int) -> Int): Int {
        var raw = 0L
        for (i in 0 until xs.size - 1) {
            val overshoot = valueAt(i) + gap.toLong() - valueAt(i + 1)
            if (overshoot > 0L) raw += overshoot
        }
        return compressViolation(raw, softCap)
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        var k = -1
        for (i in 0 until xs.size - 1) {
            if (state.assignment.intValue(xs[i]) + gap > state.assignment.intValue(xs[i + 1])) {
                k = i
                break
            }
        }
        if (k < 0) return
        val a = state.assignment.intValue(xs[k])
        val b = state.assignment.intValue(xs[k + 1])
        // Local snaps at the inversion: pull xs(k) down to b−gap, or push xs(k+1) up to a+gap.
        snap(state, sink, xs[k], target = b - gap, lowering = true)
        snap(state, sink, xs[k + 1], target = a + gap, lowering = false)
        // Cascading re-monotonisation of the whole chain in a single compound move.
        proposeCascade(state, sink, raise = true)
        proposeCascade(state, sink, raise = false)
    }

    /** Snap [v] to [target] if reachable; otherwise to the nearest domain bound in that direction. */
    private fun snap(state: LocalSearchState, sink: MoveSink, v: Int, target: Int, lowering: Boolean) {
        val d = state.problem.intDomains[v]
        val cur = state.assignment.intValue(v)
        val pick = when {
            target in d -> target
            lowering && d.min <= target -> d.min
            !lowering && d.max >= target -> d.max
            else -> return
        }
        if (pick != cur) sink.addChannelingIntSet(state, v, pick)
    }

    /**
     * Re-monotonise the chain by sweeping one direction and clamping each variable to the running
     * bound: [raise] pushes the suffix up (`target(i) = max(cur, target(i−1)+gap)`), `!raise` pulls
     * the prefix down (`target(i) = min(cur, target(i+1)−gap)`). Aborts if the running bound leaves a
     * variable's domain (no monotone completion in that direction). Emitted as one compound move.
     */
    private fun proposeCascade(state: LocalSearchState, sink: MoveSink, raise: Boolean) {
        val n = xs.size
        val target = LongArray(n)
        val indices = if (raise) 0 until n else n - 1 downTo 0
        var first = true
        for (i in indices) {
            val d = state.problem.intDomains[xs[i]]
            val cur = state.assignment.intValue(xs[i]).toLong()
            if (first) {
                target[i] = cur
                first = false
            } else {
                val prev = if (raise) target[i - 1] + gap else target[i + 1] - gap
                val want = if (raise) maxOf(cur, prev) else minOf(cur, prev)
                if (if (raise) want > d.max else want < d.min) return
                target[i] = want
            }
        }
        // Distinct-var dedup (last write wins) keeps the compound well-formed if a var repeats.
        val parts = LinkedHashMap<Int, Int>()
        for (i in 0 until n) {
            if (target[i] != state.assignment.intValue(xs[i]).toLong()) parts[xs[i]] = target[i].toInt()
        }
        if (parts.isNotEmpty()) sink.addCompound(parts.map { Move.IntSet(it.key, it.value) })
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // Pre-condition: chain is satisfied. Each variable may move freely within its monotone window
        // [xs(i−1)+gap, xs(i+1)−gap] without breaking the chain — a feasibility-preserving neighbourhood.
        val n = xs.size
        for (i in 0 until n) {
            val cur = state.assignment.intValue(xs[i])
            val d = state.problem.intDomains[xs[i]]
            val lo = if (i == 0) d.min else maxOf(d.min, state.assignment.intValue(xs[i - 1]) + gap)
            val hi = if (i == n - 1) d.max else minOf(d.max, state.assignment.intValue(xs[i + 1]) - gap)
            if (cur - 1 >= lo) sink.addChannelingIntSet(state, xs[i], cur - 1)
            if (cur + 1 <= hi) sink.addChannelingIntSet(state, xs[i], cur + 1)
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        var floor = Long.MIN_VALUE // minimal value the current variable may take
        for (i in xs.indices) {
            val v = xs[i]
            val d = state.problem.intDomains[v]
            if (state.assumptions.isFrozenInt(v)) {
                val fv = state.assignment.intValue(v).toLong()
                if (fv < floor) return false // a frozen value already breaks the chain
                floor = fv + gap
            } else {
                val pick = maxOf(d.min.toLong(), floor)
                if (pick > d.max) return false
                state.assignment.setInt(v, pick.toInt())
                floor = pick + gap
            }
        }
        return true
    }
}
