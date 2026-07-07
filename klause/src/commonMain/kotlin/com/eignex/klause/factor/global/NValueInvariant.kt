package com.eignex.klause.factor.global

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.global.internals.countPresentOccurrences
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableLongIntMap

/** LS invariant logic for `nvalue`. */
internal class NValueInvariant(
    private val n: Int,
    private val xs: IntArray,
    private val mode: NValue.Mode,
    private val presents: IntArray,
    private val presentNvInvFn: (LocalSearchState, Int) -> Boolean,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = MutableLongIntMap()
        var distinct = 0
        for (i in xs.indices) {
            if (!presentNvInvFn(state, i)) continue
            val value = state.assignment.intValue(xs[i])
            val prev = counts.getOrDefault(value, 0)
            counts.put(value, prev + 1)
            if (prev == 0) distinct++
        }
        state.refPayload[factorId] = NValueState(counts, distinct)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as NValueState
        return nvDegree(s.distinctCount, state.assignment.intValue(n)) > 0L
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val s = state.refPayload[factorId] as NValueState
        val raw = nvDegree(s.distinctCount, state.assignment.intValue(n))
        return compressViolation(raw, state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val s = state.refPayload[factorId] as NValueState
        val before = nvDegree(s.distinctCount, state.assignment.intValue(n))
        val newDistinct = simulateDistinct(state, s, intVar, newValue)
        val newN = if (intVar == n) newValue else state.assignment.intValue(n)
        return compressViolation(nvDegree(newDistinct, newN), state.violationSoftCap) -
            compressViolation(before, state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val s = state.refPayload[factorId] as NValueState
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val nBefore = if (intVar == n) oldValue else state.assignment.intValue(n)
        val beforeDeg = nvDegree(s.distinctCount, nBefore)
        val occurrences = countPresentOccurrences(xs, intVar, state) { st, i -> presentNvInvFn(st, i) }
        if (occurrences > 0) {
            val oldCount = s.counts.getOrDefault(oldValue, 0)
            val after = oldCount - occurrences
            if (after == 0) {
                s.counts.remove(oldValue)
                s.distinctCount--
            } else {
                s.counts.put(oldValue, after)
            }
            val newCount = s.counts.getOrDefault(cur, 0)
            if (newCount == 0) s.distinctCount++
            s.counts.put(cur, newCount + occurrences)
        }
        val afterDeg = nvDegree(s.distinctCount, state.assignment.intValue(n))
        return compressViolation(afterDeg, state.violationSoftCap) -
            compressViolation(beforeDeg, state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as NValueState
        val nVal = state.assignment.intValue(n)
        val before = nvDegree(s.distinctCount, nVal)
        var distinct = s.distinctCount
        val touched = MutableLongIntMap()
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasP = presentNvInvFn(state, i)
            val value = state.assignment.intValue(xs[i])
            touched.addTo(value, if (wasP) -1 else 1)
        }
        touched.forEach { value, delta ->
            val cntBefore = s.counts.getOrDefault(value, 0)
            val cntAfter = cntBefore + delta
            if (cntBefore == 0 && cntAfter > 0) distinct++
            if (cntBefore > 0 && cntAfter == 0) distinct--
        }
        return compressViolation(nvDegree(distinct, nVal), state.violationSoftCap) -
            compressViolation(before, state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as NValueState
        val nVal = state.assignment.intValue(n)
        val beforeDeg = nvDegree(s.distinctCount, nVal)
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowP = presentNvInvFn(state, i)
            val value = state.assignment.intValue(xs[i])
            if (nowP) {
                val before = s.counts.getOrDefault(value, 0)
                if (before == 0) s.distinctCount++
                s.counts.put(value, before + 1)
            } else {
                if (!s.counts.containsKey(value)) error("nvalue: absent flip without prior count")
                val after = s.counts.getOrDefault(value, 0) - 1
                if (after == 0) {
                    s.counts.remove(value)
                    s.distinctCount--
                } else {
                    s.counts.put(value, after)
                }
            }
        }
        val afterDeg = nvDegree(s.distinctCount, state.assignment.intValue(n))
        return compressViolation(afterDeg, state.violationSoftCap) -
            compressViolation(beforeDeg, state.violationSoftCap)
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as NValueState
        val nv = state.assignment.intValue(n)
        val nDom = state.problem.intDomains[n]
        if (s.distinctCount.toLong() in nDom) sink.addChannelingIntSet(state, n, s.distinctCount.toLong())
        val needIncrease = when (mode) {
            NValue.Mode.Eq -> nv > s.distinctCount
            NValue.Mode.AtLeast -> true
            NValue.Mode.AtMost -> false
        }
        val needDecrease = when (mode) {
            NValue.Mode.Eq -> nv < s.distinctCount
            NValue.Mode.AtLeast -> false
            NValue.Mode.AtMost -> true
        }
        if (!needIncrease && !needDecrease) return
        if (needIncrease) {
            for (i in xs.indices) {
                if (!presentNvInvFn(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if (s.counts.getOrDefault(cur, 0) <= 1) continue
                val d = state.problem.intDomains[xs[i]]
                var pick: Long? = null
                d.forEach { if (pick == null && it != cur && s.counts.getOrDefault(it, 0) == 0) pick = it }
                val p = pick
                if (p != null) sink.addChannelingIntSet(state, xs[i], p)
            }
        }
        if (needDecrease) {
            for (i in xs.indices) {
                if (!presentNvInvFn(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if (s.counts.getOrDefault(cur, 0) > 1) continue
                val d = state.problem.intDomains[xs[i]]
                var pick: Long? = null
                d.forEach { if (pick == null && it != cur && s.counts.getOrDefault(it, 0) > 0) pick = it }
                val p = pick
                if (p != null) sink.addChannelingIntSet(state, xs[i], p)
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as NValueState
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            val i = state.rng.nextInt(xs.size)
            if (!presentNvInvFn(state, i)) continue
            val v = state.assignment.intValue(xs[i])
            var occ = 0
            for (j in xs.indices) if (xs[j] == xs[i] && presentNvInvFn(state, j)) occ++
            val cv = s.counts.getOrDefault(v, 0)
            val vDies = cv - occ == 0
            val d = state.problem.intDomains[xs[i]]
            var pick = -1L
            var seen = 0
            d.forEach { w ->
                if (w != v) {
                    val wBorn = s.counts.getOrDefault(w, 0) == 0
                    if (vDies == wBorn) {
                        seen++
                        if (state.rng.nextInt(seen) == 0) pick = w
                    }
                }
            }
            if (pick < 0) continue
            sink.addChannelingIntSet(state, xs[i], pick)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (i in xs.indices) {
            if (!presentNvInvFn(state, i)) continue
            if (state.assumptions.isFrozenInt(xs[i])) continue
            state.assignment.setInt(xs[i], state.problem.intDomains[xs[i]].min)
        }
        val seen = LongHashSet()
        for (i in xs.indices) if (presentNvInvFn(state, i)) seen.add(state.assignment.intValue(xs[i]))
        val distinct = seen.size
        val nDom = state.problem.intDomains[n]
        val target = when (mode) {
            NValue.Mode.Eq -> distinct.toLong()
            NValue.Mode.AtLeast -> largestInDomainAtMost(nDom, distinct) ?: return false
            NValue.Mode.AtMost -> smallestInDomainAtLeast(nDom, distinct) ?: return false
        }
        if (state.assumptions.isFrozenInt(n)) return nvDegree(distinct, state.assignment.intValue(n)) == 0L
        if (target !in nDom) return false
        state.assignment.setInt(n, target)
        return true
    }

    fun nvDegree(distinct: Int, nVal: Long): Long = when (mode) {
        NValue.Mode.Eq -> if (nVal >= distinct) nVal - distinct else distinct - nVal
        NValue.Mode.AtLeast -> if (nVal > distinct) nVal - distinct else 0
        NValue.Mode.AtMost -> if (distinct > nVal) distinct - nVal else 0
    }

    private fun simulateDistinct(state: LocalSearchState, s: NValueState, intVar: Int, newValue: Long): Int {
        val occurrences = countPresentOccurrences(xs, intVar, state) { st, i -> presentNvInvFn(st, i) }
        if (occurrences == 0) return s.distinctCount
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return s.distinctCount
        var distinct = s.distinctCount
        val oldCount = s.counts.getOrDefault(old, 0)
        if (oldCount - occurrences == 0) distinct--
        val newCount = s.counts.getOrDefault(newValue, 0)
        if (newCount == 0) distinct++
        return distinct
    }

    private fun largestInDomainAtMost(d: IntDomain, bound: Int): Long? {
        if (d.min > bound) return null
        var pick = -1L
        d.forEach { if (it <= bound) pick = it }
        return if (pick < 0) null else pick
    }

    private fun smallestInDomainAtLeast(d: IntDomain, bound: Int): Long? {
        if (d.max < bound) return null
        var pick = -1L
        d.forEach { if (pick < 0 && it >= bound) pick = it }
        return if (pick < 0) null else pick
    }

    companion object {
        const val STRUCTURED_MOVE_CAP: Int = 4
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }
}

internal class NValueState(val counts: MutableLongIntMap, var distinctCount: Int)
