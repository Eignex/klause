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
 * Propagation: tighten [idx] to its legal index range.
 */
class ArgMinMax(
    /** Variable id holding the argmin/argmax index. */
    val idx: Int,
    /** The variable ids being ranked. */
    val xs: IntArray,
    /** True for argmax, false for argmin. */
    val max: Boolean,
    /** Integer representing index 0 of [xs]. */
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
            if (extreme(v, bestValue)) {
                bestIdx = i
                bestValue = v
            }
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

    /** Graded degree for a hypothetical `idx = idxVal` and operand values supplied by
     *  [valueAt] (0-based position → value). `0` exactly when `idx` names the canonical
     *  arg-extreme position; otherwise positive. Two regimes:
     *   - `idx` out of `[indexOffset, indexOffset+n-1]` → graded by its distance back into range.
     *   - `idx` in range but not the (lowest-index) extremum → graded by the value gap between
     *     the extremum and the value at the named position, floored at 1 so a tie (named
     *     position holds the extreme value but isn't the lowest such index) still reads violated. */
    private inline fun degreeAt(idxVal: Int, valueAt: (pos: Int) -> Int): Int {
        val lo = indexOffset
        val hi = indexOffset + xs.size - 1
        if (idxVal < lo) return compressViolation((lo - idxVal).toLong())
        if (idxVal > hi) return compressViolation((idxVal - hi).toLong())
        val pos = idxVal - indexOffset
        var bestPos = 0
        var bestValue = valueAt(0)
        for (i in 1 until xs.size) {
            val v = valueAt(i)
            if (extreme(v, bestValue)) {
                bestPos = i
                bestValue = v
            }
        }
        if (bestPos == pos) return 0
        val gap = bestValue.toLong() - valueAt(pos)
        val ad = if (gap < 0) -gap else gap
        return maxOf(1, compressViolation(ad))
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degreeAt(state.assignment.intValue(idx)) { pos -> state.assignment.intValue(xs[pos]) }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val curIdx = state.assignment.intValue(idx)
        val newIdx = if (intVar == idx) newValue else curIdx
        val newDeg = degreeAt(newIdx) { pos ->
            if (xs[pos] == intVar) newValue else state.assignment.intValue(xs[pos])
        }
        val oldDeg = degreeAt(curIdx) { pos -> state.assignment.intValue(xs[pos]) }
        return newDeg - oldDeg
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        // Stateless: reconstruct the pre-move degree from [oldValue] and compare to the live one.
        val curIdxNow = state.assignment.intValue(idx)
        val oldIdx = if (intVar == idx) oldValue else curIdxNow
        val newDeg = degreeAt(curIdxNow) { pos -> state.assignment.intValue(xs[pos]) }
        val oldDeg = degreeAt(oldIdx) { pos ->
            if (xs[pos] == intVar) oldValue else state.assignment.intValue(xs[pos])
        }
        return newDeg - oldDeg
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
            sink.addChannelingIntSet(state, idx, expected)
        }
        // Push xs[curIdx] toward the current extreme value so the named index becomes extreme.
        val pos = curIdx - indexOffset
        if (pos in xs.indices) {
            val v = xs[pos]
            val d = state.problem.intDomains[v]
            val target = if (max) bestValue + 1 else bestValue - 1
            val clamped = d.clamp(target)
            if (clamped != state.assignment.intValue(v)) sink.addChannelingIntSet(state, v, clamped)
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
            if (d.min != d.max) {
                allSingleton = false
                break
            }
        }
        if (allSingleton) {
            var bestPos = 0
            var bestVal = state.intDomains[xs[0]].min
            for (i in 1 until xs.size) {
                val v = state.intDomains[xs[i]].min
                if (extreme(v, bestVal)) {
                    bestPos = i
                    bestVal = v
                }
            }
            val expected = bestPos + indexOffset
            val ant = state.composeIntVarAtomAntecedents(xs)
            if (!state.tightenIntMin(idx, expected, ant)) return false
            if (!state.tightenIntMax(idx, expected, ant)) return false
        }
        return true
    }
}
