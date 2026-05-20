package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.math.max
import kotlin.math.min

/**
 * Cumulative scheduling constraint: at every integer time point the total resource use of
 * tasks running at that point stays under [capacity]. Task `i` has variable start time
 * `starts[i]`, fixed duration `durations[i] ≥ 0`, fixed resource demand `resources[i] ≥ 0`.
 *
 * Semantics:
 *  - Task `i` occupies the half-open interval `[starts[i], starts[i] + durations[i])`.
 *  - For every integer time point `t`, `Σ_{i: starts[i] ≤ t < starts[i]+durations[i]} resources[i] ≤ capacity`.
 *  - Zero-duration tasks consume no resource and impose no constraint.
 *  - Any task with `resources[i] > capacity` makes the problem trivially infeasible (the
 *    factor still reports a graded overage cost when LS hits such a placement).
 *
 * LS cost is graded:
 *   `cost = Σ_t max(0, usage[t] − capacity)`
 * — broken assignments rank by total energy overflow rather than by a flat boolean,
 * giving the search a real gradient toward the cumulative bound. The factor's binary
 * violation status (returned through [deltaIfIntSet] / [applyIntSet]) flips only on the
 * 0 ↔ positive boundary, matching the rest of the factor catalog; strategies that want
 * the graded value read `state.intPayload[factorId]` directly (as ALNS does).
 *
 * Propagation: **time-tabling**. For every task with overlap window `[lst_i, ect_i)`
 * (latest-start to earliest-completion), `resources[i]` is *mandatory* throughout that
 * window. The summed mandatory profile is built event-by-event in O(n log n); any time
 * point with `Σ mandatory > capacity` proves infeasibility. For each non-fixed task `i`,
 * any candidate start `s` that would push the *post-i* profile (mandatory + r_i during
 * `[s, s+d_i)`) above capacity at some time point is forbidden — the standard
 * time-tabling deduction. Bounds are tightened at the candidate-domain endpoints,
 * mirroring the rest of the factor catalog's bounds-consistency style.
 *
 * Time-tabling is the standard baseline for competitive cumulative propagation. Time-
 * table-edge-finding (Schutt-Feydy-Stuckey 2009) is the natural strengthening; energetic
 * reasoning (Baptiste-Le Pape-Nuijten) the second. Neither is implemented here yet — the
 * current strength matches Choco's default `cumulative_time` and is sufficient for the
 * Challenge JSP / RCPSP families this codebase has tests for.
 *
 * Cost model is dense: the LS payload allocates an `IntArray` of size
 * `horizon = max_i(starts[i].max + durations[i]) − min_i(starts[i].min)`. For Challenge
 * instances this is typically a few hundred; if your horizon explodes past ~1M, prefer a
 * Linear-per-timepoint decomposition.
 */
