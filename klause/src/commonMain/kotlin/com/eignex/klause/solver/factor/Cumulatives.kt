package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-machine cumulative scheduling — MiniZinc's `cumulatives(s, d, r, m, b, upper, min_m)`,
 * the machine-indexed generalization of [Cumulative]. Task `i` has variable start
 * `starts[i]`, duration `durations[i] ≥ 0`, resource demand `resources[i] ≥ 0`, and a
 * **machine** variable `machines[i]` selecting which of the `bounds.size` machines it runs on.
 * Machine value `mv` indexes bound `bounds[mv − minMachine]`.
 *
 * Semantics, per machine `k` and integer time `t` with `usage[k][t] = Σ resources[i]` over
 * present tasks with `machines[i] = k` running at `t`:
 *  - **[upper] = true** (capacity): `usage[k][t] ≤ bounds[k]`.
 *  - **[upper] = false** (minimum load, only where some task runs): `usage[k][t] ≥ bounds[k]`
 *    at every `t` with `usage[k][t] > 0`.
 *
 * LS cost is graded through [compressViolation]:
 *   `raw = Σ_{k,t} penalty(usage[k][t], bounds[k])`
 * where `penalty` is the capacity overflow (upper) or the coverage shortfall (lower). This
 * gives CBLS a real gradient — a move trimming a peak by 3 scores below one trimming it by 1.
 *
 * Machine validity (`machines[i] ∈ [minMachine, minMachine + bounds.size − 1]`) is a *domain*
 * fact, enforced by [propagate] tightening the machine vars (and, on the FZN side, by the
 * declared domain). A task whose machine falls outside that range contributes nowhere — the
 * factor never indexes its timeline out of bounds — so well-formed models (machine domain ⊆
 * the bound index set) see exact semantics.
 *
 * Cost model is dense: the payload allocates `bounds.size × horizon` ints, where
 * `horizon = max_i(starts[i].max + durations[i]) − min_i(starts[i].min)`. Fine for the few-
 * machines / few-hundred-horizon Challenge instances; a per-timepoint Linear decomposition is
 * better if `machines × horizon` explodes.
 */
