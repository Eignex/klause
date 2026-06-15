package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap

/**
 * `result = arr(idx)` — the element constraint, native to local search rather than a
 * per-index reified-linear + indicator-clause decomposition (which would explode one
 * constraint into ~5·len factors and 2·len aux bools and give CBLS no gradient).
 *
 * [arr] is either a constant table ([arrIsVars] = false; entries are literal values) or an
 * array of int-var ids ([arrIsVars] = true; the element value is the *current value* of the
 * selected var). [idx] is `[indexOffset]`-based — `1` for MiniZinc's default, so position 0
 * of [arr] is selected by `idx = 1`.
 *
 * Stateless (no payload): every query reads the live assignment, O(1) for the selected
 * element. Graded violation `|result − arr(idx)|` (run through [compressViolation]) gives a
 * descent gradient that pushes `result` toward the selected element (or the element toward
 * `result`); an out-of-range `idx` is graded by its distance back into range. Repair moves
 * snap `result` to the selected element, snap the selected element to `result`, or re-point
 * `idx` at a position whose value already equals `result`.
 */
class Element(
    /** Index variable id. */
    val idx: Int,
    /** Result variable id (`result = arr(idx - indexOffset)`). */
    val result: Int,
    /** The indexed array: variable ids when [arrIsVars], else constant values. */
    val arr: IntArray,
    /** Whether [arr] holds variable ids (true) or constants (false). */
    val arrIsVars: Boolean,
    /** Integer representing index 0 of [arr]. */
    val indexOffset: Int = 1,
) : Factor {

    init {
        require(arr.isNotEmpty()) { "element: empty array" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        Element(intMap[idx], intMap[result], if (arrIsVars) arr.remapVars(intMap) else arr, arrIsVars, indexOffset)

    // Positional: the array is ordered (idx selects by position), so the key keeps array order
    // rather than sorting. Encodes every distinguishing field — array kind, offset, idx, result,
    // and the ordered array (var ids when [arrIsVars], else constant values) — so two non-equivalent
    // Elements never collide (a coarser key would let symmetry verification accept a false swap).
    override fun structuralKey(): String = "element:$arrIsVars:$indexOffset:$idx:$result:" + arr.joinToString(",")

    // No remapValues override (value symmetry stays blocked when an Element is present, #536): the
    // value-symmetry verifier relabels a factor's value *constants* and compares keys, but `idx` is a
    // *variable* whose value selects which constant is read. A swap of two idx positions leaves the
    // constant array unchanged, so the verifier would (wrongly) accept it as a value symmetry. Since
    // remapValues only sees the factor, not idx's domain, it can't tell positions from values — so it
    // must conservatively return `null` (the default). Regular/Mdd are sound because their seq values
    // *are* the relabelable symbols, with no such positional coupling.

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray =
        if (arrIsVars) intArrayOf(idx, result) + arr else intArrayOf(idx, result)

    /**
     * Advisor subscription (#623): the **variable-array** path is full GAC over interior domains, so
     * it subscribes to *every* kind on every variable and consumes the dirty-variable delta (#624)
     * to scope its unchanged-domains gate to the variables that actually changed, instead of the
     * O(`intVars.size`) ref-scan on every (often redundant fixpoint) re-fire. The **constant-array**
     * path keeps occurrence wakeup and its own reversible `domRef` fast path in [ElementConstState]
     * (two variables — `idx`/`result` — so a delta would buy nothing). Subscriptions cover all kinds
     * because the var array's consistency is hole-aware membership, not just bounds.
     */
    override val initialIntEventWatches: IntArray? = if (!arrIsVars) {
        null
    } else {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * IntEvent.COUNT)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
            out[w++] = IntEvent.pack(v, IntEvent.FIXED)
        }
        out
    }

    override val consumesIntEventDelta: Boolean = arrIsVars

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
    private inline fun degreeAt(idxVal: Int, resultVal: Int, softCap: Int, elemAt: (pos: Int) -> Int): Int {
        val pos = idxVal - indexOffset
        if (pos < 0) return compressViolation((indexOffset - idxVal).toLong(), softCap)
        if (pos >= len) return compressViolation((idxVal - (indexOffset + len - 1)).toLong(), softCap)
        val ev = elemAt(pos)
        val d = resultVal.toLong() - ev
        return compressViolation(if (d < 0) -d else d, softCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val pos = state.assignment.intValue(idx) - indexOffset
        if (pos !in 0..<len) return true
        return state.assignment.intValue(result) != elementValue(state, pos)
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degreeAt(state.assignment.intValue(idx), state.assignment.intValue(result), state.violationSoftCap) { pos ->
            elementValue(state, pos)
        }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val curIdx = state.assignment.intValue(idx)
        val curResult = state.assignment.intValue(result)
        val newIdx = if (intVar == idx) newValue else curIdx
        val newResult = if (intVar == result) newValue else curResult
        val cap = state.violationSoftCap
        val newDeg = degreeAt(newIdx, newResult, cap) { pos ->
            if (arrIsVars && arr[pos] == intVar) newValue else elementValue(state, pos)
        }
        val oldDeg = degreeAt(curIdx, curResult, cap) { pos -> elementValue(state, pos) }
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

    /** Cached domain refs of every [intVars] entry at the last successful propagate, for the
     *  unchanged-domains fast path. NOT a [PropagationState.SnapshottablePayload]: Element fires
     *  often and its var-array `intVars` can be long, so per-push snapshot copies would cost more
     *  than they save. Soundness doesn't need snapshotting — `IntDomain` is immutable, so per-entry
     *  reference identity means that domain is unchanged; after a backtrack the restored domain
     *  objects differ from these (deeper) refs, so the check simply misses (a full propagate runs)
     *  rather than skipping unsoundly. The slot drifts across snapshot/restore like CDCL watches.
     *  [posOf] maps a variable id to one of its positions in [intVars], so the dirty-variable delta
     *  (var ids) can index [cachedDoms] without an O(arity) lookup. */
    private class Cache(val cachedDoms: Array<IntDomain?>, val posOf: IntIntMap)

    /** Element propagation. Both kinds first tighten `idx ∈ [indexOffset, indexOffset+len-1]`,
     *  then filter to full GAC: a **constant** array via the incremental [ElementConstState], a
     *  **var** array via [propagateVarArray].
     *
     *  Fast path: if no [intVars] domain reference changed since the last successful propagate, the
     *  previous fixpoint still holds (every prune/tighten below is a pure function of these domains),
     *  so it returns immediately — skipping the var-array path's O(len · |dom|) per-position scan on
     *  the redundant re-fires that fixpoint iteration produces. */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (!state.tightenIntMin(idx, indexOffset)) return false
        if (!state.tightenIntMax(idx, indexOffset + len - 1)) return false
        if (!arrIsVars) {
            // Constant array: reversible, delta-driven GAC (see [ElementConstState]). Its own
            // domain-ref fast path short-circuits an unchanged fire; the bound tighten above is a
            // no-op after the first fire.
            val st = (state.refPayload[factorId] as? ElementConstState)
                ?: ElementConstState(state, idx, result, arr, indexOffset).also { state.refPayload[factorId] = it }
            return st.propagate(state)
        }
        // Variable array: full GAC with the unchanged-domains fast path (per-position scans are
        // expensive enough that the redundant fixpoint re-fires must be skipped).
        val cache = (state.refPayload[factorId] as? Cache) ?: run {
            // Map each (distinct) variable id to a position in intVars, so the dirty-variable delta
            // (var ids) can scope the unchanged-domains check below.
            val firstPos = HashMap<Int, Int>(intVars.size)
            for (k in intVars.indices) firstPos.getOrPut(intVars[k]) { k }
            val fresh = Cache(
                arrayOfNulls(intVars.size),
                IntIntMap.build(firstPos.keys.toIntArray(), firstPos.values.toIntArray(), absent = -1),
            )
            state.refPayload[factorId] = fresh
            fresh
        }
        // Unchanged-domains gate, scoped by the dirty-variable delta (#624): the delta is a superset
        // of the variables changed since the last successful propagate, recorded into cachedDoms in
        // lockstep — so a real change always appears here, while a self-mutation from the previous
        // fire shows cachedDoms === current (recorded post-mutation) and a stale backtrack entry shows
        // no change. The first fire (cachedDoms not yet recorded) always runs a full propagate.
        val delta = state.drainIntEventDirtyVars(factorId)
        var changed = cache.cachedDoms[0] == null
        if (!changed) {
            for (vid in delta) {
                val k = cache.posOf[vid]
                if (k >= 0 && cache.cachedDoms[k] !== state.intDomains[vid]) {
                    changed = true
                    break
                }
            }
        }
        if (!changed) return true
        if (!propagateVarArray(state)) return false
        // Record post-propagate refs so the next (often no-op) fire can short-circuit.
        for (k in intVars.indices) cache.cachedDoms[k] = state.intDomains[intVars[k]]
        return true
    }

    /**
     * Full GAC for a **variable** array (`arrIsVars == true`). The selected element is the live
     * value of a var, so unlike the constant table the consistency test is hole-aware domain
     * *membership*, not interval overlap:
     *   - **idx**: position `i` is supported iff `dom(arr(i)) ∩ dom(result) ≠ ∅` (a position whose
     *     element domain meets result's range only inside a mutual hole is dropped — interval
     *     overlap would have kept it).
     *   - **result**: value `v` is supported iff some still-reachable position `i` can take it
     *     (`v ∈ dom(arr(i))`), so interior holes are punched for values no surviving element can
     *     produce — not merely the bounds union over reachable positions.
     *   - **idx fixed → channel**: `result == arr(idx)`, so the selected element loses every value
     *     not live in `result` (the symmetric result-side prune falls out of the result pass above
     *     when only one position survives).
     *
     * Each direction does a per-position / per-value domain scan (O(len · |dom|)), heavier than
     * the constant path — see #540: gated by no instance shown element-propagation-bound, the
     * brute-force `assertGac` oracle validates completeness.
     */
    private fun propagateVarArray(state: PropagationState): Boolean {
        val resultDom = state.intDomains[result]
        // 2. Prune idx: drop a position whose element domain is disjoint (hole-aware) from result's.
        var toExclude: IntArrayList? = null
        state.intDomains[idx].forEach { iv ->
            val pos = iv - indexOffset
            if (pos in 0 until len && !domainsIntersect(state.intDomains[arr[pos]], resultDom)) {
                (toExclude ?: IntArrayList().also { toExclude = it }).add(iv)
            }
        }
        toExclude?.let { ex ->
            for (i in 0 until ex.size) {
                // Reason: the disjoint domains of result and the selected element (holes+bounds).
                val ant = collectHoleAndBoundAntecedents(state, intArrayOf(result, arr[ex[i] - indexOffset]))
                if (!state.excludeIntValue(idx, ex[i], ant)) return false
            }
        }

        // Surviving positions (idx may have shrunk above). No reachable position ⇒ infeasible.
        val positions = IntArrayList()
        state.intDomains[idx].forEach { iv ->
            val pos = iv - indexOffset
            if (pos in 0 until len) positions.add(pos)
        }
        if (positions.size == 0) return false

        // 3. Prune result: value v survives iff some reachable position can take it. Punch holes
        //    for unreachable values (GAC), not just the [min, max] union of reachable elements.
        var resExclude: IntArrayList? = null
        state.intDomains[result].forEach { rv ->
            var supported = false
            for (k in 0 until positions.size) {
                if (rv in state.intDomains[arr[positions[k]]]) {
                    supported = true
                    break
                }
            }
            if (!supported) (resExclude ?: IntArrayList().also { resExclude = it }).add(rv)
        }
        resExclude?.let { ex ->
            // Reason: idx's surviving domain (which positions remain) plus those positions'
            // element domains — hole-aware, since a value's support is exactly its membership in
            // one of the reachable element domains.
            val arrVars = IntArray(positions.size) { arr[positions[it]] }
            val ant = collectHoleAndBoundAntecedents(state, intArrayOf(idx) + arrVars)
            for (i in 0 until ex.size) if (!state.excludeIntValue(result, ex[i], ant)) return false
        }

        // 4. idx fixed → channel: result == arr(idx). The result-side prune is already done by
        //    step 3 (single surviving position), so only the selected element needs filtering —
        //    it loses every value not live in result.
        val d = state.intDomains[idx]
        if (d.min == d.max) {
            val pos = d.min - indexOffset
            if (pos in 0 until len) {
                val sel = arr[pos]
                val resD = state.intDomains[result]
                var selExclude: IntArrayList? = null
                state.intDomains[sel].forEach { v ->
                    if (v !in resD) (selExclude ?: IntArrayList().also { selExclude = it }).add(v)
                }
                selExclude?.let { ex ->
                    // The channeled removal depends on result's domain plus the index pin, so cite
                    // both — citing the pin alone would record the prune as holding for any source.
                    val ant = collectHoleAndBoundAntecedents(state, intArrayOf(idx, result))
                    for (i in 0 until ex.size) if (!state.excludeIntValue(sel, ex[i], ant)) return false
                }
            }
        }
        return true
    }

    /** Whether [a] and [b] share at least one live value (hole-aware). Disjoint ranges short-
     *  circuit; otherwise the smaller domain is scanned for membership in the larger. */
    private fun domainsIntersect(a: IntDomain, b: IntDomain): Boolean {
        if (a.max < b.min || b.max < a.min) return false
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        var found = false
        small.forEach { v -> if (v in large) found = true }
        return found
    }
}
