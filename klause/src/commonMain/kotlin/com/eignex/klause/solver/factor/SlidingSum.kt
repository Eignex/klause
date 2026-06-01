package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.math.max
import kotlin.math.min

/**
 * `sliding_sum(low, up, seq, vs)` — every contiguous window of [seq] elements of [vs] sums to
 * a value in `[low, up]`. MiniZinc's windowed-*sum* constraint (the dual of `Sequence`'s
 * windowed-*count*), native here instead of the prefix-sum + per-window linear decomposition.
 *
 * LS cost is graded through [compressViolation]:
 *   `raw = Σ_window (max(0, low − sum) + max(0, sum − up))`
 * — each window contributes how far its sum sits outside `[low, up]`, so CBLS sees a gradient
 * that pushes element values up into under-full windows and down out of over-full ones.
 *
 * Propagation does per-window linear bound reasoning (`vs[j] ≥ low − Σ_{k≠j} max(vs[k])` and
 * the dual), which at a complete assignment becomes an exact window-sum leaf check.
 */
class SlidingSum(
    val low: Int,
    val up: Int,
    val seq: Int,
    val vs: IntArray,
) : LocalSearchFactor {

    init {
        require(seq >= 1) { "sliding_sum: seq must be ≥ 1, got $seq" }
        require(low <= up) { "sliding_sum: low ($low) must be ≤ up ($up)" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = vs

    /** Number of length-[seq] windows; 0 when the sequence is shorter than one window. */
    private val numWindows: Int = max(0, vs.size - seq + 1)

    private class State(val windowSum: IntArray, var rawV: Long)

    private fun penalty(sum: Int): Int = max(0, low - sum) + max(0, sum - up)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val ws = IntArray(numWindows)
        var raw = 0L
        for (w in 0 until numWindows) {
            var s = 0
            for (j in w until w + seq) s += state.assignment.intValue(vs[j])
            ws[w] = s
            raw += penalty(s)
        }
        state.refPayload[factorId] = State(ws, raw)
        state.intPayload[factorId] = if (raw > 0L) 1 else 0
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as State).rawV > 0L

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as State).rawV)

    /** Windows covering element position [j]: indices `[max(0, j−seq+1), min(numWindows−1, j)]`. */
    private inline fun forEachWindowOf(j: Int, action: (w: Int) -> Unit) {
        val lo = max(0, j - seq + 1)
        val hi = min(numWindows - 1, j)
        for (w in lo..hi) action(w)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val oldVal = state.assignment.intValue(intVar)
        if (oldVal == newValue) return 0
        val diff = newValue - oldVal
        var rawDelta = 0L
        // intVar may appear at several positions in vs (e.g. a repeated var) — handle each.
        for (j in vs.indices) {
            if (vs[j] != intVar) continue
            forEachWindowOf(j) { w ->
                val old = s.windowSum[w]
                rawDelta += penalty(old + diff) - penalty(old)
            }
        }
        return compressViolation(s.rawV + rawDelta) - compressViolation(s.rawV)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val newValue = state.assignment.intValue(intVar)
        if (oldValue == newValue) return 0
        val diff = newValue - oldValue
        val before = compressViolation(s.rawV)
        for (j in vs.indices) {
            if (vs[j] != intVar) continue
            forEachWindowOf(j) { w ->
                val old = s.windowSum[w]
                val nu = old + diff
                s.windowSum[w] = nu
                s.rawV += penalty(nu) - penalty(old)
            }
        }
        state.intPayload[factorId] = if (s.rawV > 0L) 1 else 0
        return compressViolation(s.rawV) - before
    }

    /** Repair: push elements of an under-full window up and an over-full window down, plus the
     *  generic small-domain enumeration that guarantees single-step repair coverage. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.rawV <= 0L) return
        for (w in 0 until numWindows) {
            val sum = s.windowSum[w]
            if (sum in low..up) continue
            val raise = sum < low
            for (j in w until w + seq) {
                val v = vs[j]
                val cur = state.assignment.intValue(v)
                val dom = state.problem.intDomains[v]
                if (raise && cur < dom.max) sink.addChannelingIntSet(state, v, cur + 1)
                if (!raise && cur > dom.min) sink.addChannelingIntSet(state, v, cur - 1)
            }
        }
        // Generic coverage for the oracle / fine moves.
        val MAX_TARGETS = 4
        for (v in vs) {
            val cur = state.assignment.intValue(v)
            val dom = state.problem.intDomains[v]
            if (cur < dom.max) sink.addChannelingIntSet(state, v, cur + 1)
            if (cur > dom.min) sink.addChannelingIntSet(state, v, cur - 1)
            if (dom.size <= MAX_TARGETS) {
                dom.forEach { t -> if (t != cur) sink.addChannelingIntSet(state, v, t) }
            } else {
                repeat(MAX_TARGETS) {
                    val pick = dom.valueAt(state.rng.nextInt(dom.size))
                    if (pick != cur) sink.addChannelingIntSet(state, v, pick)
                }
            }
        }
    }

    /** Window vars are the joint cause of any window-sum conflict — cite all of [vs]. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, vs, excludeIdx = -1, extraLit = 0)

    /** Per-window linear bound propagation: within each window the sum lies in `[low, up]`, so
     *  each member is bounded by the residual once the others take their extreme values. */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (numWindows == 0) return true
        val ant = state.composeIntVarAtomAntecedents(vs)
        for (w in 0 until numWindows) {
            var sumMin = 0L
            var sumMax = 0L
            for (j in w until w + seq) {
                val d = state.intDomains[vs[j]]
                sumMin += d.min
                sumMax += d.max
            }
            if (sumMin > up || sumMax < low) return false // window infeasible
            for (j in w until w + seq) {
                val v = vs[j]
                val d = state.intDomains[v]
                // Others' contribution range with vs[j] removed.
                val othersMin = sumMin - d.min
                val othersMax = sumMax - d.max
                val loBound = low - othersMax // vs[j] ≥ low − Σ_{k≠j} max
                val hiBound = up - othersMin // vs[j] ≤ up  − Σ_{k≠j} min
                if (loBound > d.max || hiBound < d.min) return false
                if (loBound > d.min && !state.tightenIntMin(v, loBound.toInt(), ant)) return false
                if (hiBound < d.max && !state.tightenIntMax(v, hiBound.toInt(), ant)) return false
            }
        }
        return true
    }
}
