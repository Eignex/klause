package com.eignex.klause.factor.global

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.global.internals.GccState
import com.eignex.klause.factor.global.internals.countPresentOccurrences
import com.eignex.klause.factor.global.internals.proposeRandomRotations
import com.eignex.klause.factor.global.internals.proposeRandomSwaps
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.values
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap

/** LS invariant logic for `global_cardinality`. */
internal class GlobalCardinalityInvariant(
    private val xs: IntArray,
    private val cover: LongArray,
    private val countVars: IntArray?,
    private val countLow: IntArray?,
    private val countHigh: IntArray?,
    private val closed: Boolean,
    private val presents: IntArray,
    private val coverIndexByValue: MutableLongIntMap,
    private val presentGccInvFn: (LocalSearchState, Int) -> Boolean,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = IntArray(cover.size)
        for (i in xs.indices) {
            if (!presentGccInvFn(state, i)) continue
            val value = state.assignment.intValue(xs[i])
            val idx = coverIndexByValue.getOrDefault(value, -1)
            if (idx < 0) continue
            counts[idx]++
        }
        state.refPayload[factorId] = GccState(counts)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as GccState
        return rawDegree(state, s.counts, ovVar = -1, ovVal = 0L) > 0L
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val s = state.refPayload[factorId] as GccState
        return compressViolation(rawDegree(state, s.counts, ovVar = -1, ovVal = 0L), state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val s = state.refPayload[factorId] as GccState
        val sim = s.counts.copyOf()
        val occurrencesInXs = countPresentOccurrences(xs, intVar, state) { s, i -> presentGccInvFn(s, i) }
        if (occurrencesInXs > 0) {
            val old = state.assignment.intValue(intVar)
            val oldIdx = coverIndexByValue.getOrDefault(old, -1)
            if (oldIdx >= 0) sim[oldIdx] -= occurrencesInXs
            val newIdx = coverIndexByValue.getOrDefault(newValue, -1)
            if (newIdx >= 0) sim[newIdx] += occurrencesInXs
        }
        val after = rawDegree(state, sim, ovVar = intVar, ovVal = newValue)
        return compressViolation(after, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val s = state.refPayload[factorId] as GccState
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val beforeDeg = state.factorDegree[factorId]
        val occurrencesInXs = countPresentOccurrences(xs, intVar, state) { s, i -> presentGccInvFn(s, i) }
        if (occurrencesInXs > 0) {
            val oldIdx = coverIndexByValue.getOrDefault(oldValue, -1)
            if (oldIdx >= 0) s.counts[oldIdx] -= occurrencesInXs
            val curIdx = coverIndexByValue.getOrDefault(cur, -1)
            if (curIdx >= 0) s.counts[curIdx] += occurrencesInXs
        }
        val afterDeg = rawDegree(state, s.counts, ovVar = -1, ovVal = 0L)
        return compressViolation(afterDeg, state.violationSoftCap) - beforeDeg
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as GccState
        val sim = s.counts.copyOf()
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasP = presentGccInvFn(state, i)
            val coverIdx = coverIndexByValue.getOrDefault(state.assignment.intValue(xs[i]), -1)
            if (coverIdx < 0) continue
            sim[coverIdx] += if (wasP) -1 else +1
        }
        val after = countsDegree(state, sim, ovVar = -1, ovVal = 0L) +
            closedDegree(state, ovVar = -1, ovVal = 0L, flipVar = boolVar)
        return compressViolation(after, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as GccState
        val beforeDeg = state.factorDegree[factorId]
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowP = presentGccInvFn(state, i)
            val coverIdx = coverIndexByValue.getOrDefault(state.assignment.intValue(xs[i]), -1)
            if (coverIdx < 0) continue
            s.counts[coverIdx] += if (nowP) +1 else -1
        }
        val afterDeg = rawDegree(state, s.counts, ovVar = -1, ovVal = 0L)
        return compressViolation(afterDeg, state.violationSoftCap) - beforeDeg
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as GccState
        val cvs = countVars
        if (cvs != null) {
            for (k in cover.indices) {
                val cv = cvs[k]
                val cur = state.assignment.intValue(cv)
                if (cur != s.counts[k].toLong() && s.counts[k].toLong() in state.rootDomains[cv]) {
                    sink.addChannelingIntSet(state, cv, s.counts[k].toLong())
                }
            }
        }
        val cLow = countLow
        val cHigh = countHigh
        for (k in cover.indices) {
            val coverVal = cover[k]
            val cnt = s.counts[k]
            val target: Int
            val needIncrease: Boolean
            if (cvs != null) {
                target = state.assignment.intValue(cvs[k]).toInt()
                if (cnt == target) continue
                needIncrease = cnt < target
            } else {
                checkNotNull(cLow)
                checkNotNull(cHigh)
                if (cnt in cLow[k]..cHigh[k]) continue
                needIncrease = cnt < cLow[k]
                target = if (needIncrease) cLow[k] else cHigh[k]
            }
            if (needIncrease) {
                for (i in xs.indices) {
                    if (!presentGccInvFn(state, i)) continue
                    val cur = state.assignment.intValue(xs[i])
                    if (cur != coverVal && coverVal in state.rootDomains[xs[i]]) {
                        sink.addChannelingIntSet(state, xs[i], coverVal)
                    }
                }
            } else {
                for (i in xs.indices) {
                    if (!presentGccInvFn(state, i)) continue
                    val cur = state.assignment.intValue(xs[i])
                    if (cur != coverVal) continue
                    val d = state.rootDomains[xs[i]]
                    var pick: Long? = null
                    d.values.forEach { if (pick == null && it != coverVal) pick = it }
                    val p = pick
                    if (p != null) sink.addChannelingIntSet(state, xs[i], p)
                }
            }
        }
        if (closed) {
            for (i in xs.indices) {
                if (!presentGccInvFn(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if (coverIndexByValue.containsKey(cur)) continue
                val d = state.rootDomains[xs[i]]
                for (cv in cover) {
                    if (cv in d && cv != cur) {
                        sink.addChannelingIntSet(state, xs[i], cv)
                        break
                    }
                }
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) = proposeRandomSwaps(
        state,
        xs,
        sink,
        STRUCTURED_SWAP_CAP,
        SWAP_ATTEMPT_STRIDE,
    ) { s, idx -> presentGccInvFn(s, idx) }

    override fun proposeExtendedStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        proposeRandomRotations(state, xs, sink, STRUCTURED_SWAP_CAP, SWAP_ATTEMPT_STRIDE) { s, idx ->
            presentGccInvFn(s, idx)
        }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        val counts = IntArray(cover.size)
        val free = IntArrayList()
        for (i in xs.indices) {
            if (!presentGccInvFn(state, i)) continue
            if (state.assumptions.isFrozenInt(xs[i])) {
                val idx = coverIndexByValue.getOrDefault(state.assignment.intValue(xs[i]), -1)
                if (idx >= 0) {
                    counts[idx]++
                } else if (closed) {
                    return false
                }
            } else {
                free.add(i)
            }
        }
        val assigned = BooleanArray(xs.size)
        if (countVars == null) {
            val lo = requireNotNull(countLow)
            val hi = requireNotNull(countHigh)
            for (k in cover.indices) {
                while (counts[k] < lo[k]) {
                    val pos = takeFreeFor(state, free, assigned, cover[k]) ?: return false
                    state.assignment.setInt(xs[pos], cover[k])
                    counts[k]++
                }
            }
            for (fi in 0 until free.size) {
                val pos = free[fi]
                if (assigned[pos]) continue
                val pick = pickUnderHigh(state, xs[pos], counts, hi) ?: return false
                state.assignment.setInt(xs[pos], pick)
                val idx = coverIndexByValue.getOrDefault(pick, -1)
                if (idx >= 0) counts[idx]++
            }
        } else {
            val cvArr = checkNotNull(countVars)
            for (fi in 0 until free.size) {
                val pos = free[fi]
                val pick = firstCoverInDomain(state, xs[pos])
                    ?: if (closed) return false else firstInDomain(state, xs[pos])
                state.assignment.setInt(xs[pos], pick)
                val idx = coverIndexByValue.getOrDefault(pick, -1)
                if (idx >= 0) counts[idx]++
            }
            for (k in cover.indices) {
                val cv = cvArr[k]
                if (state.assumptions.isFrozenInt(cv)) {
                    if (state.assignment.intValue(cv) != counts[k].toLong()) return false
                } else {
                    if (counts[k].toLong() !in state.rootDomains[cv]) return false
                    state.assignment.setInt(cv, counts[k].toLong())
                }
            }
        }
        return true
    }

    private fun takeFreeFor(state: LocalSearchState, free: IntArrayList, assigned: BooleanArray, value: Long): Int? {
        for (fi in 0 until free.size) {
            val pos = free[fi]
            if (assigned[pos]) continue
            if (value in state.rootDomains[xs[pos]]) {
                assigned[pos] = true
                return pos
            }
        }
        return null
    }

    private fun pickUnderHigh(state: LocalSearchState, varId: Int, counts: IntArray, hi: IntArray): Long? {
        for (k in cover.indices) {
            if (counts[k] < hi[k] && cover[k] in state.rootDomains[varId]) return cover[k]
        }
        return if (closed) null else firstInDomain(state, varId)
    }

    private fun firstCoverInDomain(state: LocalSearchState, varId: Int): Long? {
        val d = state.rootDomains[varId]
        for (cv in cover) if (cv in d) return cv
        return null
    }

    private fun firstInDomain(state: LocalSearchState, varId: Int): Long = state.rootDomains[varId].min

    private fun countsDegree(state: LocalSearchState, simCounts: IntArray, ovVar: Int, ovVal: Long): Long {
        val cvArr = countVars
        var deg = 0L
        for (k in cover.indices) {
            if (cvArr != null) {
                val expected = if (cvArr[k] == ovVar) ovVal else state.assignment.intValue(cvArr[k])
                val d = expected - simCounts[k]
                deg += if (d < 0) -d else d
            } else {
                val cnt = simCounts[k]
                val lo = requireNotNull(countLow)[k]
                val hi = requireNotNull(countHigh)[k]
                if (cnt < lo) {
                    deg += (lo - cnt).toLong()
                } else if (cnt > hi) {
                    deg += (cnt - hi).toLong()
                }
            }
        }
        return deg
    }

    private fun closedDegree(state: LocalSearchState, ovVar: Int, ovVal: Long, flipVar: Int = -1): Long {
        if (!closed) return 0L
        var deg = 0L
        for (i in xs.indices) {
            val controlled = flipVar >= 0 && presents.isNotEmpty() && Lit.variable(presents[i]) == flipVar
            val p = if (controlled) !presentGccInvFn(state, i) else presentGccInvFn(state, i)
            if (!p) continue
            val v = if (xs[i] == ovVar) ovVal else state.assignment.intValue(xs[i])
            if (!coverIndexByValue.containsKey(v)) deg++
        }
        return deg
    }

    private fun rawDegree(state: LocalSearchState, simCounts: IntArray, ovVar: Int, ovVal: Long): Long =
        countsDegree(state, simCounts, ovVar, ovVal) + closedDegree(state, ovVar, ovVal)

    companion object {
        const val STRUCTURED_SWAP_CAP: Int = 4
        const val SWAP_ATTEMPT_STRIDE: Int = 6
    }
}
