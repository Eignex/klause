package com.eignex.klause.factor.global

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

/** LS invariant logic for `value_precede`. */
internal class ValuePrecedeInvariant(private val s: Int, private val t: Int, private val xs: IntArray) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val i = firstStOccurrence(state)
        return i >= 0 && state.assignment.intValue(xs[i]) == t.toLong()
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(badTCount(state, intVar = -1, newValue = 0).toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val cap = state.violationSoftCap
        val after = compressViolation(badTCount(state, intVar, newValue).toLong(), cap)
        val before = compressViolation(badTCount(state, -1, 0).toLong(), cap)
        return after - before
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int = 0

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        var firstS = xs.size
        var firstBadT = -1
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            if (v == s.toLong()) {
                firstS = i
                break
            }
            if (v == t.toLong() && firstBadT < 0) firstBadT = i
        }
        if (firstBadT < 0 || firstBadT > firstS) return
        val badVar = xs[firstBadT]
        val badDom = state.problem.intDomains[badVar]
        for (cand in longArrayOf(badDom.min, badDom.max)) {
            if (cand != t.toLong() && cand in badDom) {
                sink.addChannelingIntSet(state, badVar, cand)
                break
            }
        }
        for (i in 0..firstBadT) {
            val v = xs[i]
            if (s.toLong() in state.problem.intDomains[v] && state.assignment.intValue(v) != s.toLong()) {
                sink.addChannelingIntSet(state, v, s.toLong())
                break
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (xs.isEmpty()) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            val i = state.rng.nextInt(xs.size)
            val v = state.assignment.intValue(xs[i])
            val d = state.problem.intDomains[xs[i]]
            var pick = -1L
            var seen = 0
            d.forEach { w ->
                if (w != v && (w == s.toLong() || (w != t.toLong() && v != s.toLong()))) {
                    seen++
                    if (state.rng.nextInt(seen) == 0) pick = w
                }
            }
            if (pick < 0) continue
            sink.addChannelingIntSet(state, xs[i], pick)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (i in xs.indices) {
            val v = xs[i]
            if (state.assumptions.isFrozenInt(v)) {
                if (state.assignment.intValue(v) == t.toLong()) return false
                continue
            }
            val d = state.problem.intDomains[v]
            var pick = -1L
            d.forEach { if (pick < 0 && it != t.toLong()) pick = it }
            if (pick < 0) return false
            state.assignment.setInt(v, pick)
        }
        return true
    }

    private fun firstStOccurrence(state: LocalSearchState): Int {
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            if (v == s.toLong() || v == t.toLong()) return i
        }
        return -1
    }

    private fun badTCount(state: LocalSearchState, intVar: Int, newValue: Long): Int {
        var count = 0
        for (i in xs.indices) {
            val v = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            if (v == s.toLong()) return count
            if (v == t.toLong()) count++
        }
        return count
    }

    companion object {
        const val STRUCTURED_MOVE_CAP: Int = 4
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }
}
