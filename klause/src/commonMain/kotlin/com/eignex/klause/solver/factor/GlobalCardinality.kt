package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Global Cardinality Constraint (GCC). Covers the four MiniZinc variants in one factor:
 *
 *  - `global_cardinality(xs, cover, counts)` — `counts[k] = #{i : xs[i] = cover[k]}`. Use
 *    [countVars] (`size = cover.size`) and [closed] = `false`.
 *  - `global_cardinality_low_up(xs, cover, lo, up)` — `lo[k] ≤ #{i : xs[i] = cover[k]} ≤ up[k]`.
 *    Use [countLow] / [countHigh] (constant arrays) and [countVars] = `null`.
 *  - `_closed` variants additionally require every `xs[i] ∈ cover` — i.e. no value outside
 *    the cover set may appear. Pass [closed] = `true`.
 *
 * Exactly one of ([countVars], [countLow]+[countHigh]) is non-null — the constructor
 * validates.
 *
 * Propagation in this first cut: bound-tighten each `countVars[k]` (or `lo/up[k]`) from the
 * count of definite-matchers (every val pinned to `cover[k]`) and possible-matchers (vars
 * whose domain still contains `cover[k]`). Stronger Régin-style flow lands later.
 */
class GlobalCardinality(
    val xs: IntArray,
    val cover: IntArray,
    val countVars: IntArray? = null,
    val countLow: IntArray? = null,
    val countHigh: IntArray? = null,
    val closed: Boolean = false,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "gcc: empty xs" }
        require(cover.isNotEmpty()) { "gcc: empty cover" }
        if (countVars != null) {
            require(countVars.size == cover.size) { "gcc: countVars size mismatch" }
            require(countLow == null && countHigh == null) { "gcc: pass either countVars OR countLow+countHigh" }
        } else {
            require(countLow != null && countHigh != null) { "gcc: missing countLow/countHigh" }
            require(countLow.size == cover.size && countHigh.size == cover.size) { "gcc: lo/hi size mismatch" }
        }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = run {
        val cv = countVars
        if (cv != null) xs + cv else xs
    }

    private val coverIndexByValue: HashMap<Int, Int> = run {
        val m = HashMap<Int, Int>(cover.size * 2)
        for (i in cover.indices) m[cover[i]] = i
        m
    }

    /** Per-cover-index count under the current assignment. */
    private class State(val counts: IntArray)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = IntArray(cover.size)
        for (x in xs) {
            val value = state.assignment.intValue(x)
            val idx = coverIndexByValue[value] ?: continue  // out-of-cover; counts unaffected
            counts[idx]++
        }
        state.refPayload[factorId] = State(counts)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        // Per-cover constraint check.
        for (k in cover.indices) {
            if (countVars != null) {
                if (state.assignment.intValue(countVars[k]) != s.counts[k]) return true
            } else {
                val cnt = s.counts[k]
                if (cnt < countLow!![k] || cnt > countHigh!![k]) return true
            }
        }
        // Closed variant: every xs[i] must be in cover.
        if (closed) {
            for (x in xs) if (state.assignment.intValue(x) !in coverIndexByValue) return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolated(state, factorId)
        // Simulate the change by adjusting a counts copy.
        val sim = s.counts.copyOf()
        var occurrencesInXs = 0
        for (x in xs) if (x == intVar) occurrencesInXs++
        if (occurrencesInXs > 0) {
            val old = state.assignment.intValue(intVar)
            val oldIdx = coverIndexByValue[old]
            if (oldIdx != null) sim[oldIdx] -= occurrencesInXs
            val newIdx = coverIndexByValue[newValue]
            if (newIdx != null) sim[newIdx] += occurrencesInXs
        }
        val willViolate = simulatedViolation(state, intVar, newValue, sim)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun simulatedViolation(
        state: LocalSearchState, intVar: Int, newValue: Int, simCounts: IntArray,
    ): Boolean {
        for (k in cover.indices) {
            if (countVars != null) {
                val expected = if (countVars[k] == intVar) newValue
                else state.assignment.intValue(countVars[k])
                if (expected != simCounts[k]) return true
            } else {
                if (simCounts[k] < countLow!![k] || simCounts[k] > countHigh!![k]) return true
            }
        }
        if (closed) {
            for (x in xs) {
                val v = if (x == intVar) newValue else state.assignment.intValue(x)
                if (v !in coverIndexByValue) return true
            }
        }
        return false
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = state.refPayload[factorId].let { p ->
            // Use the existing isViolated against pre-update counts.
            // The counts haven't been updated yet — we compare against assignment which IS post-update.
            // To compare against pre-update, simulate the inverse.
            val sim = s.counts.copyOf()
            var occ = 0
            for (x in xs) if (x == intVar) occ++
            if (occ > 0) {
                val oldIdx = coverIndexByValue[oldValue]
                val newIdx = coverIndexByValue[cur]
                if (newIdx != null) sim[newIdx] -= occ  // undo post-update
                if (oldIdx != null) sim[oldIdx] += occ  // restore pre-update
            }
            simulatedViolation(state, intVar, oldValue, sim)
        }
        var occurrencesInXs = 0
        for (x in xs) if (x == intVar) occurrencesInXs++
        if (occurrencesInXs > 0) {
            val oldIdx = coverIndexByValue[oldValue]
            val newIdx = coverIndexByValue[cur]
            if (oldIdx != null) s.counts[oldIdx] -= occurrencesInXs
            if (newIdx != null) s.counts[newIdx] += occurrencesInXs
        }
        val nowViolated = isViolated(state, factorId)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (k in cover.indices) {
            val target = cover[k]
            var definite = 0
            var possible = 0
            for (x in xs) {
                val d = state.intDomains[x]
                if (d.min == d.max && d.min == target) definite++
                if (target in d) possible++
            }
            if (countVars != null) {
                if (!state.tightenIntMin(countVars[k], definite)) return false
                if (!state.tightenIntMax(countVars[k], possible)) return false
            } else {
                if (countLow!![k] > possible) return false
                if (countHigh!![k] < definite) return false
            }
        }
        if (closed) {
            // Every xs[i] must take a value from cover. Tighten each xs[i]'s domain to the
            // cover set: exclude any value not in cover. With sparse domains this punches
            // holes; with contiguous it tightens bounds.
            val coverSet = coverIndexByValue.keys
            for (x in xs) {
                val d = state.intDomains[x]
                // Build a list of values currently in d but not in cover.
                val toRemove = ArrayList<Int>()
                d.forEach { if (it !in coverSet) toRemove.add(it) }
                for (v in toRemove) if (!state.excludeIntValue(x, v)) return false
            }
        }
        return true
    }
}
