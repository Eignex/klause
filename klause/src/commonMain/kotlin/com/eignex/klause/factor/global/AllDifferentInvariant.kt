package com.eignex.klause.factor.global

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.global.internals.countPresentOccurrences
import com.eignex.klause.factor.global.internals.proposeRandomRotations
import com.eignex.klause.factor.global.internals.proposeRandomSwaps
import com.eignex.klause.factor.global.internals.reginTryAugment
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap

/** LS invariant logic for `all_different`. */
internal class AllDifferentInvariant(
    private val vars: IntArray,
    private val domainMin: Int,
    private val domainSize: Int,
    private val presents: IntArray,
    private val exceptValues: IntHashSet,
    private val occurrencesByVar: IntIntMap,
    private val presentInvFn: (LocalSearchState, Int) -> Boolean,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        for (v in vars) {
            val d = state.problem.intDomains[v]
            require(d.min >= domainMin && d.max < domainMin + domainSize) {
                "AllDifferent var $v has domain $d outside declared union " +
                    "[$domainMin..${domainMin + domainSize - 1}]"
            }
        }
        val counts = IntArray(domainSize)
        var excess = 0
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue
            val idx = value - domainMin
            val prev = counts[idx]
            counts[idx] = prev + 1
            if (prev >= 1) excess++
        }
        state.refPayload[factorId] = AllDifferentState(counts, excess)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as AllDifferentState
        return s.excess > 0
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as AllDifferentState).excess.toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as AllDifferentState
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        val n = presentOccurrences(state, intVar)
        if (n == 0) return 0
        var rawDelta = 0
        if (old !in exceptValues) {
            val oldCount = s.counts[old - domainMin]
            rawDelta += excessOf(oldCount - n) - excessOf(oldCount)
        }
        if (newValue !in exceptValues) {
            val newCount = s.counts[newValue - domainMin]
            rawDelta += excessOf(newCount + n) - excessOf(newCount)
        }
        return compressViolation((s.excess + rawDelta).toLong(), state.violationSoftCap) -
            compressViolation(s.excess.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as AllDifferentState
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val n = presentOccurrences(state, intVar)
        if (n == 0) return 0
        val before = s.excess
        if (oldValue !in exceptValues) {
            val oldIdx = oldValue - domainMin
            val oldCount = s.counts[oldIdx]
            s.counts[oldIdx] = oldCount - n
            s.excess += excessOf(oldCount - n) - excessOf(oldCount)
        }
        if (cur !in exceptValues) {
            val newIdx = cur - domainMin
            val newCount = s.counts[newIdx]
            s.counts[newIdx] = newCount + n
            s.excess += excessOf(newCount + n) - excessOf(newCount)
        }
        return compressViolation(s.excess.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as AllDifferentState
        val touchedIdx = IntArrayList()
        val touchedDelta = IntArrayList()
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue
            val wasP = presentInvFn(state, i)
            val delta = if (wasP) -1 else +1
            touchedIdx.add(value - domainMin)
            touchedDelta.add(delta)
        }
        var excessDelta = 0
        val snapshot = s.counts
        for (k in 0 until touchedIdx.size) excessDelta += adjustExcess(snapshot, touchedIdx[k], touchedDelta[k])
        for (k in touchedIdx.size - 1 downTo 0) adjustExcess(snapshot, touchedIdx[k], -touchedDelta[k])
        return compressViolation((s.excess + excessDelta).toLong(), state.violationSoftCap) -
            compressViolation(s.excess.toLong(), state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as AllDifferentState
        val before = s.excess
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue
            val nowP = presentInvFn(state, i)
            val delta = if (nowP) +1 else -1
            s.excess += adjustExcess(s.counts, value - domainMin, delta)
        }
        return compressViolation(s.excess.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean =
        seedGreedy(state) || seedByMatching(state)

    /** Fast path: first-fit assignment, each present non-frozen var taking the first in-domain value
     *  that is either an excepted value or not yet used. Mutates as it goes; a `false` result is
     *  retried by [seedByMatching], which recomputes from the frozen vars and overwrites the rest. */
    private fun seedGreedy(state: LocalSearchState): Boolean {
        val used = IntHashSet(vars.size)
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val v = vars[i]
            if (!state.assumptions.isFrozenInt(v)) continue
            val value = state.assignment.intValue(v)
            if (value !in exceptValues) used.add(value)
        }
        var allDistinct = true
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val v = vars[i]
            if (state.assumptions.isFrozenInt(v)) continue
            val d = state.problem.intDomains[v]
            var chosen = Int.MIN_VALUE
            d.forEach { cand ->
                if (chosen == Int.MIN_VALUE && (cand in exceptValues || !used.contains(cand))) chosen = cand
            }
            if (chosen == Int.MIN_VALUE) {
                allDistinct = false
            } else {
                state.assignment.setInt(v, chosen)
                if (chosen !in exceptValues) used.add(chosen)
            }
        }
        return allDistinct
    }

    /**
     * Matching-based seed for tight domains where first-fit gives up but a feasible assignment
     * exists. Present non-frozen vars are matched to distinct non-excepted values via the
     * all-different augmenting-path matcher [reginTryAugment]; frozen present vars pre-occupy their
     * value. A var the matching leaves unmatched takes any excepted value in its domain (excepted
     * values may be shared), and the seed fails only when such a var has no excepted fallback.
     */
    private fun seedByMatching(state: LocalSearchState): Boolean {
        val taken = BooleanArray(domainSize)
        val freeVars = IntArrayList()
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val v = vars[i]
            if (!state.assumptions.isFrozenInt(v)) {
                freeVars.add(v)
                continue
            }
            val value = state.assignment.intValue(v)
            if (value in exceptValues) continue
            val idx = value - domainMin
            if (idx in 0 until domainSize) {
                if (taken[idx]) return false
                taken[idx] = true
            }
        }
        val m = freeVars.size
        val valuesPerVar = Array(m) { k ->
            val d = state.problem.intDomains[freeVars[k]]
            val allowed = IntArrayList()
            d.forEach { cand ->
                val idx = cand - domainMin
                if (idx in 0 until domainSize && cand !in exceptValues && !taken[idx]) allowed.add(idx)
            }
            IntArray(allowed.size) { allowed[it] }
        }
        val matchVar = IntArray(m) { -1 }
        val matchVal = IntArray(domainSize) { -1 }
        val visited = BooleanArray(domainSize)
        for (k in 0 until m) {
            visited.fill(false)
            reginTryAugment(k, valuesPerVar, matchVar, matchVal, visited)
        }
        for (k in 0 until m) {
            val v = freeVars[k]
            val vid = matchVar[k]
            if (vid >= 0) {
                state.assignment.setInt(v, vid + domainMin)
                continue
            }
            var chosen = Int.MIN_VALUE
            state.problem.intDomains[v].forEach { cand ->
                if (chosen == Int.MIN_VALUE &&
                    cand in exceptValues
                ) {
                    chosen = cand
                }
            }
            if (chosen == Int.MIN_VALUE) return false
            state.assignment.setInt(v, chosen)
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as AllDifferentState
        if (s.excess == 0) return
        var pickedIdx = -1
        var seenDups = 0
        for (idx in s.counts.indices) {
            if (s.counts[idx] <= 1) continue
            seenDups++
            if (state.rng.nextInt(seenDups) == 0) pickedIdx = idx
        }
        if (pickedIdx == -1) return
        val value = pickedIdx + domainMin
        var occupant = -1
        var seenOccupants = 0
        for (i in vars.indices) {
            val v = vars[i]
            if (state.assignment.intValue(v) != value) continue
            if (!presentInvFn(state, i)) continue
            seenOccupants++
            if (state.rng.nextInt(seenOccupants) == 0) occupant = v
        }
        if (occupant == -1) return
        val d = state.problem.intDomains[occupant]
        val targets = IntArray(MAX_REPAIR_TARGETS) { Int.MIN_VALUE }
        var filled = 0
        var seenTargets = 0
        d.forEach { target ->
            if (target != value) {
                val tIdx = target - domainMin
                if (tIdx in s.counts.indices && s.counts[tIdx] == 0) {
                    seenTargets++
                    if (filled < MAX_REPAIR_TARGETS) {
                        targets[filled++] = target
                    } else {
                        val r = state.rng.nextInt(seenTargets)
                        if (r < MAX_REPAIR_TARGETS) targets[r] = target
                    }
                }
            }
        }
        if (filled > 0) {
            for (i in 0 until filled) sink.addChannelingIntSet(state, occupant, targets[i])
            return
        }
        var swapsAdded = 0
        for (w in d.min..d.max) {
            if (swapsAdded >= MAX_SWAP_CANDIDATES) break
            if (w == value) continue
            if (w !in d) continue
            val wIdx = w - domainMin
            if (wIdx !in s.counts.indices || s.counts[wIdx] != 1) continue
            var holder = -1
            for (v in vars) {
                if (state.assignment.intValue(v) == w) {
                    holder = v
                    break
                }
            }
            if (holder == -1 || holder == occupant) continue
            val hd = state.problem.intDomains[holder]
            if (value !in hd) continue
            sink.addCompound(listOf(Move.IntSet(occupant, w), Move.IntSet(holder, value)))
            swapsAdded++
        }
        if (swapsAdded > 0) return
        val cur = state.assignment.intValue(occupant)
        if (cur < d.max) sink.addChannelingIntSet(state, occupant, cur + 1)
        if (cur > d.min) sink.addChannelingIntSet(state, occupant, cur - 1)
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        proposeRandomSwaps(state, vars, sink, MAX_STRUCTURED_SWAPS, SWAP_ATTEMPT_STRIDE) { s, idx ->
            presentInvFn(s, idx)
        }
    }

    override fun proposeExtendedStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        proposeRandomRotations(state, vars, sink, MAX_STRUCTURED_SWAPS, SWAP_ATTEMPT_STRIDE) { s, idx ->
            presentInvFn(s, idx)
        }
    }

    private fun excessOf(count: Int): Int = if (count > 1) count - 1 else 0

    private fun presentOccurrences(state: LocalSearchState, intVar: Int): Int {
        if (presents.isEmpty()) return occurrencesByVar[intVar]
        return countPresentOccurrences(vars, intVar, state) { s, i -> presentInvFn(s, i) }
    }

    private fun adjustExcess(counts: IntArray, valueIdx: Int, delta: Int): Int {
        val before = counts[valueIdx]
        val after = before + delta
        counts[valueIdx] = after
        return excessOf(after) - excessOf(before)
    }

    companion object {
        val NO_EXCEPT = IntHashSet()
        const val MAX_STRUCTURED_SWAPS: Int = 4
        const val SWAP_ATTEMPT_STRIDE: Int = 6
        const val MAX_REPAIR_TARGETS: Int = 4
        const val MAX_SWAP_CANDIDATES: Int = 2
    }
}

internal class AllDifferentState(val counts: IntArray, var excess: Int)
