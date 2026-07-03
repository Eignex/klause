package com.eignex.klause.factor.global

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.global.internals.proposeRandomSwaps
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap

/** LS invariant logic for `sort`. */
internal class SortInvariant(private val xs: IntArray, private val ys: IntArray) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = mismatches(state, ov = -1, nv = 0) > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(mismatches(state, ov = -1, nv = 0).toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val before = mismatches(state, ov = -1, nv = 0)
        val after = mismatches(state, ov = intVar, nv = newValue)
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val xsVals = IntArray(xs.size) { state.assignment.intValue(xs[it]) }
        val ysVals = IntArray(ys.size) { state.assignment.intValue(ys[it]) }
        val sortedXs = xsVals.copyOf().also { it.sort() }
        for (i in ys.indices) {
            val target = sortedXs[i]
            if (target != ysVals[i] && target in state.problem.intDomains[ys[i]]) {
                sink.addChannelingIntSet(state, ys[i], target)
            }
        }
        val xsCount = MutableIntIntMap().also { for (v in xsVals) it.addTo(v, 1) }
        val ysCount = MutableIntIntMap().also { for (v in ysVals) it.addTo(v, 1) }
        val over = IntArrayList()
        val under = IntArrayList()
        xsCount.forEach { v, c -> if (c > ysCount.getOrDefault(v, 0)) over.add(v) }
        ysCount.forEach { v, c -> if (c > xsCount.getOrDefault(v, 0)) under.add(v) }
        for (oi in 0 until over.size) {
            val v = over[oi]
            for (ui in 0 until under.size) {
                val vPrime = under[ui]
                for (k in xs.indices) {
                    if (xsVals[k] == v && vPrime in state.problem.intDomains[xs[k]]) {
                        sink.addChannelingIntSet(state, xs[k], vPrime)
                        break
                    }
                }
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        proposeRandomSwaps(state, xs, sink, STRUCTURED_SWAP_CAP, SWAP_ATTEMPT_STRIDE) { _, _ -> true }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        val sortedXs = IntArray(xs.size) { state.assignment.intValue(xs[it]) }.also { it.sort() }
        for (i in ys.indices) {
            val target = sortedXs[i]
            if (target !in state.problem.intDomains[ys[i]]) return false
            if (state.assumptions.isFrozenInt(ys[i]) && state.assignment.intValue(ys[i]) != target) return false
        }
        for (i in ys.indices) {
            if (!state.assumptions.isFrozenInt(ys[i])) state.assignment.setInt(ys[i], sortedXs[i])
        }
        return true
    }

    private fun mismatches(state: LocalSearchState, ov: Int, nv: Int): Int {
        val xsVals = IntArray(xs.size) { i -> if (xs[i] == ov) nv else state.assignment.intValue(xs[i]) }
            .also { it.sort() }
        val ysVals = IntArray(ys.size) { i -> if (ys[i] == ov) nv else state.assignment.intValue(ys[i]) }
        var m = 0
        for (i in ysVals.indices) if (ysVals[i] != xsVals[i]) m++
        return m
    }

    companion object {
        const val STRUCTURED_SWAP_CAP: Int = 4
        const val SWAP_ATTEMPT_STRIDE: Int = 6
    }
}
