package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList

/** LS invariant logic for `symmetric_all_different`. */
internal interface SymmetricAllDifferentInvariant : Invariant {
    val xs: IntArray
    val indexOffset: Int

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        brokenPositions(state, ov = -1, nv = 0) > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(brokenPositions(state, ov = -1, nv = 0).toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val after = brokenPositions(state, ov = intVar, nv = newValue)
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(brokenPositions(state, ov = -1, nv = 0).toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            val target = v - indexOffset
            if (target !in xs.indices) {
                val di = state.problem.intDomains[xs[i]]
                val pick = (i + indexOffset).takeIf { it in di } ?: continue
                if (pick != v) sink.addChannelingIntSet(state, xs[i], pick)
                continue
            }
            val backVal = state.assignment.intValue(xs[target])
            val want = i + indexOffset
            if (backVal != want) {
                if (want in state.problem.intDomains[xs[target]] && want != backVal) {
                    sink.addChannelingIntSet(state, xs[target], want)
                }
                val xiDom = state.problem.intDomains[xs[i]]
                val backTarget = backVal - indexOffset
                if (backTarget in xs.indices) {
                    val candidate = backTarget + indexOffset
                    if (candidate in xiDom && candidate != v) sink.addChannelingIntSet(state, xs[i], candidate)
                }
                val selfPair = i + indexOffset
                if (selfPair in xiDom && selfPair != v) sink.addChannelingIntSet(state, xs[i], selfPair)
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val n = xs.size
        if (n < 2) return
        val fixed = IntArrayList()
        for (i in 0 until n) {
            if (state.assignment.intValue(xs[i]) - indexOffset == i) fixed.add(i)
        }
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            if (state.rng.nextBoolean() && fixed.size >= 2) {
                val i = fixed[state.rng.nextInt(fixed.size)]
                val j = fixed[state.rng.nextInt(fixed.size)]
                if (i == j || xs[i] == xs[j]) continue
                val vi = j + indexOffset
                val vj = i + indexOffset
                if (vi !in state.problem.intDomains[xs[i]] || vj !in state.problem.intDomains[xs[j]]) continue
                sink.addCompound(listOf(Move.IntSet(xs[i], vi), Move.IntSet(xs[j], vj)))
                emitted++
            } else {
                val i = state.rng.nextInt(n)
                val p = state.assignment.intValue(xs[i]) - indexOffset
                if (p == i || p !in 0 until n || xs[i] == xs[p]) continue
                val vi = i + indexOffset
                val vp = p + indexOffset
                if (vi !in state.problem.intDomains[xs[i]] || vp !in state.problem.intDomains[xs[p]]) continue
                sink.addCompound(listOf(Move.IntSet(xs[i], vi), Move.IntSet(xs[p], vp)))
                emitted++
            }
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (i in xs.indices) {
            val target = i + indexOffset
            if (target !in state.problem.intDomains[xs[i]]) return false
            if (state.assumptions.isFrozenInt(xs[i]) && state.assignment.intValue(xs[i]) != target) return false
        }
        for (i in xs.indices) {
            if (!state.assumptions.isFrozenInt(xs[i])) state.assignment.setInt(xs[i], i + indexOffset)
        }
        return true
    }

    private fun brokenPositions(state: LocalSearchState, ov: Int, nv: Int): Int {
        var bad = 0
        for (i in xs.indices) {
            val v = if (xs[i] == ov) nv else state.assignment.intValue(xs[i])
            val target = v - indexOffset
            if (target !in xs.indices) {
                bad++
                continue
            }
            val backVal = if (xs[target] == ov) nv else state.assignment.intValue(xs[target])
            if (backVal != i + indexOffset) bad++
        }
        return bad
    }

    companion object {
        const val STRUCTURED_MOVE_CAP: Int = 4
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }
}
