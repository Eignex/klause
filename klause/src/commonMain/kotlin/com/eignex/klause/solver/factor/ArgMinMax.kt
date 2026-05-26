package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `arg_max(idx, xs)` / `arg_min(idx, xs)` — [idx] equals the (smallest) position whose
 * value is the maximum (resp. minimum) of [xs]. Tie-breaking: lex by position
 * (lowest-index winner), matching MiniZinc semantics.
 *
 * [indexOffset] is the value `idx` takes for position 0 in [xs] — typically `1` for the
 * MiniZinc 1-based default, `0` for the canonical klause 0-based form.
 *
 * Propagation in this first cut: tighten [idx] to its legal index range. Per-element
 * inferences (e.g. forcing `xs[idx].max ≥ max(xs.min)`) land in the next strength pass.
 */
class ArgMinMax(
    val idx: Int,
    val xs: IntArray,
    val max: Boolean,
    val indexOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "arg_${if (max) "max" else "min"}: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(idx)

    private fun extreme(a: Int, b: Int): Boolean = if (max) a > b else a < b

    private fun argExtreme(state: LocalSearchState): Int {
        var bestIdx = 0
        var bestValue = state.assignment.intValue(xs[0])
        for (i in 1 until xs.size) {
            val v = state.assignment.intValue(xs[i])
            if (extreme(v, bestValue)) { bestIdx = i; bestValue = v }
        }
        return bestIdx
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // No payload — re-derive on each query. The factor is O(n) per call but the structure
        // is simple enough that incremental tracking doesn't pay off until n is large.
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val expected = argExtreme(state) + indexOffset
        return state.assignment.intValue(idx) != expected
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        // Simulate: find best with intVar = newValue.
        var bestIdx = -1
        var bestValue = 0
        for (i in xs.indices) {
            val v = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            if (bestIdx == -1 || extreme(v, bestValue)) { bestIdx = i; bestValue = v }
        }
        val expected = bestIdx + indexOffset
        val newIdxValue = if (intVar == idx) newValue else state.assignment.intValue(idx)
        val willViolate = newIdxValue != expected
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        // Stateless. Delta queries already correct.
        return 0
    }

    /** Repair: either snap `idx` to the current arg-extreme, or shift `xs[idx]` to the
     *  current arg-extreme value so the named position becomes extreme. */
    override fun proposeRepairMoves(
        state: LocalSearchState,
        factorId: Int,
        sink: com.eignex.klause.solver.localsearch.MoveSink,
    ) {
        if (!isViolated(state, factorId)) return
        val bestIdx = argExtreme(state)
        val bestValue = state.assignment.intValue(xs[bestIdx])
        val expected = bestIdx + indexOffset
        val curIdx = state.assignment.intValue(idx)
        if (expected != curIdx && expected in state.problem.intDomains[idx]) {
            sink.addIntSet(idx, expected)
        }
        // Push xs[curIdx] toward the current extreme value so the named index becomes extreme.
        val pos = curIdx - indexOffset
        if (pos in xs.indices) {
            val v = xs[pos]
            val d = state.problem.intDomains[v]
            val target = if (max) bestValue + 1 else bestValue - 1
            val clamped = d.clamp(target)
            if (clamped != state.assignment.intValue(v)) sink.addIntSet(v, clamped)
        }
    }

    /** Bound-only conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // idx ∈ [indexOffset, indexOffset + xs.size - 1]. These bound facts are
        // structural (true from the factor's existence) so antecedents are null.
        if (!state.tightenIntMin(idx, indexOffset)) return false
        if (!state.tightenIntMax(idx, indexOffset + xs.size - 1)) return false
        // If all operands are singleton, compute the true argextreme and force idx to match.
        var allSingleton = true
        for (x in xs) {
            val d = state.intDomains[x]
            if (d.min != d.max) { allSingleton = false; break }
        }
        if (allSingleton) {
            var bestPos = 0
            var bestVal = state.intDomains[xs[0]].min
            for (i in 1 until xs.size) {
                val v = state.intDomains[xs[i]].min
                if (extreme(v, bestVal)) { bestPos = i; bestVal = v }
            }
            val expected = bestPos + indexOffset
            val ant = state.composeIntVarAtomAntecedents(xs)
            if (!state.tightenIntMin(idx, expected, ant)) return false
            if (!state.tightenIntMax(idx, expected, ant)) return false
        }
        return true
    }
}
