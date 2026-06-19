package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.argsortByIntKey
import kotlin.math.max
import kotlin.math.min

/**
 * Cumulative scheduling constraint: at every integer time point the total resource use of
 * tasks running at that point stays under [capacity]. Task `i` has variable start time
 * `starts(i)`, fixed duration `durations(i) ≥ 0`, fixed resource demand `resources(i) ≥ 0`.
 *
 * Semantics:
 *  - Task `i` occupies the half-open interval `[starts(i), starts(i) + durations(i))`.
 *  - For every integer time point `t`, `Σ_{i: starts(i) ≤ t < starts(i)+durations(i)} resources(i) ≤ capacity`.
 *  - Zero-duration tasks consume no resource and impose no constraint.
 *  - Any task with `resources(i) > capacity` makes the problem trivially infeasible (the
 *    factor still reports a graded overage cost when LS hits such a placement).
 *
 * LS cost is graded:
 *   `cost = Σ_t max(0, usage(t) − capacity)`
 * — broken assignments rank by total energy overflow rather than by a flat boolean,
 * giving the search a real gradient toward the cumulative bound. This energy overage is
 * the factor's [violationDegree] (run through [compressViolation] so a deeply-overloaded
 * profile can't dominate the global cost); [deltaIfIntSet] / [applyIntSet] and the
 * bool-flip paths return its compressed delta. The raw overage is also mirrored to
 * `state.intPayload(factorId)` for strategies that read it directly (as ALNS does).
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
    override val presents: IntArray = EmptyIntArray,
    /** Per-task duration variables; empty = use [durations] as constants. When set, the
     *  factor reads the current duration from `state.assignment.intValue(durationVars(i))`
     *  and propagation pulls bounds from `state.intDomains(durationVars(i))`. */
    val durationVars: IntArray = EmptyIntArray,
    /** Per-task resource variables; empty = use [resources] as constants. Same pattern as
     *  [durationVars]. */
    val resourceVars: IntArray = EmptyIntArray,
    /** Capacity variable id; -1 = use [capacity] as a constant. */
    val capacityVar: Int = -1,
) : Factor,
    OptionalFactor {

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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Cumulative(
        starts.remapVars(intMap),
        durations,
        resources,
        capacity,
        presents.remapLits(boolMap),
        durationVars.remapVars(intMap),
        resourceVars.remapVars(intMap),
        if (capacityVar >= 0) intMap[capacityVar] else capacityVar,
    )

    /** Position-faithful (task i is fixed by index): keeps every array in order and folds in all
     *  constants — durations/resources/capacity and the var/const split — so two non-equivalent
     *  cumulatives never collide (#531). */
    override fun structuralKey(): String =
        "cumulative:$capacity:$capacityVar:${durations.joinToString(",")}:${resources.joinToString(",")}:" +
            "${starts.joinToString(",")}:${presents.joinToString(",")}:" +
            "${durationVars.joinToString(",")}:${resourceVars.joinToString(",")}"

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

    /**
     * Advisor subscription (#623): cumulative propagation (mandatory profile + Θ-tree edge-finding)
     * reads only each variable's `min`/`max` — start bounds drive the profile/edge-finding, and
     * duration/resource/capacity vars are consulted only once fixed (`d.min == d.max`). It never
     * inspects interior holes, so it subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] on
     * every integer variable and skips interior `VALUE_REMOVED` wakes. Presence is carried by Boolean
     * variables, which keep their separate two-watched-literal wakeup.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    private val n: Int = starts.size

    /** The sharp pointwise time-tabling explanation ([pointwiseOverloadReason]) covers every task as
     *  mandatory. Variable durations / resources / capacity are handled by additionally citing their
     *  (fixed-at-propagation) bounds, but separate presence literals are not, so optional tasks fall
     *  back to the sound constraint-wide reason. RCPSP / mspsp-style instances (mandatory tasks, the
     *  multi-skill "presence" carried by a 0/1 resource var rather than a presence literal) are sharp. */
    private val sharpReasonEligible: Boolean = presents.isEmpty()

    // Var id → its position in the corresponding array (-1 when the var is not in that role).
    // IntIntMap keeps the lookup unboxed and array-backed for the dense var ids these hold.
    private val startPos: IntIntMap = IntIntMap.build(starts, IntArray(starts.size) { it }, absent = -1)
    private val durPos: IntIntMap = IntIntMap.build(durationVars, IntArray(durationVars.size) { it }, absent = -1)
    private val resPos: IntIntMap = IntIntMap.build(resourceVars, IntArray(resourceVars.size) { it }, absent = -1)

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

    /** Graded degree: total resource overage `Σ_t max(0, usage_t - cap)`, compressed so a
     *  deeply-overloaded profile can't dominate the global cost sum. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(state.intPayload[factorId].toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val ls = state.refPayload[factorId] as LsState
        val oldVal = state.assignment.intValue(intVar)
        if (oldVal == newValue) return 0
        val delta = when {
            intVar == capacityVar -> capacityDelta(ls, newValue)

            else -> {
                val sp = startPos[intVar]
                if (sp >= 0) {
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
                    if (dp >= 0) {
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
                        if (rp >= 0) {
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
        return compressViolation((ls.overage + delta).toLong(), state.violationSoftCap) -
            compressViolation(ls.overage.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val ls = state.refPayload[factorId] as LsState
        val newValue = state.assignment.intValue(intVar)
        val before = ls.overage
        if (oldValue == newValue) return 0
        when {
            intVar == capacityVar -> applyCapacityDelta(ls, newValue)

            else -> {
                val sp = startPos[intVar]
                if (sp >= 0) {
                    if (!present(state, sp)) return 0
                    val d = curDur(state, sp)
                    val r = curRes(state, sp)
                    if (d <= 0 || r <= 0) return 0
                    applyStartDelta(ls, oldValue, newValue, d, r)
                } else {
                    val dp = durPos[intVar]
                    if (dp >= 0) {
                        if (!present(state, dp)) return 0
                        val r = curRes(state, dp)
                        if (r <= 0) return 0
                        val s = state.assignment.intValue(starts[dp])
                        applyDurDelta(ls, s, oldValue, newValue, r)
                    } else {
                        val rp = resPos[intVar]
                        if (rp < 0) return 0
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
        return compressViolation(ls.overage.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        val cap = ls.cap
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
        return compressViolation((ls.overage + deltaOv).toLong(), state.violationSoftCap) -
            compressViolation(ls.overage.toLong(), state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        val cap = ls.cap
        val before = ls.overage
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
        return compressViolation(ls.overage.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    /**
     * Visit every timeline slot whose load changes when an equal-length task footprint moves from
     * `[oldStart, +d)` to `[newStart, +d)`. The two footprints share the overlap `[overlapLo,
     * overlapHi)`, whose load is unchanged, so only the symmetric difference is visited: [onRemove]
     * for slots the task leaves, [onAdd] for slots it enters. Work is `O(|newStart − oldStart|)`,
     * not `O(d)` — a one-step shift touches two slots regardless of duration. Slots are clamped to
     * the timeline `[0, size)`. Inline so the per-slot callbacks stay allocation-free.
     */
    private inline fun forEachStartShiftSlot(
        ls: LsState,
        oldStart: Int,
        newStart: Int,
        d: Int,
        onRemove: (t: Int) -> Unit,
        onAdd: (t: Int) -> Unit,
    ) {
        val size = ls.usage.size
        val oldFrom = oldStart - ls.tLow
        val newFrom = newStart - ls.tLow
        val overlapLo = max(oldFrom, newFrom)
        val overlapHi = min(oldFrom + d, newFrom + d)
        if (overlapLo >= overlapHi) {
            for (t in max(0, oldFrom) until min(size, oldFrom + d)) onRemove(t)
            for (t in max(0, newFrom) until min(size, newFrom + d)) onAdd(t)
        } else {
            for (t in max(0, oldFrom) until min(size, overlapLo)) onRemove(t)
            for (t in max(0, overlapHi) until min(size, oldFrom + d)) onRemove(t)
            for (t in max(0, newFrom) until min(size, overlapLo)) onAdd(t)
            for (t in max(0, overlapHi) until min(size, newFrom + d)) onAdd(t)
        }
    }

    /** Overage Δ of shifting task from [oldStart,+d) → [newStart,+d). Pure simulation. */
    private fun simulateStartDelta(ls: LsState, oldStart: Int, newStart: Int, d: Int, r: Int): Int {
        val cap = ls.cap
        val usage = ls.usage
        var delta = 0
        forEachStartShiftSlot(
            ls,
            oldStart,
            newStart,
            d,
            onRemove = { t ->
                val u = usage[t]
                delta += max(0, u - r - cap) - max(0, u - cap)
            },
            onAdd = { t ->
                val u = usage[t]
                delta += max(0, u + r - cap) - max(0, u - cap)
            },
        )
        return delta
    }

    private fun applyStartDelta(ls: LsState, oldStart: Int, newStart: Int, d: Int, r: Int) {
        val cap = ls.cap
        val usage = ls.usage
        var deltaOv = 0
        forEachStartShiftSlot(
            ls,
            oldStart,
            newStart,
            d,
            onRemove = { t ->
                val u = usage[t]
                val nu = u - r
                usage[t] = nu
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            },
            onAdd = { t ->
                val u = usage[t]
                val nu = u + r
                usage[t] = nu
                deltaOv += max(0, nu - cap) - max(0, u - cap)
            },
        )
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

    /**
     * Conflict reason. The constraint-wide fallback ([collectLinearTightenAntecedents] over every
     * read int var) is sound but cites *every* task's current bounds, so the learned nogood matches
     * only the exact dead-end state and the search relearns one clause per failure without pruning
     * (#729). When an overloaded mandatory-profile point can be reconstructed, the sharp pointwise
     * reason ([pointwiseOverloadReason]) cites only the tasks whose compulsory part covers that one time
     * point, and only the generalised window bounds (`start ≤ t`, `start ≥ t − d + 1`) that keep them
     * there — a Schutt-style minimal time-tabling explanation that generalises across the search.
     *
     * The overloaded point is recomputed here rather than stashed at the failure site: the domains
     * are unchanged between [propagate] returning `false` and this call, and any currently-overloaded
     * profile point is a valid nogood regardless of which check (per-task / edge-finding / profile)
     * actually fired. When no profile point is over capacity — a pure energy (edge-finding) overload,
     * or a no-feasible-placement domain wipeout — the fallback reason is returned.
     */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val fallback = collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)
        if (!sharpReasonEligible) return fallback
        // Effective durations / resources / capacity at conflict time. Any var-arg is fixed here:
        // the snapshot in [propagate] defers (returns true) on an unfixed arg, so a `false` return —
        // the precondition for this call — only happens once they are all singletons.
        val eff = effectiveSnapshot(state) ?: return fallback
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            val d = eff.dur[i]
            val r = eff.res[i]
            if (d == 0 || r == 0) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (profile.build(eff.cap)) return fallback
        return pointwiseOverloadReason(state, profile.overloadTime, eff, blamed = -1, blamedStart = 0) ?: fallback
    }

    /** Effective (fixed) durations / resources / capacity, or `null` if any var-arg is still open. */
    private class Eff(val dur: IntArray, val res: IntArray, val cap: Int)

    private fun effectiveSnapshot(state: PropagationState): Eff? {
        val dur = IntArray(n)
        val res = IntArray(n)
        for (i in 0 until n) {
            if (durationVars.isEmpty()) {
                dur[i] = durations[i]
            } else {
                val d = state.intDomains[durationVars[i]]
                if (d.min != d.max) return null
                dur[i] = d.min
            }
            if (resourceVars.isEmpty()) {
                res[i] = resources[i]
            } else {
                val d = state.intDomains[resourceVars[i]]
                if (d.min != d.max) return null
                res[i] = d.min
            }
        }
        val cap = if (capacityVar < 0) {
            capacity
        } else {
            val d = state.intDomains[capacityVar]
            if (d.min != d.max) return null
            d.min
        }
        return Eff(dur, res, cap)
    }

    /**
     * Pointwise time-tabling explanation for an overload at time [t]: the literals that force every
     * task whose compulsory part covers [t] to keep covering it, plus — when [eff] reads var-arg
     * durations / resources / capacity — the bounds that fix the energy. Each premise is cited in
     * implication-clause form (the negation of a currently-true bound), and only when non-trivial
     * (tighter than the original domain — a global fact is dropped, the analyzer minimises it out):
     *   - `¬[start_k ≤ t]` and `¬[start_k ≥ t − d_k + 1]` keep `k`'s compulsory part over `t`;
     *   - `¬[d_k ≥ eff_d_k]` / `¬[r_k ≥ eff_r_k]` pin the (over-)estimated duration / demand so the
     *     covered energy can't shrink below what overloaded; `¬[cap ≤ eff_cap]` pins the capacity.
     * Any model satisfying these premises has each cited task covering [t] with demand ≥ its snapshot,
     * summing past the (≤-snapshot) capacity — so the clause is a true nogood, independent of the
     * model. [blamed] ≥ 0 names a task whose own compulsory part is discounted by `overloadsAt` in a
     * shave: it is dropped from the covering loop (its placement is the *conclusion*), but its own
     * energy bounds and prior start bound [blamedStart] are still cited, since they drive the overload.
     * Returns `null` when nothing non-trivial is cited (caller falls back to the constraint-wide reason).
     */
    private fun pointwiseOverloadReason(
        state: PropagationState,
        t: Int,
        eff: Eff,
        blamed: Int,
        blamedStart: Int,
    ): IntArray? {
        val out = IntArrayList()
        if (blamedStart != 0) out.add(blamedStart)
        if (blamed >= 0) citeEnergyBounds(out, state, blamed, eff)
        for (k in 0 until n) {
            if (k == blamed) continue
            val d = eff.dur[k]
            val r = eff.res[k]
            if (d <= 0 || r <= 0) continue
            val dom = state.intDomains[starts[k]]
            // Compulsory part [lst, ect) = [dom.max, dom.min + d); covers t iff lst ≤ t < ect.
            if (dom.max > t || t >= dom.min + d) continue
            val orig = state.problem.intDomains[starts[k]]
            if (t < orig.max) out.add(Lit.make(state.atomVarLe(starts[k], t), false))
            val geThreshold = t - d + 1
            if (geThreshold > orig.min) out.add(Lit.make(state.atomVarGe(starts[k], geThreshold), false))
            citeEnergyBounds(out, state, k, eff)
        }
        if (capacityVar >= 0) {
            val orig = state.problem.intDomains[capacityVar]
            if (eff.cap < orig.max) out.add(Lit.make(state.atomVarLe(capacityVar, eff.cap), false))
        }
        if (out.size == 0) return null
        return out.toIntArray()
    }

    /** Cite task [k]'s duration / resource var lower bounds (`¬[d_k ≥ eff]` / `¬[r_k ≥ eff]`) when
     *  variable and tighter than the original domain — a smaller value would only shrink the overload,
     *  so pinning the lower bound is what the deduction rests on. No-op on the constant fast path. */
    private fun citeEnergyBounds(out: IntArrayList, state: PropagationState, k: Int, eff: Eff) {
        if (durationVars.isNotEmpty()) {
            val dv = durationVars[k]
            if (eff.dur[k] > state.problem.intDomains[dv].min) out.add(Lit.make(state.atomVarGe(dv, eff.dur[k]), false))
        }
        if (resourceVars.isNotEmpty()) {
            val rv = resourceVars[k]
            if (eff.res[k] > state.problem.intDomains[rv].min) out.add(Lit.make(state.atomVarGe(rv, eff.res[k]), false))
        }
    }

    /**
     * Sharp reason for raising `start_i`'s lower bound from [oldMin] to [newMin] by time-tabling.
     * The first feasible start [newMin] differs from the infeasible [newMin] − 1 only by uncovering
     * `t* = newMin − 1`, so that single point is the blocker: any start in `[t* − d + 1, t*] =
     * [newMin − d, newMin − 1]` makes `i` cover `t*` and overload it. With `i` already at or above
     * `[newMin − d]` (`newMin − oldMin ≤ d`), forbidding that window pushes the bound to [newMin].
     * Cites the profile at `t*` (blaming `i`, whose own part `overloadsAt` discounts) plus `i`'s own
     * `start_i ≥ oldMin` premise when non-trivial. Returns `null` (caller falls back) when the push
     * spans more than the duration, where a single point no longer covers the whole forbidden range.
     */
    private fun minTightenReason(
        state: PropagationState,
        i: Int,
        d: Int,
        oldMin: Int,
        newMin: Int,
        eff: Eff,
    ): IntArray? {
        if (newMin - oldMin > d) return null
        val orig = state.problem.intDomains[starts[i]]
        val extra = if (oldMin > orig.min) Lit.make(state.atomVarGe(starts[i], oldMin), false) else 0
        return pointwiseOverloadReason(state, newMin - 1, eff, blamed = i, blamedStart = extra)
    }

    /** Mirror of [minTightenReason] for lowering `start_i`'s upper bound from [oldMax] to [newMax].
     *  The blocking point is `t* = newMax + d` (the point [newMax] + 1's placement uncovers); any
     *  start in `[newMax + 1, newMax + d]` makes `i` cover it. Cites `i`'s `start_i ≤ oldMax` premise
     *  when non-trivial. Returns `null` for pushes wider than the duration. */
    private fun maxTightenReason(
        state: PropagationState,
        i: Int,
        d: Int,
        oldMax: Int,
        newMax: Int,
        eff: Eff,
    ): IntArray? {
        if (oldMax - newMax > d) return null
        val orig = state.problem.intDomains[starts[i]]
        val extra = if (oldMax < orig.max) Lit.make(state.atomVarLe(starts[i], oldMax), false) else 0
        return pointwiseOverloadReason(state, newMax + d, eff, blamed = i, blamedStart = extra)
    }

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
            if (definitelyAbsent(i, state)) continue
            if (!definitelyPresent(i, state)) continue
            if (effDur[i] > 0 && effRes[i] > effCap) return false
        }
        // Overload + edge-finding, both driven off the Θ-tree max-envelope (the overload
        // failure test is the envelope check `env(Θ_τ) > C·τ`, strictly tighter than the
        // old scalar full-prefix sweep — it catches every energy-concentrated sub-window).
        if (!edgeFindingPass(state, effDur, effRes, effCap)) return false
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!definitelyPresent(i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0 || r == 0) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (!profile.build(effCap)) return false
        val eff = if (sharpReasonEligible) Eff(effDur, effRes, effCap) else null
        for (i in 0 until n) {
            if (!definitelyPresent(i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0 || r == 0) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val oldMin = dom.min
            val oldMax = dom.max
            val lstI = oldMax
            val ectI = oldMin + d
            val ownsMandatory = lstI < ectI
            var newMin = oldMin
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin > state.intDomains[v].max) return false
            if (newMin != oldMin) {
                // Sharp single-point pointwise reason when eligible; the constraint-wide fallback
                // (all read int vars — the shave can be driven by the fixed durations / resources /
                // capacity, not just start bounds) for optional tasks or wide pushes. See
                // [minTightenReason].
                val ant = (if (eff != null) minTightenReason(state, i, d, oldMin, newMin, eff) else null)
                    ?: state.composeIntVarAtomAntecedents(intVars)
                if (!state.tightenIntMin(v, newMin, ant)) return false
            }
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else {
                    break
                }
            }
            if (newMax < state.intDomains[v].min) return false
            if (newMax != oldMax) {
                val ant = (if (eff != null) maxTightenReason(state, i, d, oldMax, newMax, eff) else null)
                    ?: state.composeIntVarAtomAntecedents(intVars)
                if (!state.tightenIntMax(v, newMax, ant)) return false
            }
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
     *   env(Θ_τ ∪ {i}) > C · τ   ⇒   est(i) ≥ ⌈(env(Θ_τ) − (C − c_i) · τ) / c_i⌉
     * for every task i with `lct(i) > τ`. The derivation is the standard
     * energy-conservation argument over `[est(Ω), τ]`: if Ω's energy plus i's full
     * energy would exceed the rectangle's capacity-area, i must end after Ω, which
     * forces i's earliest start up by however much room c_i leaves outside Ω's anchor.
     * The detection inserts i into the tree (`env(Θ_τ ∪ {i})`) rather than flat-adding
     * `e_i`, so a task whose est lets it run *before* Ω is not wrongly forced after it.
     *
     * Cost is O(m² log m) where m = active task count (tasks with positive duration and
     * resource): one inner sweep over outside tasks per distinct LCT value, each probing
     * the tree with an O(log m) insert/remove of the candidate.
     */
    private fun edgeFindingPass(state: PropagationState, effDur: IntArray, effRes: IntArray, effCap: Int): Boolean {
        if (n < 2 || effCap == 0) return true
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!definitelyPresent(i, state)) continue
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
        val estOrder = argsortByIntKey(m) { ests[it] }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx

        // LCT-ascending sweep order.
        val lctOrder = argsortByIntKey(m) { lcts[it] }

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
                // Detection: env(Θ_τ ∪ {i}) > C·τ. Insert i (it is inactive — lct(i) > τ) so the
                // envelope folds i's own est into the anchor. The flat `envTheta + e_i`
                // upper-bounds env(Θ∪{i}) and over-detects when est_i < est(Ω) — wrongly forcing
                // a task that could run before Θ to come after.
                val envWith = tree.envIfActivated(i, ests[i], eI)
                if (envWith <= capTau) continue
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

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: swap the start times of two present tasks with the
     *  same current duration **and** resource demand. Their energy blocks have identical shape and
     *  height, so swapping which task occupies which slot leaves the resource profile `usage(t)`
     *  unchanged at every time point — the overage cost, hence feasibility, is preserved. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (n < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_SWAP_CAP && attempts < STRUCTURED_SWAP_CAP * SWAP_ATTEMPT_STRIDE) {
            attempts++
            val i = state.rng.nextInt(n)
            val j = state.rng.nextInt(n)
            if (i == j || starts[i] == starts[j]) continue
            if (!present(state, i) || !present(state, j)) continue
            if (curDur(state, i) != curDur(state, j) || curRes(state, i) != curRes(state, j)) continue
            val si = state.assignment.intValue(starts[i])
            val sj = state.assignment.intValue(starts[j])
            if (si == sj) continue
            if (sj !in state.problem.intDomains[starts[i]] || si !in state.problem.intDomains[starts[j]]) continue
            sink.addCompound(listOf(IntSet(starts[i], sj), IntSet(starts[j], si)))
            emitted++
        }
    }

    /** Feasible init: serialise the present tasks in earliest-start order so no two overlap; with
     *  no overlap the usage at any time is a single task's demand, capacity-feasible as long as
     *  every demand fits under the capacity. Returns false — leaving the random assignment — when a
     *  task can't be placed in domain or a single demand exceeds the capacity. */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (starts.isEmpty()) return false
        val cap = curCap(state)
        val order = argsortByIntKey(starts.size) { state.problem.intDomains[starts[it]].min }
        var prevEnd = Int.MIN_VALUE
        for (oi in order.indices) {
            val i = order[oi]
            if (!present(state, i)) continue
            if (curRes(state, i) > cap) return false
            val dur = curDur(state, i)
            val v = starts[i]
            if (state.assumptions.isFrozenInt(v)) {
                val s = state.assignment.intValue(v)
                if (s < prevEnd) return false
                prevEnd = s + dur
            } else {
                val d = state.problem.intDomains[v]
                val cand = max(d.min, prevEnd)
                if (cand > d.max) return false
                var s = -1
                d.forEach { if (s < 0 && it >= cand) s = it }
                if (s < 0) return false
                state.assignment.setInt(v, s)
                prevEnd = s + dur
            }
        }
        return true
    }

    private companion object {
        const val MAX_SWAPS: Int = 4

        /** Cap on equal-shape start-swap compounds offered per [proposeStructuredMoves] call. */
        const val STRUCTURED_SWAP_CAP: Int = 4

        /** Rejection-sampling attempts per requested swap before giving up. */
        const val SWAP_ATTEMPT_STRIDE: Int = 8
    }
}