class Cumulative(
    val starts: IntArray,
    val durations: IntArray,
    val resources: IntArray,
    val capacity: Int,
) : LocalSearchFactor {

    init {
        require(starts.size == durations.size && starts.size == resources.size) {
            "Cumulative arrays must match: starts=${starts.size} durations=${durations.size} resources=${resources.size}"
        }
        require(capacity >= 0) { "Cumulative capacity must be ≥ 0, got $capacity" }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "Cumulative durations[$i] must be ≥ 0, got ${durations[i]}" }
            require(resources[i] >= 0) { "Cumulative resources[$i] must be ≥ 0, got ${resources[i]}" }
        }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = starts

    private val n: Int = starts.size
    private val positionOfVar: Map<Int, Int> = starts.withIndex().associate { (i, v) -> v to i }

    /** LS-side payload. Owns the usage timeline and the running overage. */
    private class LsState(
        val tLow: Int,
        val usage: IntArray,
        var overage: Int,
    )

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val tLow = computeTLow(state)
        val tHigh = computeTHigh(state)
        val size = max(0, tHigh - tLow)
        val usage = IntArray(size)
        for (i in 0 until n) {
            val s = state.assignment.intValue(starts[i])
            val d = durations[i]
            val r = resources[i]
            if (d == 0 || r == 0) continue
            val from = max(0, s - tLow)
            val to = min(size, s + d - tLow)
            for (t in from until to) usage[t] += r
        }
        var ov = 0
        for (t in usage.indices) {
            val u = usage[t]
            if (u > capacity) ov += u - capacity
        }
        val ls = LsState(tLow, usage, ov)
        state.refPayload[factorId] = ls
        state.intPayload[factorId] = ov
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.intPayload[factorId] > 0

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        val ls = state.refPayload[factorId] as LsState
        val oldStart = state.assignment.intValue(intVar)
        if (oldStart == newValue) return 0
        val d = durations[pos]
        val r = resources[pos]
        val oldViolated = ls.overage > 0
        if (d == 0 || r == 0) return 0
        val delta = simulateOverageDelta(ls, oldStart, newValue, d, r)
        val newViolated = (ls.overage + delta) > 0
        return (if (newViolated) 1 else 0) - (if (oldViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        val ls = state.refPayload[factorId] as LsState
        val newValue = state.assignment.intValue(intVar)
        val d = durations[pos]
        val r = resources[pos]
        val oldViolated = ls.overage > 0
        if (oldValue == newValue || d == 0 || r == 0) return 0
        applyOverageDelta(ls, oldValue, newValue, d, r)
        state.intPayload[factorId] = ls.overage
        val newViolated = ls.overage > 0
        return (if (newViolated) 1 else 0) - (if (oldViolated) 1 else 0)
    }

    /**
     * Compute the overage Δ of moving task `pos` from `oldStart` to `newStart` without
     * mutating the timeline. Visits the symmetric difference of the two task intervals.
     */
    private fun simulateOverageDelta(ls: LsState, oldStart: Int, newStart: Int, d: Int, r: Int): Int {
        val usage = ls.usage
        val tLow = ls.tLow
        val size = usage.size
        var delta = 0
        val oldFrom = oldStart - tLow
        val oldTo = oldFrom + d
        val newFrom = newStart - tLow
        val newTo = newFrom + d
        // Slots present in old but not new → usage drops by r.
        for (t in oldFrom until oldTo) {
            if (t in newFrom until newTo) continue
            if (t < 0 || t >= size) continue
            val u = usage[t]
            delta += max(0, u - r - capacity) - max(0, u - capacity)
        }
        // Slots present in new but not old → usage rises by r.
        for (t in newFrom until newTo) {
            if (t in oldFrom until oldTo) continue
            if (t < 0 || t >= size) continue
            val u = usage[t]
            delta += max(0, u + r - capacity) - max(0, u - capacity)
        }
        return delta
    }

    /** Same as [simulateOverageDelta] but mutates the timeline and the cached overage. */
    private fun applyOverageDelta(ls: LsState, oldStart: Int, newStart: Int, d: Int, r: Int) {
        val usage = ls.usage
        val tLow = ls.tLow
        val size = usage.size
        var deltaOv = 0
        val oldFrom = oldStart - tLow
        val oldTo = oldFrom + d
        val newFrom = newStart - tLow
        val newTo = newFrom + d
        for (t in oldFrom until oldTo) {
            if (t in newFrom until newTo) continue
            if (t < 0 || t >= size) continue
            val u = usage[t]
            val nu = u - r
            usage[t] = nu
            deltaOv += max(0, nu - capacity) - max(0, u - capacity)
        }
        for (t in newFrom until newTo) {
            if (t in oldFrom until oldTo) continue
            if (t < 0 || t >= size) continue
            val u = usage[t]
            val nu = u + r
            usage[t] = nu
            deltaOv += max(0, nu - capacity) - max(0, u - capacity)
        }
        ls.overage += deltaOv
    }

    private fun computeTLow(state: LocalSearchState): Int {
        var lo = Int.MAX_VALUE
        for (i in 0 until n) {
            lo = min(lo, min(state.problem.intDomains[starts[i]].min, state.assignment.intValue(starts[i])))
        }
        return if (lo == Int.MAX_VALUE) 0 else lo
    }

    private fun computeTHigh(state: LocalSearchState): Int {
        var hi = Int.MIN_VALUE
        for (i in 0 until n) {
            val d = durations[i]
            val cand = max(state.problem.intDomains[starts[i]].max, state.assignment.intValue(starts[i])) + d
            hi = max(hi, cand)
        }
        return if (hi == Int.MIN_VALUE) 0 else hi
    }

    /**
     * Time-tabling propagation. Builds the mandatory profile from each task's `[lst, ect)`
     * compulsory part; fails on any `Σ > capacity` time point; for every task with a
     * non-fixed start, tightens the start-domain endpoints against placements that would
     * push the profile over capacity within `[s, s+d_i)`.
     *
     * Event-based O(n log n) sweep keeps the work proportional to the number of tasks
     * (not to the horizon length), so the propagator stays cheap even on RCPSP instances
     * with planning horizons in the tens of thousands.
     */
    /** Conflict reason: starts-bound atoms of every task. Cumulative is bound-only
     *  (sweep-line time-tabling tightens start mins/maxes, never excludes interior
     *  start values), so citing the current bound atoms of every task captures the
     *  full cause of any capacity-overload conflict. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, starts, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (n == 0) return true
        // Per-task resource feasibility.
        for (i in 0 until n) {
            if (durations[i] > 0 && resources[i] > capacity) return false
        }
        // Overload check (Vilím 2002 / Schutt-Feydy-Stuckey 2009 simplified). For each
        // anchor LCT τ, let Ω(τ) = { j : LCT(j) ≤ τ }; if Σ_{j∈Ω} dur(j)·res(j) exceeds
        // capacity · (τ − EST(Ω)), the instance is infeasible. Catches conflicts that
        // time-tabling misses (no compulsory parts but combined energy overflows).
        // O(n log n): sort tasks by LCT, accumulate Energy and min-EST in scan order.
        run {
            val idx = IntArray(n) { it }
            // Stable sort by (lct, est) so multiple tasks with the same LCT all anchor
            // through one another in the same iteration.
            val lcts = IntArray(n) { i ->
                val d = state.intDomains[starts[i]]
                d.max + durations[i]
            }
            val ests = IntArray(n) { i -> state.intDomains[starts[i]].min }
            // Sort indices by lcts[idx[i]] ascending.
            val sorted = idx.sortedBy { lcts[it] }.toIntArray()
            var totalEnergy = 0L
            var minEst = Int.MAX_VALUE
            for (k in 0 until n) {
                val j = sorted[k]
                val e = durations[j].toLong() * resources[j].toLong()
                if (e == 0L) continue
                totalEnergy += e
                if (ests[j] < minEst) minEst = ests[j]
                val tau = lcts[j]
                val slack = (tau.toLong() - minEst.toLong()) * capacity.toLong()
                if (totalEnergy > slack) return false
            }
        }
        // 1. Build mandatory profile as an event list. Each task contributes one (lst, +r)
        //    and one (ect, -r) when lst < ect; otherwise no compulsory part.
        val events = ArrayList<IntArray>(n * 2)
        for (i in 0 until n) {
            val d = durations[i]
            val r = resources[i]
            if (d == 0 || r == 0) continue
            val dom = state.intDomains[starts[i]]
            val lst = dom.max
            val ect = dom.min + d
            if (lst < ect) {
                events.add(intArrayOf(lst, +r))
                events.add(intArrayOf(ect, -r))
            }
        }
        events.sortWith(compareBy({ it[0] }, { -it[1] })) // process +deltas before -deltas at same time
        // 2. Sweep; record the per-segment profile level; fail on any segment > capacity.
        val segFrom = IntArray(events.size)
        val segTo = IntArray(events.size)
        val segLevel = IntArray(events.size)
        var segCount = 0
        var level = 0
        var cursor = if (events.isEmpty()) 0 else events[0][0]
        for ((idx, ev) in events.withIndex()) {
            val t = ev[0]
            if (t > cursor && level > 0) {
                segFrom[segCount] = cursor; segTo[segCount] = t; segLevel[segCount] = level
                segCount++
            }
            level += ev[1]
            cursor = t
            if (idx == events.size - 1 || events[idx + 1][0] != t) {
                if (level > capacity) return false
            }
        }
        // 3. Per-task shaving. For each task i with a free start domain, check whether
        //    placing it at the current low / high endpoint would push the mandatory profile
        //    over capacity at any covered segment (after subtracting i's own mandatory
        //    contribution, so a task doesn't conflict with itself).
        for (i in 0 until n) {
            val d = durations[i]
            val r = resources[i]
            if (d == 0 || r == 0) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val lstI = dom.max
            val ectI = dom.min + d
            val ownsMandatory = lstI < ectI
            // Tighten dom.min upward.
            var newMin = dom.min
            while (newMin <= state.intDomains[v].max) {
                if (overloadsAt(segFrom, segTo, segLevel, segCount,
                        newMin, newMin + d, r, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else break
            }
            if (newMin > state.intDomains[v].max) return false
            // LCG antecedents: every start var's bounds contribute to the resource profile.
            val ant = state.composeIntVarAtomAntecedents(starts)
            if (newMin != state.intDomains[v].min && !state.tightenIntMin(v, newMin, ant)) return false
            // Tighten dom.max downward.
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (overloadsAt(segFrom, segTo, segLevel, segCount,
                        newMax, newMax + d, r, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else break
            }
            if (newMax < state.intDomains[v].min) return false
            if (newMax != state.intDomains[v].max && !state.tightenIntMax(v, newMax, ant)) return false
        }
        return true
    }

    /**
     * Returns true iff placing a task (resource `r`, occupying `[s, s + d)`) anywhere in
     * the mandatory-profile segments would push that segment over [capacity] — after
     * subtracting the task's own already-contributed mandatory part on overlapping segments.
     */
    private fun overloadsAt(
        segFrom: IntArray, segTo: IntArray, segLevel: IntArray, segCount: Int,
        s: Int, sPlusD: Int, r: Int,
        ownsMandatory: Boolean, lstI: Int, ectI: Int,
    ): Boolean {
        for (k in 0 until segCount) {
            val from = segFrom[k]; val to = segTo[k]; val lvl = segLevel[k]
            if (to <= s || from >= sPlusD) continue
            var effective = lvl
            if (ownsMandatory) {
                val ovFrom = max(from, lstI); val ovTo = min(to, ectI)
                if (ovFrom < ovTo) effective -= r
            }
            if (effective + r > capacity) return true
        }
        return false
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == 0) return
        val ls = state.refPayload[factorId] as LsState
        // 1. Find the peak time slot — it concentrates the most leverage for a single move.
        var peakT = -1
        var peakV = capacity
        val usage = ls.usage
        for (t in usage.indices) {
            if (usage[t] > peakV) { peakV = usage[t]; peakT = t }
        }
        val tLow = ls.tLow
        // 2. For each task running through the peak slot, propose moves that take it off
        //    the peak — either start it after the peak, or finish it before. Plus ±1
        //    slides as cheap local fallbacks.
        val MAX_TARGETS = 4
        val absT = if (peakT >= 0) peakT + tLow else 0
        for (i in 0 until n) {
            val v = starts[i]
            val cur = state.assignment.intValue(v)
            val d = durations[i]
            val r = resources[i]
            val dom = state.problem.intDomains[v]
            val runsAtPeak = (peakT >= 0 && r > 0 && d > 0 && cur <= absT && absT < cur + d)
            if (runsAtPeak) {
                // Start just after the peak: cur' = absT + 1 (clamped to domain).
                val afterPeak = absT + 1
                if (afterPeak in dom && afterPeak != cur) sink.addIntSet(v, afterPeak)
                // Finish just before the peak: cur' = absT - d.
                val beforePeak = absT - d
                if (beforePeak in dom && beforePeak != cur) sink.addIntSet(v, beforePeak)
            }
            // Local nudges as a robustness fallback for tight windows.
            if (cur < dom.max && cur + 1 != cur) sink.addIntSet(v, cur + 1)
            if (cur > dom.min && cur - 1 != cur) sink.addIntSet(v, cur - 1)
            // A few random alternatives so the search isn't trapped near the peak.
            if (dom.size <= MAX_TARGETS) {
                dom.forEach { target -> if (target != cur) sink.addIntSet(v, target) }
            } else {
                repeat(MAX_TARGETS) {
                    val pick = dom.valueAt(state.rng.nextInt(dom.size))
                    if (pick != cur) sink.addIntSet(v, pick)
                }
            }
        }
    }}
