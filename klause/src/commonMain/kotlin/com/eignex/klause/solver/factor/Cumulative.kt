package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.IntSet
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
 *   `cost = Σ_t max(0, usage`t` − capacity)`
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
 * profile, which time-tabling cannot see.
 *
 * Cost model is dense: the LS payload allocates an `IntArray` of size
 * `horizon = max_i(starts[i].max + durations[i]) − min_i(starts[i].min)`. For Challenge
 * instances this is typically a few hundred; if your horizon explodes past ~1M, prefer a
 * Linear-per-timepoint decomposition.
 */
class Cumulative(
    /** Task start-time variable ids. */
    val starts: IntArray,
    /** Per-task duration: constant fallback / upper bound (when [durationVars] is set this
     *  holds the var's domain ub, used for horizon sizing). */
    val durations: IntArray,
    /** Per-task resource demand: same dual role as [durations]. */
    val resources: IntArray,
    /** Capacity: constant fallback / upper bound (when [capacityVar] ≥ 0 holds the var's ub). */
    val capacity: Int,
    /** Per-task presence literals; empty for the non-opt fast path. Absent tasks contribute
     *  zero energy / zero compulsory part. Theta-tree leaves stay inactive for
     *  definitely-absent tasks; unpinned-presence tasks are excluded from edge-finding too
     *  (they may yet go absent, so they can't sharpen Ω-energy deductions). */
    val presents: IntArray = EmptyIntArray,
    /** Per-task duration variables; empty = use [durations] as constants. When set, the
     *  factor reads the current duration from `state.assignment.intValue(durationVars[i])`
     *  and propagation pulls bounds from `state.intDomains[durationVars[i]]`. */
    val durationVars: IntArray = EmptyIntArray,
    /** Per-task resource variables; empty = use [resources] as constants. Same pattern as
     *  [durationVars]. */
    val resourceVars: IntArray = EmptyIntArray,
    /** Capacity variable id; -1 = use [capacity] as a constant. */
    val capacityVar: Int = -1,
) : LocalSearchFactor {

    init {
        require(starts.size == durations.size && starts.size == resources.size) {
            "Cumulative arrays must match: starts=${starts.size} " +
                "durations=${durations.size} resources=${resources.size}"
        }
        require(capacity >= 0) { "Cumulative capacity must be ≥ 0, got $capacity" }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "Cumulative durations[$i] must be ≥ 0, got ${durations[i]}" }
            require(resources[i] >= 0) { "Cumulative resources[$i] must be ≥ 0, got ${resources[i]}" }
        }
        require(presents.isEmpty() || presents.size == starts.size) {
            "Cumulative: presents must be empty or match starts arity"
        }
        require(durationVars.isEmpty() || durationVars.size == starts.size) {
            "Cumulative: durationVars must be empty or match starts arity"
        }
        require(resourceVars.isEmpty() || resourceVars.size == starts.size) {
            "Cumulative: resourceVars must be empty or match starts arity"
        }
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = run {
        val extra = (if (durationVars.isNotEmpty()) durationVars.size else 0) +
            (if (resourceVars.isNotEmpty()) resourceVars.size else 0) +
            (if (capacityVar >= 0) 1 else 0)
        if (extra == 0) {
            starts
        } else {
            val out = IntArray(starts.size + extra)
            var k = 0
            for (v in starts) out[k++] = v
            if (durationVars.isNotEmpty()) for (v in durationVars) out[k++] = v
            if (resourceVars.isNotEmpty()) for (v in resourceVars) out[k++] = v
            if (capacityVar >= 0) out[k++] = capacityVar
            out
        }
    }

    private fun present(state: LocalSearchState, idx: Int): Boolean =
        OptPresence.isPresentInAssignment(presents, idx, state)

    private val n: Int = starts.size

    private val startPos: Map<Int, Int> = starts.withIndex().associate { (i, v) -> v to i }
    private val durPos: Map<Int, Int> =
        if (durationVars.isEmpty()) emptyMap() else durationVars.withIndex().associate { (i, v) -> v to i }
    private val resPos: Map<Int, Int> =
        if (resourceVars.isEmpty()) emptyMap() else resourceVars.withIndex().associate { (i, v) -> v to i }

    private fun curDur(state: LocalSearchState, i: Int): Int =
        if (durationVars.isEmpty()) durations[i] else state.assignment.intValue(durationVars[i])
    private fun curRes(state: LocalSearchState, i: Int): Int =
        if (resourceVars.isEmpty()) resources[i] else state.assignment.intValue(resourceVars[i])
    private fun curCap(state: LocalSearchState): Int =
        if (capacityVar < 0) capacity else state.assignment.intValue(capacityVar)

    /** LS-side payload. Owns the usage timeline, the running overage, and the cached
     *  capacity (so capacity-var changes can recompute overage in one O(horizon) scan). */
    private class LsState(val tLow: Int, val usage: IntArray, var overage: Int, var cap: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val tLow = computeTLow(state)
        val tHigh = computeTHigh(state)
        val size = max(0, tHigh - tLow)
        val usage = IntArray(size)
        for (i in 0 until n) {
            if (!present(state, i)) continue
            val s = state.assignment.intValue(starts[i])
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val from = max(0, s - tLow)
            val to = min(size, s + d - tLow)
            for (t in from until to) usage[t] += r
        }
        val cap = curCap(state)
        var ov = 0
        for (t in usage.indices) {
            val u = usage[t]
            if (u > cap) ov += u - cap
        }
        val ls = LsState(tLow, usage, ov, cap)
        state.refPayload[factorId] = ls
        state.intPayload[factorId] = ov
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val ls = state.refPayload[factorId] as LsState
        val oldViolated = ls.overage > 0
        val oldVal = state.assignment.intValue(intVar)
        if (oldVal == newValue) return 0
        val delta = when {
            intVar == capacityVar -> capacityDelta(ls, newValue)

            else -> {
                val sp = startPos[intVar]
                if (sp != null) {
                    if (!present(state, sp)) {
                        0
                    } else {
                        val d = curDur(state, sp)
                        val r = curRes(state, sp)
                        if (d <= 0 || r <= 0) {
                            0
                        } else {
                            simulateStartDelta(ls, oldVal, newValue, d, r)
                        }
                    }
                } else {
                    val dp = durPos[intVar]
                    if (dp != null) {
                        if (!present(state, dp)) {
                            0
                        } else {
                            val r = curRes(state, dp)
                            if (r <= 0) {
                                0
                            } else {
                                val s = state.assignment.intValue(starts[dp])
                                simulateDurDelta(ls, s, oldVal, newValue, r)
                            }
                        }
                    } else {
                        val rp = resPos[intVar]
                        if (rp != null) {
                            if (!present(state, rp)) {
                                0
                            } else {
                                val d = curDur(state, rp)
                                if (d <= 0) {
                                    0
                                } else {
                                    val s = state.assignment.intValue(starts[rp])
                                    simulateResDelta(ls, s, d, oldVal, newValue)
                                }
                            }
                        } else {
                            0
                        }
                    }
                }
            }
        }
        val newViolated = (ls.overage + delta) > 0
        return (if (newViolated) 1 else 0) - (if (oldViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val ls = state.refPayload[factorId] as LsState
        val newValue = state.assignment.intValue(intVar)
        val oldViolated = ls.overage > 0
        if (oldValue == newValue) return 0
        when {
            intVar == capacityVar -> applyCapacityDelta(ls, newValue)

            else -> {
                val sp = startPos[intVar]
                if (sp != null) {
                    if (!present(state, sp)) return 0
                    val d = curDur(state, sp)
                    val r = curRes(state, sp)
                    if (d <= 0 || r <= 0) return 0
                    applyStartDelta(ls, oldValue, newValue, d, r)
                } else {
                    val dp = durPos[intVar]
                    if (dp != null) {
                        if (!present(state, dp)) return 0
                        val r = curRes(state, dp)
                        if (r <= 0) return 0
                        val s = state.assignment.intValue(starts[dp])
                        applyDurDelta(ls, s, oldValue, newValue, r)
                    } else {
                        val rp = resPos[intVar] ?: return 0
                        if (!present(state, rp)) return 0
                        val d = curDur(state, rp)
                        if (d <= 0) return 0
                        val s = state.assignment.intValue(starts[rp])
                        applyResDelta(ls, s, d, oldValue, newValue)
                    }
                }
            }
        }
        state.intPayload[factorId] = ls.overage
        val newViolated = ls.overage > 0
        return (if (newViolated) 1 else 0) - (if (oldViolated) 1 else 0)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        val cap = ls.cap
        val oldViolated = ls.overage > 0
        var deltaOv = 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val wasP = present(state, i)
            val sign = if (wasP) -1 else +1
            val s = state.assignment.intValue(starts[i])
            val from = max(0, s - ls.tLow)
            val to = min(ls.usage.size, s + d - ls.tLow)
            for (t in from until to) {
                val u = ls.usage[t]
                val nu = u + sign * r
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            }
        }
        val newViolated = (ls.overage + deltaOv) > 0
        return (if (newViolated) 1 else 0) - (if (oldViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        val cap = ls.cap
        val oldViolated = ls.overage > 0
        var deltaOv = 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val nowP = present(state, i)
            val sign = if (nowP) +1 else -1
            val s = state.assignment.intValue(starts[i])
            val from = max(0, s - ls.tLow)
            val to = min(ls.usage.size, s + d - ls.tLow)
            for (t in from until to) {
                val u = ls.usage[t]
                val nu = u + sign * r
                ls.usage[t] = nu
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            }
        }
        ls.overage += deltaOv
        state.intPayload[factorId] = ls.overage
        val newViolated = ls.overage > 0
        return (if (newViolated) 1 else 0) - (if (oldViolated) 1 else 0)
    }

    /** Overage Δ of shifting task from [oldStart,+d) → [newStart,+d). Pure simulation. */
    private fun simulateStartDelta(ls: LsState, oldStart: Int, newStart: Int, d: Int, r: Int): Int {
        val cap = ls.cap
        val usage = ls.usage
        val tLow = ls.tLow
        val size = usage.size
        var delta = 0
        val oldFrom = oldStart - tLow
        val oldTo = oldFrom + d
        val newFrom = newStart - tLow
        val newTo = newFrom + d
        for (t in oldFrom until oldTo) {
            if (t in newFrom until newTo) continue
            if (t < 0 || t >= size) continue
            val u = usage[t]
            delta += max(0, u - r - cap) - max(0, u - cap)
        }
        for (t in newFrom until newTo) {
            if (t in oldFrom until oldTo) continue
            if (t < 0 || t >= size) continue
            val u = usage[t]
            delta += max(0, u + r - cap) - max(0, u - cap)
        }
        return delta
    }

    private fun applyStartDelta(ls: LsState, oldStart: Int, newStart: Int, d: Int, r: Int) {
        val cap = ls.cap
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
            deltaOv += max(0, nu - cap) - max(0, u - cap)
        }
        for (t in newFrom until newTo) {
            if (t in oldFrom until oldTo) continue
            if (t < 0 || t >= size) continue
            val u = usage[t]
            val nu = u + r
            usage[t] = nu
            deltaOv += max(0, nu - cap) - max(0, u - cap)
        }
        ls.overage += deltaOv
    }

    /** Overage Δ of duration change [s,s+oldD) → [s,s+newD) at constant r. */
    private fun simulateDurDelta(ls: LsState, s: Int, oldD: Int, newD: Int, r: Int): Int {
        if (r <= 0 || oldD == newD) return 0
        val cap = ls.cap
        val usage = ls.usage
        val tLow = ls.tLow
        val size = usage.size
        var delta = 0
        if (newD > oldD) {
            val from = max(0, s + oldD - tLow)
            val to = min(size, s + newD - tLow)
            for (t in from until to) {
                val u = usage[t]
                delta += max(0, u + r - cap) - max(0, u - cap)
            }
        } else {
            val from = max(0, s + newD - tLow)
            val to = min(size, s + oldD - tLow)
            for (t in from until to) {
                val u = usage[t]
                delta += max(0, u - r - cap) - max(0, u - cap)
            }
        }
        return delta
    }

    private fun applyDurDelta(ls: LsState, s: Int, oldD: Int, newD: Int, r: Int) {
        if (r <= 0 || oldD == newD) return
        val cap = ls.cap
        val usage = ls.usage
        val tLow = ls.tLow
        val size = usage.size
        var deltaOv = 0
        if (newD > oldD) {
            val from = max(0, s + oldD - tLow)
            val to = min(size, s + newD - tLow)
            for (t in from until to) {
                val u = usage[t]
                val nu = u + r
                usage[t] = nu
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            }
        } else {
            val from = max(0, s + newD - tLow)
            val to = min(size, s + oldD - tLow)
            for (t in from until to) {
                val u = usage[t]
                val nu = u - r
                usage[t] = nu
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            }
        }
        ls.overage += deltaOv
    }

    /** Overage Δ of resource change r → r' over fixed interval [s, s+d). */
    private fun simulateResDelta(ls: LsState, s: Int, d: Int, oldR: Int, newR: Int): Int {
        if (d <= 0 || oldR == newR) return 0
        val cap = ls.cap
        val usage = ls.usage
        val tLow = ls.tLow
        val size = usage.size
        val diff = newR - oldR
        val from = max(0, s - tLow)
        val to = min(size, s + d - tLow)
        var delta = 0
        for (t in from until to) {
            val u = usage[t]
            delta += max(0, u + diff - cap) - max(0, u - cap)
        }
        return delta
    }

    private fun applyResDelta(ls: LsState, s: Int, d: Int, oldR: Int, newR: Int) {
        if (d <= 0 || oldR == newR) return
        val cap = ls.cap
        val usage = ls.usage
        val tLow = ls.tLow
        val size = usage.size
        val diff = newR - oldR
        val from = max(0, s - tLow)
        val to = min(size, s + d - tLow)
        var deltaOv = 0
        for (t in from until to) {
            val u = usage[t]
            val nu = u + diff
            usage[t] = nu
            deltaOv += max(0, nu - cap) - max(0, u - cap)
        }
        ls.overage += deltaOv
    }

    /** Overage Δ when capacity changes; full O(horizon) rescan. */
    private fun capacityDelta(ls: LsState, newCap: Int): Int {
        val usage = ls.usage
        val oldCap = ls.cap
        if (newCap == oldCap) return 0
        var newOv = 0
        for (u in usage) if (u > newCap) newOv += u - newCap
        return newOv - ls.overage
    }

    private fun applyCapacityDelta(ls: LsState, newCap: Int) {
        if (newCap == ls.cap) return
        var newOv = 0
        for (u in ls.usage) if (u > newCap) newOv += u - newCap
        ls.cap = newCap
        ls.overage = newOv
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
            // durations[i] is the constant or the var's ub (set by the compiler) — both
            // are sound upper bounds for horizon sizing.
            val dUb = if (durationVars.isEmpty()) {
                durations[i]
            } else {
                max(durations[i], state.problem.intDomains[durationVars[i]].max)
            }
            val cand = max(state.problem.intDomains[starts[i]].max, state.assignment.intValue(starts[i])) + dUb
            hi = max(hi, cand)
        }
        return if (hi == Int.MIN_VALUE) 0 else hi
    }

    /*
     * Time-tabling propagation. Builds the mandatory profile from each task's `[lst, ect)`
     * compulsory part; fails on any `Σ > capacity` time point; for every task with a
     * non-fixed start, tightens the start-domain endpoints against placements that would
     * push the profile over capacity within `[s, s+d_i)`.
     *
     * Event-based O(n log n) sweep keeps the work proportional to the number of tasks
     * (not to the horizon length), so the propagator stays cheap even on RCPSP instances
     * with planning horizons in the tens of thousands.
     */

    /** Conflict reason: bound atoms of every int var the propagator reads. The sweep is
     *  bound-only over the *starts* (it tightens start mins/maxes, never excludes interior
     *  start values), but [propagate] also snapshots and requires the fixed durations,
     *  resources, and capacity — an overload/edge-finding failure can be driven by those
     *  fixed values, so the reason must cite them too or the learned nogood is unsound and
     *  can prune feasible space on backtrack. [intVars] is starts plus any
     *  duration / resource / capacity vars. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (n == 0) return true
        // Snapshot effective durations / resources / capacity. If any var-arg isn't
        // fixed at this fixpoint pass, skip propagation (sound: better deductions defer
        // to later when bounds tighten). Const-only path is unchanged.
        val effDur = IntArray(n)
        val effRes = IntArray(n)
        for (i in 0 until n) {
            if (durationVars.isEmpty()) {
                effDur[i] = durations[i]
            } else {
                val d = state.intDomains[durationVars[i]]
                if (d.min != d.max) return true
                effDur[i] = d.min
            }
            if (resourceVars.isEmpty()) {
                effRes[i] = resources[i]
            } else {
                val d = state.intDomains[resourceVars[i]]
                if (d.min != d.max) return true
                effRes[i] = d.min
            }
        }
        val effCap = if (capacityVar < 0) {
            capacity
        } else {
            val d = state.intDomains[capacityVar]
            if (d.min != d.max) return true
            d.min
        }
        // Per-task resource feasibility — only definitely-present tasks must fit.
        for (i in 0 until n) {
            if (OptPresence.isDefinitelyAbsent(presents, i, state)) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0 && effRes[i] > effCap) return false
        }
        // Overload + edge-finding, both driven off the Θ-tree max-envelope (the overload
        // failure test is the envelope check `env(Θ_τ) > C·τ`, strictly tighter than the
        // old scalar full-prefix sweep — it catches every energy-concentrated sub-window).
        if (!edgeFindingPass(state, effDur, effRes, effCap)) return false
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0 || r == 0) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (!profile.build(effCap)) return false
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0 || r == 0) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val lstI = dom.max
            val ectI = dom.min + d
            val ownsMandatory = lstI < ectI
            var newMin = dom.min
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin > state.intDomains[v].max) return false
            // Cite all read int vars (starts + fixed durations/resources/capacity), not just
            // starts — the shave can be driven by those fixed values (see [conflictReason]).
            val ant = state.composeIntVarAtomAntecedents(intVars)
            if (newMin != state.intDomains[v].min && !state.tightenIntMin(v, newMin, ant)) return false
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else {
                    break
                }
            }
            if (newMax < state.intDomains[v].min) return false
            if (newMax != state.intDomains[v].max && !state.tightenIntMax(v, newMax, ant)) return false
        }
        return true
    }

    /**
     * Vilím cumulative overload + edge-finding using [CumulativeThetaTree].
     *
     * For each LCT threshold τ (swept in ascending order), the tree's active set Θ_τ
     * contains every task j with `lct(j) ≤ τ`, and the root envelope
     *   env(Θ_τ) = max_{Ω ⊆ Θ_τ, Ω ≠ ∅} (C · est(Ω) + e(Ω))
     * captures the worst-case energy concentration at any anchor inside Θ_τ.
     *
     * **Overload:** `env(Θ_τ) > C · τ` means some Ω's energy can't fit in `[est(Ω), τ]` at
     * capacity C — the instance is infeasible. This single envelope test subsumes the
     * weaker scalar full-prefix sweep it replaced (which only anchored at the global min
     * EST); it is checked for every τ including the full active set.
     *
     * **Edge-finding:** the rule
     *   env(Θ_τ) + e_i > C · τ   ⇒   est(i) ≥ ⌈(env(Θ_τ) − (C − c_i) · τ) / c_i⌉
     * for every task i with `lct(i) > τ`. The derivation is the standard
     * energy-conservation argument over `[est(Ω), τ]`: if Ω's energy plus i's full
     * energy would exceed the rectangle's capacity-area, i must end after Ω, which
     * forces i's earliest start up by however much room c_i leaves outside Ω's anchor.
     *
     * Cost is O(m²) where m = active task count (tasks with positive duration and
     * resource): one inner sweep over outside tasks per distinct LCT value.
     */
    private fun edgeFindingPass(state: PropagationState, effDur: IntArray, effRes: IntArray, effCap: Int): Boolean {
        if (n < 2 || effCap == 0) return true
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0 && effRes[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val ests = IntArray(m) { state.intDomains[starts[taskIds[it]]].min }
        val lcts = IntArray(m) { state.intDomains[starts[taskIds[it]]].max + effDur[taskIds[it]] }
        val energies = LongArray(m) { effDur[taskIds[it]].toLong() * effRes[taskIds[it]].toLong() }
        val cs = IntArray(m) { effRes[taskIds[it]] }

        // EST-ascending leaf positions. Stable on ties — choice doesn't affect the
        // envelope recurrence since equal-EST leaves anchor at the same time.
        val estOrder = (0 until m).sortedWith(compareBy({ ests[it] }, { it })).toIntArray()
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx

        // LCT-ascending sweep order.
        val lctOrder = (0 until m).sortedWith(compareBy({ lcts[it] }, { it })).toIntArray()

        val tree = CumulativeThetaTree(n = m, capacity = effCap)
        tree.setLeafOrder(leafPos)
        val capL = effCap.toLong()
        // Cite all read int vars — edge-finding deductions depend on the fixed durations /
        // resources / capacity, not just the start bounds (see [conflictReason]).
        val ant = state.composeIntVarAtomAntecedents(intVars)

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]]
            while (k < m && lcts[lctOrder[k]] == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                k++
            }
            val envTheta = tree.envOfTheta()
            val capTau = capL * tau.toLong()
            // Overload: env(Θ_τ) = max_{Ω⊆Θ_τ} (C·est(Ω) + e(Ω)); if it exceeds C·τ some
            // subset's energy can't fit in [est(Ω), τ] at capacity C — infeasible. Runs for
            // every τ including the full set (k == m), where edge-finding below is a no-op.
            if (envTheta > capTau) return false
            for (ki in k until m) {
                val i = lctOrder[ki]
                val eI = energies[i]
                val cI = cs[i]
                if (envTheta + eI <= capTau) continue
                val numerator = envTheta - (effCap - cI).toLong() * tau.toLong()
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
        var peakV = ls.cap
        val usage = ls.usage
        for (t in usage.indices) {
            if (usage[t] > peakV) {
                peakV = usage[t]
                peakT = t
            }
        }
        val tLow = ls.tLow
        val absT = if (peakT >= 0) peakT + tLow else 0
        val peakTasks = if (peakT >= 0) collectPeakTasks(state, absT) else IntArray(0)
        val maxTargets = 4
        for (i in 0 until n) {
            val v = starts[i]
            val cur = state.assignment.intValue(v)
            val d = curDur(state, i)
            val r = curRes(state, i)
            val dom = state.problem.intDomains[v]
            val runsAtPeak = (peakT >= 0 && r > 0 && d > 0 && cur <= absT && absT < cur + d)
            if (runsAtPeak) {
                val afterPeak = absT + 1
                if (afterPeak in dom && afterPeak != cur) sink.addChannelingIntSet(state, v, afterPeak)
                val beforePeak = absT - d
                if (beforePeak in dom && beforePeak != cur) sink.addChannelingIntSet(state, v, beforePeak)
            }
            if (cur < dom.max) sink.addChannelingIntSet(state, v, cur + 1)
            if (cur > dom.min) sink.addChannelingIntSet(state, v, cur - 1)
            if (dom.size <= maxTargets) {
                dom.forEach { target -> if (target != cur) sink.addChannelingIntSet(state, v, target) }
            } else {
                repeat(maxTargets) {
                    val pick = dom.valueAt(state.rng.nextInt(dom.size))
                    if (pick != cur) sink.addChannelingIntSet(state, v, pick)
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
        val out = IntArrayList()
        for (i in 0 until n) {
            val r = curRes(state, i)
            val d = curDur(state, i)
            if (r <= 0 || d <= 0) continue
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
    private fun emitFeasibleSwaps(state: LocalSearchState, ls: LsState, peakTasks: IntArray, sink: MoveSink) {
        var swapsAdded = 0
        for (i in peakTasks) {
            if (swapsAdded >= MAX_SWAPS) break
            val iV = starts[i]
            val iCur = state.assignment.intValue(iV)
            val iDom = state.problem.intDomains[iV]
            for (j in 0 until n) {
                if (swapsAdded >= MAX_SWAPS) break
                if (j == i) continue
                val dj0 = curDur(state, j)
                val rj0 = curRes(state, j)
                if (dj0 <= 0 || rj0 <= 0) continue
                if (!present(state, j)) continue
                val jV = starts[j]
                val jCur = state.assignment.intValue(jV)
                if (jCur !in iDom || iCur !in state.problem.intDomains[jV]) continue
                if (jCur == iCur) continue
                val di = simulateStartDelta(ls, iCur, jCur, curDur(state, i), curRes(state, i))
                val dj = simulateStartDelta(ls, jCur, iCur, dj0, rj0)
                if (di + dj >= 0) continue // not feasibility-preserving by this approximation
                sink.addCompound(
                    listOf(
                        IntSet(iV, jCur),
                        IntSet(jV, iCur),
                    ),
                )
                swapsAdded++
            }
        }
    }

    private companion object {
        const val MAX_SWAPS: Int = 4
    }
}
