package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `result = arr[idx]` — the element constraint, native to local search instead of the old
 * per-index reified-linear + indicator-clause decomposition (which exploded one constraint
 * into ~5·len factors and 2·len aux bools, gave CBLS no gradient, and built a tightly-coupled
 * indicator web single-variable moves couldn't navigate — see issue #37).
 *
 * [arr] is either a constant table ([arrIsVars] = false; entries are literal values) or an
 * array of int-var ids ([arrIsVars] = true; the element value is the *current value* of the
 * selected var). [idx] is `[indexOffset]`-based — `1` for MiniZinc's default, so position 0
 * of [arr] is selected by `idx = 1`.
 *
 * Stateless (no payload): every query reads the live assignment, O(1) for the selected
 * element. Graded violation `|result − arr[idx]|` (run through [compressViolation]) gives a
 * descent gradient that pushes `result` toward the selected element (or the element toward
 * `result`); an out-of-range `idx` is graded by its distance back into range. Repair moves
 * snap `result` to the selected element, snap the selected element to `result`, or re-point
 * `idx` at a position whose value already equals `result`.
 */
class Element(
    val idx: Int,
    val result: Int,
    val arr: IntArray,
    val arrIsVars: Boolean,
    val indexOffset: Int = 1,
) : LocalSearchFactor {

    init {
        require(arr.isNotEmpty()) { "element: empty array" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray =
        if (arrIsVars) intArrayOf(idx, result) + arr else intArrayOf(idx, result)

    private val len: Int get() = arr.size

    /** Element value at 0-based [pos], read from the live assignment (var array) or the
     *  constant table. Caller guarantees `pos in 0 until len`. */
    private fun elementValue(state: LocalSearchState, pos: Int): Int =
        if (arrIsVars) state.assignment.intValue(arr[pos]) else arr[pos]

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // Stateless — re-derived per query.
    }

    /** Graded degree for hypothetical (idxVal, resultVal); [elemAt] supplies the selected
     *  element's value (allowing a hypothetical change to a var-array entry). */
    private inline fun degreeAt(idxVal: Int, resultVal: Int, elemAt: (pos: Int) -> Int): Int {
        val pos = idxVal - indexOffset
        if (pos < 0) return compressViolation((indexOffset - idxVal).toLong())
        if (pos >= len) return compressViolation((idxVal - (indexOffset + len - 1)).toLong())
        val ev = elemAt(pos)
        val d = resultVal.toLong() - ev
        return compressViolation(if (d < 0) -d else d)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val pos = state.assignment.intValue(idx) - indexOffset
        if (pos < 0 || pos >= len) return true
        return state.assignment.intValue(result) != elementValue(state, pos)
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degreeAt(state.assignment.intValue(idx), state.assignment.intValue(result)) { pos ->
            elementValue(state, pos)
        }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val curIdx = state.assignment.intValue(idx)
        val curResult = state.assignment.intValue(result)
        val newIdx = if (intVar == idx) newValue else curIdx
        val newResult = if (intVar == result) newValue else curResult
        val newDeg = degreeAt(newIdx, newResult) { pos ->
            if (arrIsVars && arr[pos] == intVar) newValue else elementValue(state, pos)
        }
        val oldDeg = degreeAt(curIdx, curResult) { pos -> elementValue(state, pos) }
        return newDeg - oldDeg
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        // Stateless: the engine reconciles cost from violationDegree after the assignment update.
        return 0
    }

    /** Repair a violated element. Three concurrent directions: clamp an out-of-range `idx`
     *  into range, snap `result` to the selected element, snap the selected element (var
     *  array) to `result`, or re-point `idx` at a position whose value already equals `result`. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val idxVal = state.assignment.intValue(idx)
        val pos = idxVal - indexOffset
        val idxDom = state.problem.intDomains[idx]
        val resultVal = state.assignment.intValue(result)
        val inRange = pos in 0 until len
        if (inRange && resultVal == elementValue(state, pos)) return // satisfied

        if (inRange) {
            val ev = elementValue(state, pos)
            // (a) Snap result to the selected element.
            if (ev in state.problem.intDomains[result]) sink.addChannelingIntSet(state, result, ev)
            // (b) Var array: snap the selected element to result.
            if (arrIsVars) {
                val sel = arr[pos]
                if (resultVal in state.problem.intDomains[sel]) sink.addChannelingIntSet(state, sel, resultVal)
            }
        } else {
            // Out of range: clamp idx into range as a fallback repair.
            val target = idxDom.clamp(if (idxVal < indexOffset) indexOffset else indexOffset + len - 1)
            if (target in idxDom && target != idxVal) sink.addChannelingIntSet(state, idx, target)
        }
        // (c) Re-point idx at a position whose value already equals result — applies whether
        //     idx is in or out of range (the strongest single move when a match exists).
        for (p in 0 until len) {
            if (elementValue(state, p) == resultVal) {
                val cand = p + indexOffset
                if (cand != idxVal && cand in idxDom) {
                    sink.addChannelingIntSet(state, idx, cand)
                    break
                }
            }
        }
    }

    /** Lower / upper bound of the element value at 0-based [pos] under [state]'s domains
     *  (a singleton for the constant table). */
    private fun elemLow(state: PropagationState, pos: Int): Int =
        if (arrIsVars) state.intDomains[arr[pos]].min else arr[pos]
    private fun elemHigh(state: PropagationState, pos: Int): Int =
        if (arrIsVars) state.intDomains[arr[pos]].max else arr[pos]

    /** Full element propagation (domain-consistent on [idx], bounds-consistent on [result],
     *  with both-way channeling once [idx] is fixed):
     *   1. `idx ∈ [indexOffset, indexOffset+len-1]` (structural).
     *   2. **Prune idx**: drop position `i` whose element range can't intersect `result`'s
     *      domain — that position can never satisfy `result = arr[i]`.
     *   3. **Bound result**: it must equal *some* still-reachable element, so tighten it to the
     *      union `[min elemLow, max elemHigh]` over idx's surviving positions.
     *   4. **idx fixed → channel** `result == arr[idx]` both ways (var array tightens the
     *      selected element back from `result`). */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (!state.tightenIntMin(idx, indexOffset)) return false
        if (!state.tightenIntMax(idx, indexOffset + len - 1)) return false

        val resultDom = state.intDomains[result]
        // 2. Prune idx positions whose element can't equal result. Collect first (don't mutate
        //    the domain mid-iteration), then exclude.
        val idxDom = state.intDomains[idx]
        var toExclude: com.eignex.klause.util.IntArrayList? = null
        idxDom.forEach { iv ->
            val pos = iv - indexOffset
            if (pos in 0 until len) {
                val lo = elemLow(state, pos)
                val hi = elemHigh(state, pos)
                // Element range [lo,hi] disjoint from result's [min,max] ⇒ position infeasible.
                if (hi < resultDom.min || lo > resultDom.max) {
                    (toExclude ?: com.eignex.klause.util.IntArrayList().also { toExclude = it }).add(iv)
                }
            }
        }
        toExclude?.let { ex ->
            val ant = state.composeIntVarAtomAntecedents(
                if (arrIsVars) intArrayOf(result) + arr else intArrayOf(result),
            )
            for (i in 0 until ex.size) if (!state.excludeIntValue(idx, ex[i], ant)) return false
        }

        // 3. Bound result to the union of reachable element ranges over idx's surviving domain.
        val survivor = state.intDomains[idx]
        var unionLo = Int.MAX_VALUE
        var unionHi = Int.MIN_VALUE
        survivor.forEach { iv ->
            val pos = iv - indexOffset
            if (pos in 0 until len) {
                val lo = elemLow(state, pos)
                val hi = elemHigh(state, pos)
                if (lo < unionLo) unionLo = lo
                if (hi > unionHi) unionHi = hi
            }
        }
        if (unionLo > unionHi) return false // no reachable position — infeasible
        val antIdx = state.composeIntVarAtomAntecedents(
            if (arrIsVars) intArrayOf(idx) + arr else intArrayOf(idx),
        )
        if (!state.tightenIntMin(result, unionLo, antIdx)) return false
        if (!state.tightenIntMax(result, unionHi, antIdx)) return false

        // 4. idx fixed → channel result against the selected element (var array: both ways).
        val d = state.intDomains[idx]
        if (d.min == d.max) {
            val pos = d.min - indexOffset
            if (pos in 0 until len) {
                val ant = state.composeIntVarAtomAntecedents(intArrayOf(idx))
                if (arrIsVars) {
                    val sel = arr[pos]
                    val rd = state.intDomains[result]
                    val sd = state.intDomains[sel]
                    if (!state.tightenIntMin(result, sd.min, ant)) return false
                    if (!state.tightenIntMax(result, sd.max, ant)) return false
                    if (!state.tightenIntMin(sel, rd.min, ant)) return false
                    if (!state.tightenIntMax(sel, rd.max, ant)) return false
                } else {
                    val v = arr[pos]
                    if (!state.tightenIntMin(result, v, ant)) return false
                    if (!state.tightenIntMax(result, v, ant)) return false
                }
            }
        }
        return true
    }
}