class Cumulatives(
    /** Task start-time variable ids. */
    val starts: IntArray,
    /** Per-task duration: constant fallback / ub (when [durationVars] is set, the var's ub). */
    val durations: IntArray,
    /** Per-task resource demand: same dual role as [durations]. */
    val resources: IntArray,
    /** Per-task machine variable ids. */
    val machines: IntArray,
    /** Per-machine bound: constant fallback / ub (when [boundVars] is set, the var's ub). */
    val bounds: IntArray,
    /** `true` = capacity (usage ≤ bound); `false` = minimum load (usage ≥ bound where covered). */
    val upper: Boolean,
    /** Machine value selecting `bounds[0]` — `min(index_set(b))` from MiniZinc. */
    val minMachine: Int,
    /** Per-task duration vars; empty = [durations] are constants. */
    val durationVars: IntArray = EmptyIntArray,
    /** Per-task resource vars; empty = [resources] are constants. */
    val resourceVars: IntArray = EmptyIntArray,
    /** Per-machine bound vars; empty = [bounds] are constants. */
    val boundVars: IntArray = EmptyIntArray,
    /** Per-task presence literals; empty for the non-opt fast path. */
    val presents: IntArray = EmptyIntArray,
) : LocalSearchFactor {

    private val n: Int = starts.size
    private val machineCount: Int = bounds.size

    init {
        require(starts.size == durations.size && starts.size == resources.size && starts.size == machines.size) {
            "Cumulatives arrays must match: starts=${starts.size} durations=${durations.size} " +
                "resources=${resources.size} machines=${machines.size}"
        }
        require(machineCount > 0) { "Cumulatives needs at least one machine bound" }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "Cumulatives durations[$i] must be ≥ 0, got ${durations[i]}" }
            require(resources[i] >= 0) { "Cumulatives resources[$i] must be ≥ 0, got ${resources[i]}" }
        }
        require(durationVars.isEmpty() || durationVars.size == n) { "Cumulatives: durationVars arity" }
        require(resourceVars.isEmpty() || resourceVars.size == n) { "Cumulatives: resourceVars arity" }
        require(boundVars.isEmpty() || boundVars.size == machineCount) { "Cumulatives: boundVars arity" }
        require(presents.isEmpty() || presents.size == n) { "Cumulatives: presents arity" }
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = run {
        val extra = (if (durationVars.isNotEmpty()) n else 0) +
            (if (resourceVars.isNotEmpty()) n else 0) +
            (if (boundVars.isNotEmpty()) machineCount else 0)
        val out = IntArray(starts.size + machines.size + extra)
        var k = 0
        for (v in starts) out[k++] = v
        for (v in machines) out[k++] = v
        if (durationVars.isNotEmpty()) for (v in durationVars) out[k++] = v
        if (resourceVars.isNotEmpty()) for (v in resourceVars) out[k++] = v
        if (boundVars.isNotEmpty()) for (v in boundVars) out[k++] = v
        out
    }

    private val startPos: Map<Int, Int> = starts.withIndex().associate { (i, v) -> v to i }
    private val machinePos: Map<Int, Int> = machines.withIndex().associate { (i, v) -> v to i }
    private val durPos: Map<Int, Int> =
        if (durationVars.isEmpty()) emptyMap() else durationVars.withIndex().associate { (i, v) -> v to i }
    private val resPos: Map<Int, Int> =
        if (resourceVars.isEmpty()) emptyMap() else resourceVars.withIndex().associate { (i, v) -> v to i }
    private val boundPos: Map<Int, Int> =
        if (boundVars.isEmpty()) emptyMap() else boundVars.withIndex().associate { (k, v) -> v to k }

    private fun present(state: LocalSearchState, i: Int): Boolean =
        OptPresence.isPresentInAssignment(presents, i, state)
    private fun curDur(state: LocalSearchState, i: Int): Int =
        if (durationVars.isEmpty()) durations[i] else state.assignment.intValue(durationVars[i])
    private fun curRes(state: LocalSearchState, i: Int): Int =
        if (resourceVars.isEmpty()) resources[i] else state.assignment.intValue(resourceVars[i])
    private fun curBound(state: LocalSearchState, k: Int): Int =
        if (boundVars.isEmpty()) bounds[k] else state.assignment.intValue(boundVars[k])
    private fun machineIdx(mv: Int): Int = mv - minMachine
    private fun inRange(idx: Int): Boolean = idx in 0 until machineCount

    /** Per-timepoint penalty for a usage level against a machine bound. Capacity overflow
     *  (upper) or coverage shortfall (lower, only where the machine is in use). */
    private fun penalty(usage: Int, bound: Int): Int = if (upper) {
        max(0, usage - bound)
    } else if (usage > 0) {
        max(0, bound - usage)
    } else {
        0
    }

    /** Dense per-machine timeline plus the running raw violation. */
    private class LsState(
        val tLow: Int,
        val horizon: Int,
        /** `usage[k * horizon + t]`. */
        val usage: IntArray,
        var rawV: Long,
    )

    private fun cell(ls: LsState, k: Int, t: Int): Int = k * ls.horizon + t

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val tLow = computeTLow(state)
        val tHigh = computeTHigh(state)
        val horizon = max(0, tHigh - tLow)
        val usage = IntArray(machineCount * horizon)
        for (i in 0 until n) {
            if (!present(state, i)) continue
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val s = state.assignment.intValue(starts[i])
            val from = max(0, s - tLow)
            val to = min(horizon, s + d - tLow)
            for (t in from until to) usage[idx * horizon + t] += r
        }
        var raw = 0L
        for (k in 0 until machineCount) {
            val cap = curBound(state, k)
            for (t in 0 until horizon) raw += penalty(usage[k * horizon + t], cap)
        }
        state.refPayload[factorId] = LsState(tLow, horizon, usage, raw)
        state.intPayload[factorId] = if (raw > 0L) 1 else 0
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as LsState).rawV > 0L

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as LsState).rawV)

    // ---- Probes (non-mutating) -------------------------------------------------------

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val ls = state.refPayload[factorId] as LsState
        val oldVal = state.assignment.intValue(intVar)
        if (oldVal == newValue) return 0
        val rawDelta = rawDeltaForIntSet(state, ls, intVar, oldVal, newValue)
        return degreeDelta(ls.rawV, rawDelta)
    }

    private fun rawDeltaForIntSet(
        state: LocalSearchState,
        ls: LsState,
        intVar: Int,
        oldVal: Int,
        newValue: Int,
    ): Long {
        startPos[intVar]?.let { i ->
            if (!present(state, i)) return 0L
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) return 0L
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) return 0L
            return shiftDelta(ls, idx, curBound(state, idx), oldVal, newValue, d, r)
        }
        machinePos[intVar]?.let { i ->
            if (!present(state, i)) return 0L
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) return 0L
            val s = state.assignment.intValue(starts[i])
            val oldIdx = machineIdx(oldVal)
            val newIdx = machineIdx(newValue)
            var delta = 0L
            if (inRange(oldIdx)) delta += spanDelta(ls, oldIdx, curBound(state, oldIdx), s, d, -r)
            if (inRange(newIdx)) delta += spanDelta(ls, newIdx, curBound(state, newIdx), s, d, +r)
            return delta
        }
        durPos[intVar]?.let { i ->
            if (!present(state, i)) return 0L
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) return 0L
            val r = curRes(state, i)
            if (r <= 0) return 0L
            val s = state.assignment.intValue(starts[i])
            return durDelta(ls, idx, curBound(state, idx), s, oldVal, newValue, r)
        }
        resPos[intVar]?.let { i ->
            if (!present(state, i)) return 0L
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) return 0L
            val d = curDur(state, i)
            if (d <= 0) return 0L
            val s = state.assignment.intValue(starts[i])
            return spanDelta(ls, idx, curBound(state, idx), s, d, newValue - oldVal)
        }
        boundPos[intVar]?.let { k ->
            return machinePenalty(ls, k, newValue) - machinePenalty(ls, k, oldVal)
        }
        return 0L
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        var rawDelta = 0L
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val sign = if (present(state, i)) -1 else +1 // flip toggles presence
            val s = state.assignment.intValue(starts[i])
            rawDelta += spanDelta(ls, idx, curBound(state, idx), s, d, sign * r)
        }
        return degreeDelta(ls.rawV, rawDelta)
    }

    // ---- Apply (mutating) ------------------------------------------------------------

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val ls = state.refPayload[factorId] as LsState
        val newValue = state.assignment.intValue(intVar)
        if (oldValue == newValue) return 0
        val before = compressViolation(ls.rawV)
        applyIntSetRaw(state, ls, intVar, oldValue, newValue)
        state.intPayload[factorId] = if (ls.rawV > 0L) 1 else 0
        return compressViolation(ls.rawV) - before
    }

    private fun applyIntSetRaw(state: LocalSearchState, ls: LsState, intVar: Int, oldValue: Int, newValue: Int) {
        startPos[intVar]?.let { i ->
            if (!present(state, i)) return
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) return
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) return
            val cap = curBound(state, idx)
            addSpan(ls, idx, cap, oldValue, d, -r)
            addSpan(ls, idx, cap, newValue, d, +r)
            return
        }
        machinePos[intVar]?.let { i ->
            if (!present(state, i)) return
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) return
            val s = state.assignment.intValue(starts[i])
            val oldIdx = machineIdx(oldValue)
            val newIdx = machineIdx(newValue)
            if (inRange(oldIdx)) addSpan(ls, oldIdx, curBound(state, oldIdx), s, d, -r)
            if (inRange(newIdx)) addSpan(ls, newIdx, curBound(state, newIdx), s, d, +r)
            return
        }
        durPos[intVar]?.let { i ->
            if (!present(state, i)) return
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) return
            val r = curRes(state, i)
            if (r <= 0) return
            val s = state.assignment.intValue(starts[i])
            val cap = curBound(state, idx)
            // Shrink/extend the tail between old and new end.
            if (newValue > oldValue) {
                addSpanRange(ls, idx, cap, s + oldValue, s + newValue, +r)
            } else {
                addSpanRange(ls, idx, cap, s + newValue, s + oldValue, -r)
            }
            return
        }
        resPos[intVar]?.let { i ->
            if (!present(state, i)) return
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) return
            val d = curDur(state, i)
            if (d <= 0) return
            val s = state.assignment.intValue(starts[i])
            addSpan(ls, idx, curBound(state, idx), s, d, newValue - oldValue)
            return
        }
        boundPos[intVar]?.let { k ->
            // Bound var already updated in the assignment; recompute machine k under both caps.
            ls.rawV += machinePenalty(ls, k, newValue) - machinePenalty(ls, k, oldValue)
            return
        }
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val ls = state.refPayload[factorId] as LsState
        val before = compressViolation(ls.rawV)
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val idx = machineIdx(state.assignment.intValue(machines[i]))
            if (!inRange(idx)) continue
            val d = curDur(state, i)
            val r = curRes(state, i)
            if (d <= 0 || r <= 0) continue
            val sign = if (present(state, i)) +1 else -1 // assignment already updated
            val s = state.assignment.intValue(starts[i])
            addSpan(ls, idx, curBound(state, idx), s, d, sign * r)
        }
        state.intPayload[factorId] = if (ls.rawV > 0L) 1 else 0
        return compressViolation(ls.rawV) - before
    }

    // ---- Raw-violation arithmetic ----------------------------------------------------

    /** `compress(raw + delta) − compress(raw)`. */
    private fun degreeDelta(raw: Long, delta: Long): Int = compressViolation(raw + delta) - compressViolation(raw)

    /** Penalty Δ of a single cell `(k, absT)` receiving [amount], reading current usage. */
    private fun cellDelta(ls: LsState, k: Int, cap: Int, absT: Int, amount: Int): Long {
        val t = absT - ls.tLow
        if (t < 0 || t >= ls.horizon) return 0L
        val u = ls.usage[cell(ls, k, t)]
        return (penalty(u + amount, cap) - penalty(u, cap)).toLong()
    }

    /** Penalty Δ of shifting a task `[oldS, oldS+d) → [newS, newS+d)` on machine [k] at
     *  constant [r]. Computed over the symmetric difference (overlap cells net to zero), so
     *  it stays exact and non-mutating regardless of how the intervals overlap. */
    private fun shiftDelta(ls: LsState, k: Int, cap: Int, oldS: Int, newS: Int, d: Int, r: Int): Long {
        if (oldS == newS || d <= 0 || r <= 0) return 0L
        val oldFrom = oldS
        val oldTo = oldS + d
        val newFrom = newS
        val newTo = newS + d
        var delta = 0L
        for (t in oldFrom until oldTo) if (t < newFrom || t >= newTo) delta += cellDelta(ls, k, cap, t, -r)
        for (t in newFrom until newTo) if (t < oldFrom || t >= oldTo) delta += cellDelta(ls, k, cap, t, +r)
        return delta
    }

    /** Penalty Δ of adding [amount] (signed) over absolute `[s, s+d)` on machine [k]. */
    private fun spanDelta(ls: LsState, k: Int, cap: Int, s: Int, d: Int, amount: Int): Long {
        if (amount == 0 || d <= 0) return 0L
        return spanRangeDelta(ls, k, cap, s, s + d, amount)
    }

    private fun spanRangeDelta(ls: LsState, k: Int, cap: Int, fromAbs: Int, toAbs: Int, amount: Int): Long {
        val from = max(0, fromAbs - ls.tLow)
        val to = min(ls.horizon, toAbs - ls.tLow)
        var delta = 0L
        for (t in from until to) {
            val u = ls.usage[cell(ls, k, t)]
            delta += penalty(u + amount, cap) - penalty(u, cap)
        }
        return delta
    }

    /** Penalty Δ of a duration change `[s,s+oldD) → [s,s+newD)` at constant [r] on machine [k]. */
    private fun durDelta(ls: LsState, k: Int, cap: Int, s: Int, oldD: Int, newD: Int, r: Int): Long {
        if (oldD == newD || r <= 0) return 0L
        return if (newD > oldD) {
            spanRangeDelta(ls, k, cap, s + oldD, s + newD, +r)
        } else {
            spanRangeDelta(ls, k, cap, s + newD, s + oldD, -r)
        }
    }

    /** Mutating: add [amount] over `[s, s+d)` on machine [k], updating usage and rawV. */
    private fun addSpan(ls: LsState, k: Int, cap: Int, s: Int, d: Int, amount: Int) {
        if (amount == 0 || d <= 0) return
        addSpanRange(ls, k, cap, s, s + d, amount)
    }

    private fun addSpanRange(ls: LsState, k: Int, cap: Int, fromAbs: Int, toAbs: Int, amount: Int) {
        if (amount == 0) return
        val from = max(0, fromAbs - ls.tLow)
        val to = min(ls.horizon, toAbs - ls.tLow)
        for (t in from until to) {
            val c = cell(ls, k, t)
            val u = ls.usage[c]
            val nu = u + amount
            ls.usage[c] = nu
            ls.rawV += penalty(nu, cap) - penalty(u, cap)
        }
    }

    /** Total penalty over machine [k]'s timeline under a hypothetical [cap]. */
    private fun machinePenalty(ls: LsState, k: Int, cap: Int): Long {
        var sum = 0L
        val base = k * ls.horizon
        for (t in 0 until ls.horizon) sum += penalty(ls.usage[base + t], cap)
        return sum
    }

    private fun computeTLow(state: LocalSearchState): Int {
        var lo = Int.MAX_VALUE
        for (i in 0 until n) {
            lo = min(
                lo,
                min(state.problem.intDomains[starts[i]].min, state.assignment.intValue(starts[i])),
            )
        }
        return if (lo == Int.MAX_VALUE) 0 else lo
    }

    private fun computeTHigh(state: LocalSearchState): Int {
        var hi = Int.MIN_VALUE
        for (i in 0 until n) {
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

    // ---- Repair ----------------------------------------------------------------------

    /** Repair a violated profile: target the worst (machine, time) cell, shift tasks running
     *  there off it (start moves) or onto a slacker machine (machine moves); plus the
     *  generic small-domain enumeration that guarantees single-step repair coverage. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val ls = state.refPayload[factorId] as LsState
        if (ls.rawV <= 0L) return
        // Locate the worst-penalty cell.
        var peakK = -1
        var peakT = -1
        var peakPen = 0
        for (k in 0 until machineCount) {
            val cap = curBound(state, k)
            for (t in 0 until ls.horizon) {
                val p = penalty(ls.usage[cell(ls, k, t)], cap)
                if (p > peakPen) {
                    peakPen = p
                    peakK = k
                    peakT = t
                }
            }
        }
        val absT = if (peakT >= 0) peakT + ls.tLow else 0
        val maxTargets = 4
        for (i in 0 until n) {
            val sv = starts[i]
            val sCur = state.assignment.intValue(sv)
            val d = curDur(state, i)
            val r = curRes(state, i)
            val mIdx = machineIdx(state.assignment.intValue(machines[i]))
            val sDom = state.problem.intDomains[sv]
            val runsAtPeak = peakK >= 0 && mIdx == peakK && r > 0 && d > 0 && sCur <= absT && absT < sCur + d
            if (runsAtPeak && upper) {
                // Upper-bound peak: push this task off the overloaded slot.
                val after = absT + 1
                val before = absT - d
                if (after in sDom && after != sCur) sink.addChannelingIntSet(state, sv, after)
                if (before in sDom && before != sCur) sink.addChannelingIntSet(state, sv, before)
                // Or move it to a machine with the most slack at this slot.
                proposeMachineMove(state, ls, i, sink)
            }
            // Generic single-var coverage (also drives the lower-bound / minimum-load case).
            if (sCur < sDom.max) sink.addChannelingIntSet(state, sv, sCur + 1)
            if (sCur > sDom.min) sink.addChannelingIntSet(state, sv, sCur - 1)
            enumerateOrSample(state, sv, sCur, sDom, maxTargets, sink)
            // Machine var coverage.
            val mv = machines[i]
            val mCur = state.assignment.intValue(mv)
            val mDom = state.problem.intDomains[mv]
            if (mCur < mDom.max) sink.addChannelingIntSet(state, mv, mCur + 1)
            if (mCur > mDom.min) sink.addChannelingIntSet(state, mv, mCur - 1)
            enumerateOrSample(state, mv, mCur, mDom, maxTargets, sink)
        }
    }

    /** Propose moving task [i] to whichever machine in its domain currently has the most
     *  slack at the task's interval (upper-bound case). */
    private fun proposeMachineMove(state: LocalSearchState, ls: LsState, i: Int, sink: MoveSink) {
        val mv = machines[i]
        val mDom = state.problem.intDomains[mv]
        val cur = state.assignment.intValue(mv)
        val s = state.assignment.intValue(starts[i])
        val d = curDur(state, i)
        val r = curRes(state, i)
        var bestVal = cur
        var bestDelta = 0L
        mDom.forEach { cand ->
            if (cand == cur) return@forEach
            val idx = machineIdx(cand)
            if (!inRange(idx)) return@forEach
            val delta = spanDelta(ls, idx, curBound(state, idx), s, d, +r) +
                spanDelta(ls, machineIdx(cur), curBound(state, machineIdx(cur)), s, d, -r)
            if (delta < bestDelta) {
                bestDelta = delta
                bestVal = cand
            }
        }
        if (bestVal != cur) sink.addChannelingIntSet(state, mv, bestVal)
    }

    private fun enumerateOrSample(
        state: LocalSearchState,
        v: Int,
        cur: Int,
        dom: IntDomain,
        maxTargets: Int,
        sink: MoveSink,
    ) {
        if (dom.size <= maxTargets) {
            dom.forEach { target -> if (target != cur) sink.addChannelingIntSet(state, v, target) }
        } else {
            repeat(maxTargets) {
                val pick = dom.valueAt(state.rng.nextInt(dom.size))
                if (pick != cur) sink.addChannelingIntSet(state, v, pick)
            }
        }
    }

    // ---- Propagation -----------------------------------------------------------------

    /** A capacity / coverage conflict is caused jointly by the start times, machine
     *  assignments, and (when variable) durations / resources / bounds of the involved tasks —
     *  cite the bound atoms of every variable the factor touches so CDCL learns a sound nogood
     *  (citing only the machine atoms would learn an unsound clause and prune valid space). */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    /**
     * Enforces the structural machine-index range, then a sound feasibility check:
     *  - **upper**: per-machine mandatory-profile time-tabling. A task with a *fixed* machine,
     *    duration, and resource contributes its compulsory part `[lst, ect)` to that machine's
     *    profile; any point exceeding the (fixed) bound proves infeasibility. This is the
     *    standard sound time-tabling overload test and, at a complete assignment, becomes an
     *    exact leaf checker.
     *  - **lower**: minimum-load shortfall isn't monotone in a mandatory profile, so the
     *    coverage check fires only once every relevant var is fixed (the exact leaf check).
     *
     * Bound tightening of individual starts is intentionally left to the LS gradient (this is
     * the LS-track generalization); the propagator's job here is soundness + leaf checking.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (mv in machines) {
            if (!state.tightenIntMin(mv, minMachine)) return false
            if (!state.tightenIntMax(mv, minMachine + machineCount - 1)) return false
        }
        return if (upper) propagateUpper(state) else propagateLowerIfFixed(state)
    }

    /** Per-machine time-tabling (the [Cumulative] propagator restricted to tasks whose machine
     *  is already fixed). Builds each machine's mandatory profile, fails on overload, and
     *  tightens the start bounds of fixed-machine tasks against placements that would push the
     *  profile over the machine's capacity — giving the complete solver real propagation. */
    private fun propagateUpper(state: PropagationState): Boolean {
        for (k in 0 until machineCount) {
            val cap = fixedBound(state, k) ?: continue // var bound not yet fixed → skip machine
            // Tasks definitely present and pinned to machine k, with fixed duration / resource.
            val members = ArrayList<Int>()
            for (i in 0 until n) {
                if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
                if (fixedMachineIdx(state, i) != k) continue
                val d = fixedDur(state, i) ?: continue
                val r = fixedRes(state, i) ?: continue
                if (d > 0 && r > 0) members.add(i)
            }
            if (members.isEmpty()) continue
            // Mandatory profile for machine k.
            val profile = MandatoryProfile()
            for (i in members) {
                val d = requireNotNull(fixedDur(state, i))
                val r = requireNotNull(fixedRes(state, i))
                val dom = state.intDomains[starts[i]]
                profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
            }
            if (!profile.build(cap)) return false
            // Tighten each member's start against overloading placements.
            for (i in members) {
                val d = requireNotNull(fixedDur(state, i))
                val r = requireNotNull(fixedRes(state, i))
                val v = starts[i]
                val dom = state.intDomains[v]
                if (dom.min == dom.max) continue
                val lstI = dom.max
                val ectI = dom.min + d
                val owns = lstI < ectI
                // The deduction depends on the other tasks' starts AND their (fixed) machine
                // assignments / durations / resources / this machine's bound — cite all touched
                // vars so the learned reason is sound.
                val ant = state.composeIntVarAtomAntecedents(intVars)
                var newMin = dom.min
                while (newMin <= state.intDomains[v].max &&
                    profile.overloadsAt(newMin, newMin + d, r, cap, owns, lstI, ectI)
                ) {
                    newMin++
                }
                if (newMin > state.intDomains[v].max) return false
                if (newMin != state.intDomains[v].min && !state.tightenIntMin(v, newMin, ant)) return false
                var newMax = state.intDomains[v].max
                while (newMax >= state.intDomains[v].min &&
                    profile.overloadsAt(newMax, newMax + d, r, cap, owns, lstI, ectI)
                ) {
                    newMax--
                }
                if (newMax < state.intDomains[v].min) return false
                if (newMax != state.intDomains[v].max && !state.tightenIntMax(v, newMax, ant)) return false
            }
        }
        return true
    }

    /** Lower-bound (minimum load) coverage check, only once every relevant var is fixed. */
    private fun propagateLowerIfFixed(state: PropagationState): Boolean {
        for (i in 0 until n) {
            if (OptPresence.isDefinitelyAbsent(presents, i, state)) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) return true // presence open
            val sd = state.intDomains[starts[i]]
            if (sd.min != sd.max) return true
            if (fixedMachineIdx(state, i) < 0) return true
            if (fixedDur(state, i) == null || fixedRes(state, i) == null) return true
        }
        for (k in 0 until machineCount) if (fixedBound(state, k) == null) return true
        // All fixed: build the exact per-machine usage and reject any coverage shortfall.
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (i in 0 until n) {
            val s = state.intDomains[starts[i]].min
            lo = min(lo, s)
            hi = max(hi, s + (fixedDur(state, i) ?: 0))
        }
        if (lo > hi) return true
        val horizon = hi - lo
        val usage = IntArray(machineCount * horizon)
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val k = fixedMachineIdx(state, i)
            val d = requireNotNull(fixedDur(state, i))
            val r = requireNotNull(fixedRes(state, i))
            if (d <= 0 || r <= 0) continue
            val s = state.intDomains[starts[i]].min
            for (t in (s - lo) until (s - lo + d)) usage[k * horizon + t] += r
        }
        for (k in 0 until machineCount) {
            val cap = requireNotNull(fixedBound(state, k))
            for (t in 0 until horizon) {
                val u = usage[k * horizon + t]
                if (u > 0 && u < cap) return false
            }
        }
        return true
    }

    private fun fixedMachineIdx(state: PropagationState, i: Int): Int {
        val d = state.intDomains[machines[i]]
        if (d.min != d.max) return -1
        val idx = d.min - minMachine
        return if (idx in 0 until machineCount) idx else -1
    }
    private fun fixedDur(state: PropagationState, i: Int): Int? {
        if (durationVars.isEmpty()) return durations[i]
        val d = state.intDomains[durationVars[i]]
        return if (d.min == d.max) d.min else null
    }
    private fun fixedRes(state: PropagationState, i: Int): Int? {
        if (resourceVars.isEmpty()) return resources[i]
        val d = state.intDomains[resourceVars[i]]
        return if (d.min == d.max) d.min else null
    }
    private fun fixedBound(state: PropagationState, k: Int): Int? {
        if (boundVars.isEmpty()) return bounds[k]
        val d = state.intDomains[boundVars[k]]
        return if (d.min == d.max) d.min else null
    }
}
