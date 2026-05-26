package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
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
 * Time-tabling is the baseline, paired here with an O(n²) Vilím Θ-tree edge-finder
 * (Vilím 2009 / Schutt-Feydy-Stuckey 2009) running off [CumulativeThetaTree]. The
 * edge-finder catches energy-overflow deductions on subsets that have no compulsory
 * profile, which time-tabling cannot see. The Θ-Λ tree variant lowering this to
 * O(kn log n), and energetic reasoning (Baptiste-Le Pape-Nuijten), are deferred until
 * profiling justifies them.
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
    /** Per-task presence literals; empty for the non-opt fast path. Absent tasks contribute
     *  zero energy / zero compulsory part. Theta-tree leaves stay inactive for
     *  definitely-absent tasks; unpinned-presence tasks are excluded from edge-finding too
     *  (they may yet go absent, so they can't sharpen Ω-energy deductions). */
    val presents: IntArray = EmptyIntArray,
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
        require(presents.isEmpty() || presents.size == starts.size) {
            "Cumulative: presents must be empty or match starts arity"
        }
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = starts

    private fun present(state: LocalSearchState, idx: Int): Boolean =
        OptPresence.isPresentInAssignment(presents, idx, state)

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
            if (!present(state, i)) continue
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
        if (!present(state, pos)) return 0
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
        if (!present(state, pos)) return 0
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

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        val oldViolated = ls.overage > 0
        // Simulate the flip's effect on the dense profile.
        var deltaOv = 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = durations[i]
            val r = resources[i]
            if (d == 0 || r == 0) continue
            val wasP = present(state, i)
            val sign = if (wasP) -1 else +1  // flipping removes/adds this task's contribution
            val s = state.assignment.intValue(starts[i])
            val from = max(0, s - ls.tLow)
            val to = min(ls.usage.size, s + d - ls.tLow)
            for (t in from until to) {
                val u = ls.usage[t]
                val nu = u + sign * r
                deltaOv += max(0, nu - capacity) - max(0, u - capacity)
            }
        }
        val newViolated = (ls.overage + deltaOv) > 0
        return (if (newViolated) 1 else 0) - (if (oldViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        val oldViolated = ls.overage > 0
        var deltaOv = 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = durations[i]
            val r = resources[i]
            if (d == 0 || r == 0) continue
            val nowP = present(state, i)
            val sign = if (nowP) +1 else -1
            val s = state.assignment.intValue(starts[i])
            val from = max(0, s - ls.tLow)
            val to = min(ls.usage.size, s + d - ls.tLow)
            for (t in from until to) {
                val u = ls.usage[t]
                val nu = u + sign * r
                ls.usage[t] = nu
                deltaOv += max(0, nu - capacity) - max(0, u - capacity)
            }
        }
        ls.overage += deltaOv
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
        // Per-task resource feasibility — only definitely-present tasks must fit.
        for (i in 0 until n) {
            if (OptPresence.isDefinitelyAbsent(presents, i, state)) continue
            // Unpinned-presence tasks may still be skipped; check feasibility only when
            // definitely present, otherwise the task could legally vanish.
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
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
                // Skip tasks that aren't definitely-present — they may yet vanish and so
                // cannot anchor an overload conclusion.
                if (!OptPresence.isDefinitelyPresent(presents, j, state)) continue
                val e = durations[j].toLong() * resources[j].toLong()
                if (e == 0L) continue
                totalEnergy += e
                if (ests[j] < minEst) minEst = ests[j]
                val tau = lcts[j]
                val slack = (tau.toLong() - minEst.toLong()) * capacity.toLong()
                if (totalEnergy > slack) return false
            }
        }
        // Edge-finding pass (Vilím 2009 via Θ-tree envelope).
        if (!edgeFindingPass(state)) return false
        // 1. Build mandatory profile as an event list. Each task contributes one (lst, +r)
        //    and one (ect, -r) when lst < ect; otherwise no compulsory part.
        val events = ArrayList<IntArray>(n * 2)
        for (i in 0 until n) {
            // Compulsory part only from definitely-present tasks.
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
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
            // Don't shave start domains of tasks that may yet vanish — if i goes absent
            // it imposes no constraint at all, so tightening its start would be unsound.
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
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

    /**
     * Vilím cumulative edge-finding using [CumulativeThetaTree].
     *
     * For each LCT threshold τ (swept in ascending order), the tree's active set Θ_τ
     * contains every task j with `lct(j) ≤ τ`, and the root envelope
     *   env(Θ_τ) = max_{Ω ⊆ Θ_τ, Ω ≠ ∅} (C · est(Ω) + e(Ω))
     * captures the worst-case energy concentration at any anchor inside Θ_τ. The rule:
     *   env(Θ_τ) + e_i > C · τ   ⇒   est(i) ≥ ⌈(env(Θ_τ) − (C − c_i) · τ) / c_i⌉
     * for every task i with `lct(i) > τ`. The derivation is the standard
     * energy-conservation argument over `[est(Ω), τ]`: if Ω's energy plus i's full
     * energy would exceed the rectangle's capacity-area, i must end after Ω, which
     * forces i's earliest start up by however much room c_i leaves outside Ω's anchor.
     *
     * Cost is O(m²) where m = active task count (tasks with positive duration and
     * resource): one inner sweep over outside tasks per distinct LCT value. The Θ-Λ
     * variant (Vilím 2009 §4) reduces this to O(km log m) and is the natural follow-up
     * if cumulative propagation shows up in profiling on RCPSP-scale instances.
     */
    private fun edgeFindingPass(state: PropagationState): Boolean {
        if (n < 2 || capacity == 0) return true
        val active = IntArrayList()
        for (i in 0 until n) {
            // Theta-tree leaves stay inactive for definitely-absent or unpinned-presence
            // tasks — the README's stated opt semantics. Only definitely-present positive-
            // energy tasks anchor edge-finding deductions.
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (durations[i] > 0 && resources[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val ests = IntArray(m) { state.intDomains[starts[taskIds[it]]].min }
        val lcts = IntArray(m) { state.intDomains[starts[taskIds[it]]].max + durations[taskIds[it]] }
        val energies = LongArray(m) { durations[taskIds[it]].toLong() * resources[taskIds[it]].toLong() }
        val cs = IntArray(m) { resources[taskIds[it]] }

        // EST-ascending leaf positions. Stable on ties — choice doesn't affect the
        // envelope recurrence since equal-EST leaves anchor at the same time.
        val estOrder = (0 until m).sortedWith(compareBy({ ests[it] }, { it })).toIntArray()
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx

        // LCT-ascending sweep order.
        val lctOrder = (0 until m).sortedWith(compareBy({ lcts[it] }, { it })).toIntArray()

        val tree = CumulativeThetaTree(n = m, capacity = capacity)
        tree.setLeafOrder(leafPos)
        val capL = capacity.toLong()
        val ant = state.composeIntVarAtomAntecedents(starts)

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]]
            // Insert every task at this LCT before testing — tasks with equal LCT all
            // belong to Θ at threshold τ.
            while (k < m && lcts[lctOrder[k]] == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                k++
            }
            if (k >= m) break
            val envTheta = tree.envOfTheta()
            val capTau = capL * tau.toLong()
            // For every task still outside Θ (lct > τ), check the edge-finding rule.
            for (ki in k until m) {
                val i = lctOrder[ki]
                val eI = energies[i]
                val cI = cs[i]
                if (envTheta + eI <= capTau) continue
                val numerator = envTheta - (capacity - cI).toLong() * tau.toLong()
                if (numerator <= 0L) continue
                val newEstL = (numerator + cI - 1L) / cI.toLong()
                if (newEstL > Int.MAX_VALUE.toLong()) continue
                val newEst = newEstL.toInt()
                val v = starts[taskIds[i]]
                if (newEst > state.intDomains[v].min) {
                    if (!state.tightenIntMin(v, newEst, ant)) return false
                }
            }
        }
        return true
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
        val absT = if (peakT >= 0) peakT + tLow else 0
        // Collect tasks running through the peak (used by both shift and swap passes).
        val peakTasks = if (peakT >= 0) collectPeakTasks(state, absT) else IntArray(0)
        // 2. For each peak task, propose feasibility-preserving shifts off the peak. Prefer
        //    shifts that strictly reduce overage (simulated) over generic afterPeak/beforePeak,
        //    so probSAT-style scoring sees winning candidates rather than random shifts.
        val MAX_TARGETS = 4
        for (i in 0 until n) {
            val v = starts[i]
            val cur = state.assignment.intValue(v)
            val d = durations[i]
            val r = resources[i]
            val dom = state.problem.intDomains[v]
            val runsAtPeak = (peakT >= 0 && r > 0 && d > 0 && cur <= absT && absT < cur + d)
            if (runsAtPeak) {
                val afterPeak = absT + 1
                if (afterPeak in dom && afterPeak != cur) sink.addIntSet(v, afterPeak)
                val beforePeak = absT - d
                if (beforePeak in dom && beforePeak != cur) sink.addIntSet(v, beforePeak)
            }
            if (cur < dom.max) sink.addIntSet(v, cur + 1)
            if (cur > dom.min) sink.addIntSet(v, cur - 1)
            if (dom.size <= MAX_TARGETS) {
                dom.forEach { target -> if (target != cur) sink.addIntSet(v, target) }
            } else {
                repeat(MAX_TARGETS) {
                    val pick = dom.valueAt(state.rng.nextInt(dom.size))
                    if (pick != cur) sink.addIntSet(v, pick)
                }
            }
        }
        // 3. Resource-feasibility-preserving swaps. Pair each peak task with an off-peak
        //    task whose start time fits both domains; the swap moves one task off the peak
        //    while filling a slot the off-peak task vacates. simulateOverageDelta filters
        //    swaps that would worsen total overage at either side.
        if (peakTasks.isNotEmpty()) emitFeasibleSwaps(state, ls, peakTasks, sink)
    }

    /** Tasks whose interval covers [absT] under the current assignment, in declaration order. */
    private fun collectPeakTasks(state: LocalSearchState, absT: Int): IntArray {
        val out = com.eignex.klause.util.IntArrayList()
        for (i in 0 until n) {
            val r = resources[i]; val d = durations[i]
            if (r == 0 || d == 0) continue
            if (!present(state, i)) continue
            val cur = state.assignment.intValue(starts[i])
            if (cur <= absT && absT < cur + d) out.add(i)
        }
        return out.toIntArray()
    }

    /** Propose paired-shift Compound moves: for each task at the peak, find a non-peak
     *  task whose start sits in the peak task's domain (and vice versa) and the combined
     *  swap doesn't push usage above capacity at a new slot. Capped at [MAX_SWAPS] to
     *  bound the proposal-set size. */
    private fun emitFeasibleSwaps(
        state: LocalSearchState, ls: LsState, peakTasks: IntArray, sink: MoveSink,
    ) {
        var swapsAdded = 0
        for (i in peakTasks) {
            if (swapsAdded >= MAX_SWAPS) break
            val iV = starts[i]
            val iCur = state.assignment.intValue(iV)
            val iDom = state.problem.intDomains[iV]
            for (j in 0 until n) {
                if (swapsAdded >= MAX_SWAPS) break
                if (j == i) continue
                if (durations[j] == 0 || resources[j] == 0) continue
                if (!present(state, j)) continue
                val jV = starts[j]
                val jCur = state.assignment.intValue(jV)
                if (jCur !in iDom || iCur !in state.problem.intDomains[jV]) continue
                if (jCur == iCur) continue
                // Simulate the joint overage delta. Each task is moved independently against
                // the *original* timeline — this is an approximation (the two moves interact),
                // but for typical instances the second task's interval rarely overlaps the
                // first task's new slot exactly, so the linearised delta is a useful filter.
                val di = simulateOverageDelta(ls, iCur, jCur, durations[i], resources[i])
                val dj = simulateOverageDelta(ls, jCur, iCur, durations[j], resources[j])
                if (di + dj >= 0) continue  // not feasibility-preserving by this approximation
                sink.addCompound(listOf(
                    com.eignex.klause.solver.Move.IntSet(iV, jCur),
                    com.eignex.klause.solver.Move.IntSet(jV, iCur),
                ))
                swapsAdded++
            }
        }
    }

    private companion object {
        const val MAX_SWAPS: Int = 4
    }
}
