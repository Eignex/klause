package com.eignex.klause.factor.global

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.global.internals.countPresentOccurrences
import com.eignex.klause.factor.global.internals.proposeRandomRotations
import com.eignex.klause.factor.global.internals.proposeRandomSwaps
import com.eignex.klause.factor.global.internals.reginTryAugment
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.randomValue
import com.eignex.klause.ir.values
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.WIDE_DOMAIN_DRAWS
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableLongIntMap

internal class AllDifferentInvariant(
    private val vars: IntArray,
    private val presents: IntArray,
    private val exceptValues: LongHashSet,
    private val occurrencesByVar: IntIntMap,
    private val presentInvFn: (LocalSearchState, Int) -> Boolean,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = MutableLongIntMap()
        var excess = 0
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue
            if (counts.addTo(value, 1) >= 2) excess++
        }
        state.refPayload[factorId] = AllDifferentState(counts, excess)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as AllDifferentState
        return s.excess > 0
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as AllDifferentState).excess.toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val s = state.refPayload[factorId] as AllDifferentState
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        val n = presentOccurrences(state, intVar)
        if (n == 0) return 0
        var rawDelta = 0
        if (old !in exceptValues) {
            val oldCount = s.counts.getOrDefault(old, 0)
            rawDelta += excessOf(oldCount - n) - excessOf(oldCount)
        }
        if (newValue !in exceptValues) {
            val newCount = s.counts.getOrDefault(newValue, 0)
            rawDelta += excessOf(newCount + n) - excessOf(newCount)
        }
        return compressViolation((s.excess + rawDelta).toLong(), state.violationSoftCap) -
            compressViolation(s.excess.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val s = state.refPayload[factorId] as AllDifferentState
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val n = presentOccurrences(state, intVar)
        if (n == 0) return 0
        val before = s.excess
        if (oldValue !in exceptValues) {
            val oldCount = s.counts.getOrDefault(oldValue, 0)
            s.counts.put(oldValue, oldCount - n)
            s.excess += excessOf(oldCount - n) - excessOf(oldCount)
        }
        if (cur !in exceptValues) {
            val newCount = s.counts.getOrDefault(cur, 0)
            s.counts.put(cur, newCount + n)
            s.excess += excessOf(newCount + n) - excessOf(newCount)
        }
        return compressViolation(s.excess.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as AllDifferentState
        val touchedVal = LongArrayList()
        val touchedDelta = IntArrayList()
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue
            val wasP = presentInvFn(state, i)
            val delta = if (wasP) -1 else +1
            touchedVal.add(value)
            touchedDelta.add(delta)
        }
        var excessDelta = 0
        val snapshot = s.counts
        for (k in 0 until touchedVal.size) excessDelta += adjustExcess(snapshot, touchedVal[k], touchedDelta[k])
        for (k in touchedVal.size - 1 downTo 0) adjustExcess(snapshot, touchedVal[k], -touchedDelta[k])
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
            s.excess += adjustExcess(s.counts, value, delta)
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
        val used = LongHashSet(vars.size)
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val v = vars[i]
            if (!state.assumptions.isFrozenInt(v)) continue
            val value = state.assignment.intValue(v)
            if (value !in exceptValues && !used.add(value)) return false
        }
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val v = vars[i]
            if (state.assumptions.isFrozenInt(v)) continue
            val d = state.rootDomains[v]
            var chosen: Long? = null
            var candidate = d.min
            while (true) {
                if (candidate in exceptValues || candidate !in used) {
                    chosen = candidate
                    break
                }
                if (candidate == d.max) break
                candidate = d.higher(candidate)
            }
            val value = chosen ?: return false
            state.assignment.setInt(v, value)
            if (value !in exceptValues) used.add(value)
        }
        return seededDistinct(state)
    }

    private fun seedByMatching(state: LocalSearchState): Boolean {
        val taken = LongHashSet()
        val freeVars = IntArrayList()
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val v = vars[i]
            if (!state.assumptions.isFrozenInt(v)) {
                freeVars.add(v)
                continue
            }
            val value = state.assignment.intValue(v)
            if (value !in exceptValues && !taken.add(value)) return false
        }
        val values = LongArrayList()
        val ids = MutableLongIntMap()
        val m = freeVars.size
        val valuesPerVar = Array(m) { k ->
            val allowed = IntArrayList()
            val domain = state.rootDomains[freeVars[k]]
            var candidate = domain.min
            while (true) {
                if (candidate !in exceptValues && candidate !in taken) {
                    var id = ids.getOrDefault(candidate, -1)
                    if (id < 0) {
                        id = values.size
                        values.add(candidate)
                        ids.put(candidate, id)
                    }
                    allowed.add(id)
                }
                // A domain retaining m candidates cannot violate Hall for any subset of m free positions.
                if (allowed.size == m || candidate == domain.max) break
                candidate = domain.higher(candidate)
            }
            allowed.toIntArray()
        }
        val matchVar = IntArray(m) { -1 }
        val matchVal = IntArray(values.size) { -1 }
        val visited = BooleanArray(values.size)
        for (k in 0 until m) {
            visited.fill(false)
            reginTryAugment(k, valuesPerVar, matchVar, matchVal, visited)
        }
        for (k in 0 until m) {
            val v = freeVars[k]
            val id = matchVar[k]
            val value = if (id >= 0) values[id] else {
                var fallback: Long? = null
                exceptValues.forEach { if (fallback == null && it in state.rootDomains[v]) fallback = it }
                fallback ?: return false
            }
            state.assignment.setInt(v, value)
        }
        return seededDistinct(state)
    }

    private fun seededDistinct(state: LocalSearchState): Boolean {
        val used = LongHashSet()
        for (i in vars.indices) {
            if (!presentInvFn(state, i)) continue
            val value = state.assignment.intValue(vars[i])
            if (value !in exceptValues && !used.add(value)) return false
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as AllDifferentState
        if (s.excess == 0) return
        // Collect the duplicated values and scan them value-ascending, so the reservoir pick draws
        // from the rng in a stable order (the count map's iteration order is unspecified).
        val dupValues = LongArrayList()
        s.counts.forEach { v, count -> if (count > 1) dupValues.add(v) }
        if (dupValues.isEmpty()) return
        dupValues.sort()
        var value = 0L
        var seenDups = 0
        for (k in 0 until dupValues.size) {
            seenDups++
            if (state.rng.nextInt(seenDups) == 0) value = dupValues[k]
        }
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
        val d = state.rootDomains[occupant]
        val targets = LongArray(MAX_REPAIR_TARGETS) { Long.MIN_VALUE }
        var filled = 0
        var seenTargets = 0
        d.forEachRepairValue(state) { target ->
            if (target != value && s.counts.getOrDefault(target, 0) == 0) {
                seenTargets++
                if (filled < MAX_REPAIR_TARGETS) {
                    targets[filled++] = target
                } else {
                    val r = state.rng.nextInt(seenTargets)
                    if (r < MAX_REPAIR_TARGETS) targets[r] = target
                }
            }
        }
        if (filled > 0) {
            for (i in 0 until filled) sink.addChannelingIntSet(state, occupant, targets[i])
            return
        }
        var swapsAdded = 0
        for (holder in vars.distinct()) {
            if (swapsAdded >= MAX_SWAP_CANDIDATES) break
            val wLong = state.assignment.intValue(holder)
            if (wLong == value || wLong !in d || s.counts.getOrDefault(wLong, 0) != 1) continue
            if (holder == occupant) continue
            val hd = state.rootDomains[holder]
            if (value !in hd) continue
            sink.addCompound(listOf(Move.IntSet(occupant, wLong), Move.IntSet(holder, value)))
            swapsAdded++
        }
        if (swapsAdded > 0) return
        val cur = state.assignment.intValue(occupant)
        if (cur < d.max) sink.addChannelingIntSet(state, occupant, d.higher(cur))
        if (cur > d.min) sink.addChannelingIntSet(state, occupant, d.lower(cur))
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        proposeRandomSwaps(state, vars, sink, MAX_STRUCTURED_SWAPS, SWAP_ATTEMPT_STRIDE) { s, idx ->
            presentInvFn(s, idx)
        }
    }

    internal val swapCoordinateCount: Int get() = vars.size

    internal fun proposeOwnedSwaps(state: LocalSearchState, factorId: Int, variable: Int, sink: MoveSink) {
        if (presents.isNotEmpty() || !exceptValues.isEmpty()) return
        if (state.seeding.ownerInt?.get(variable) != factorId || isViolated(state, factorId)) return
        val left = vars.indexOf(variable)
        if (left < 0) return
        val previous = sink.proposer
        val before = sink.size
        val start = state.rng.nextInt(vars.size)
        try {
            sink.proposer = factorId
            if (!allowsSwapCoordinate(state, left, sink)) return
            val attempts = minOf(vars.size, MAX_STRUCTURED_SWAPS * SWAP_ATTEMPT_STRIDE)
            for (step in 0 until attempts) {
                proposeSwap(state, left, (start + step) % vars.size, sink)
                if (sink.size - before >= MAX_STRUCTURED_SWAPS) break
            }
        } finally {
            sink.proposer = previous
        }
    }

    internal fun allowsSwapCoordinate(state: LocalSearchState, index: Int, sink: MoveSink): Boolean =
        presentInvFn(state, index) && sink.allowsInt(vars[index]) &&
            state.rootDomains[vars[index]].min != state.rootDomains[vars[index]].max

    internal fun proposeSwap(state: LocalSearchState, ai: Int, bi: Int, sink: MoveSink) {
        val a = vars[ai]
        val b = vars[bi]
        if (a == b || !presentInvFn(state, ai) || !presentInvFn(state, bi)) return
        if (!sink.allowsInt(a) || !sink.allowsInt(b)) return
        val va = state.assignment.intValue(a)
        val vb = state.assignment.intValue(b)
        if (va == vb || vb !in state.rootDomains[a] || va !in state.rootDomains[b]) return
        sink.addCompound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
    }

    override fun proposeExtendedStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        proposeRandomRotations(state, vars, sink, MAX_STRUCTURED_SWAPS, SWAP_ATTEMPT_STRIDE) { s, idx ->
            presentInvFn(s, idx)
        }
    }

    private fun IntDomain.forEachRepairValue(state: LocalSearchState, action: (Long) -> Unit) {
        if (spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
            values.forEach(action)
        } else {
            action(min)
            action(max)
            repeat(WIDE_DOMAIN_DRAWS) { action(randomValue(state.rng)) }
        }
    }

    private fun excessOf(count: Int): Int = if (count > 1) count - 1 else 0

    private fun presentOccurrences(state: LocalSearchState, intVar: Int): Int {
        if (presents.isEmpty()) return occurrencesByVar[intVar]
        return countPresentOccurrences(vars, intVar, state) { s, i -> presentInvFn(s, i) }
    }

    private fun adjustExcess(counts: MutableLongIntMap, value: Long, delta: Int): Int {
        val before = counts.getOrDefault(value, 0)
        val after = before + delta
        counts.put(value, after)
        return excessOf(after) - excessOf(before)
    }

    companion object {
        val NO_EXCEPT = LongHashSet()
        const val MAX_STRUCTURED_SWAPS: Int = 4
        const val SWAP_ATTEMPT_STRIDE: Int = 6
        const val MAX_REPAIR_TARGETS: Int = 4
        const val MAX_SWAP_CANDIDATES: Int = 2
    }
}

internal class AllDifferentState(val counts: MutableLongIntMap, var excess: Int)
