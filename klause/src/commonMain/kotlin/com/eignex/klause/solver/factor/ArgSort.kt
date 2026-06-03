package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `arg_sort(values, perm, permOffset=0)` — [perm] is a permutation of
 * `[permOffset, permOffset+n-1]` such that the sequence `values[perm[i] − permOffset]`
 * is non-decreasing, with ties broken by smaller index.
 *
 * Propagation:
 *  - Bound-check that [perm] entries lie in `[permOffset, permOffset+n-1]`.
 *  - Pairwise NE on [perm] (all-different).
 *  - When all [perm] entries are pinned, verify sorted-ness directly and propagate
 *    bound-tightenings from [values] back to the ordering implied by perm.
 */
class ArgSort(
    /** The values being ranked. */
    val values: IntArray,
    /** Output permutation variable ids that sort [values] ascending. */
    val perm: IntArray,
    /** Integer representing index 0 in [perm]. */
    val permOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(values.size == perm.size) { "ArgSort: values and perm length mismatch" }
        require(values.isNotEmpty()) { "ArgSort: empty arrays" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = values + perm

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val n = perm.size
        val permVals = IntArray(n) { state.assignment.intValue(perm[it]) - permOffset }
        // perm permutation of [0, n-1].
        val seen = BooleanArray(n)
        for (p in permVals) {
            if (p < 0 || p >= n) return true
            if (seen[p]) return true
            seen[p] = true
        }
        // Values at perm[i] non-decreasing with tie-break by perm index.
        for (i in 0 until n - 1) {
            val a = state.assignment.intValue(values[permVals[i]])
            val b = state.assignment.intValue(values[permVals[i + 1]])
            if (a > b) return true
            if (a == b && permVals[i] >= permVals[i + 1]) return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val was = isViolated(state, factorId)
        val n = perm.size
        val permVals = IntArray(n) { i ->
            val v = if (perm[i] == intVar) newValue else state.assignment.intValue(perm[i])
            v - permOffset
        }
        val vals = IntArray(n) { i ->
            if (values[i] == intVar) newValue else state.assignment.intValue(values[i])
        }
        var will = false
        val seen = BooleanArray(n)
        for (p in permVals) {
            if (p < 0 || p >= n) {
                will = true
                break
            }
            if (seen[p]) {
                will = true
                break
            }
            seen[p] = true
        }
        if (!will) {
            for (i in 0 until n - 1) {
                val a = vals[permVals[i]]
                val b = vals[permVals[i + 1]]
                if (a > b) {
                    will = true
                    break
                }
                if (a == b && permVals[i] >= permVals[i + 1]) {
                    will = true
                    break
                }
            }
        }
        return (if (will) 1 else 0) - (if (was) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair: snap `perm` to the true argsort of `values`. Computing the correct
     *  permutation gives strategies a one-shot fix for an entire violated argsort. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val n = perm.size
        val indices = IntArray(n) { it }
        val valuesNow = IntArray(n) { state.assignment.intValue(values[it]) }
        // Stable sort indices by valuesNow with tie-break by index (matches the constraint).
        val sortedIdx = indices.toTypedArray().also {
            it.sortWith(
                Comparator { a, b ->
                    val c = valuesNow[a].compareTo(valuesNow[b])
                    if (c != 0) c else a.compareTo(b)
                },
            )
        }
        for (i in 0 until n) {
            val target = sortedIdx[i] + permOffset
            val cur = state.assignment.intValue(perm[i])
            if (target != cur && target in state.problem.intDomains[perm[i]]) {
                sink.addChannelingIntSet(state, perm[i], target)
            }
        }
        // Symmetric: at each ordering inversion (a, b) with values[a] > values[b] but a
        // comes before b in perm, propose flattening one side to the other so the inversion
        // disappears without changing the permutation. Required for LS to escape states
        // where the right repair is in the values, not the perm.
        val permVals = IntArray(n) { state.assignment.intValue(perm[it]) - permOffset }
        for (i in 0 until n - 1) {
            val a = permVals[i]
            val b = permVals[i + 1]
            if (a !in 0 until n || b !in 0 until n) continue
            if (valuesNow[a] > valuesNow[b]) {
                val aDom = state.problem.intDomains[values[a]]
                val bDom = state.problem.intDomains[values[b]]
                // Match: collapse to either current value (handles simple inversions).
                if (valuesNow[b] in aDom && valuesNow[b] != valuesNow[a]) {
                    sink.addChannelingIntSet(state, values[a], valuesNow[b])
                }
                if (valuesNow[a] in bDom && valuesNow[a] != valuesNow[b]) {
                    sink.addChannelingIntSet(state, values[b], valuesNow[a])
                }
                // Bound spread: when the inversion sits on a tie-broken edge of the constraint
                // (sorted but indices-out-of-order at equal values), neither "match" move
                // suffices — pulling one side to its domain extremum breaks the tie.
                if (aDom.min != valuesNow[a]) sink.addChannelingIntSet(state, values[a], aDom.min)
                if (bDom.max != valuesNow[b]) sink.addChannelingIntSet(state, values[b], bDom.max)
            }
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    /** Cached domain refs at the last successful propagate. When no participating domain's
     *  reference changed, the previous fixpoint still holds and the (matching-based) sweep is
     *  skipped — the rebuild is the expensive part. Backtrack-safe via [snapshotCopy]. */
    private class ArgSortState(val cached: Array<IntDomain?>) : PropagationState.SnapshottablePayload {
        override fun snapshotCopy(): ArgSortState = ArgSortState(cached.copyOf())
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = perm.size
        // Incremental fast path (cf. Mdd): skip the whole sweep — including the Régin rebuild
        // below — when nothing changed since the last fire.
        val payload = (state.refPayload[factorId] as? ArgSortState) ?: run {
            val fresh = ArgSortState(arrayOfNulls(intVars.size))
            state.refPayload[factorId] = fresh
            fresh
        }
        var changed = payload.cached[0] == null
        if (!changed) {
            for (k in intVars.indices) {
                if (payload.cached[k] !== state.intDomains[intVars[k]]) {
                    changed = true
                    break
                }
            }
        }
        if (!changed) return true
        val ant = state.composeIntVarAtomAntecedents(intVars)
        // perm entries in range.
        for (p in perm) {
            if (!state.tightenIntMin(p, permOffset, ant)) return false
            if (!state.tightenIntMax(p, permOffset + n - 1, ant)) return false
        }
        // Singleton-take filter on perm (cheap fast-path).
        val taken = HashSet<Int>()
        for (p in perm) {
            val d = state.intDomains[p]
            if (d.min != d.max) continue
            if (!taken.add(d.min)) return false
        }
        if (taken.isNotEmpty()) {
            for (p in perm) {
                val d = state.intDomains[p]
                if (d.min == d.max) continue
                for (t in taken) {
                    if (t < d.min || t > d.max) continue
                    if (!state.excludeIntValue(p, t, ant)) return false
                }
            }
        }

        // Régin's matching-based filtering on perm (domain-consistent all-different).
        // Guard: only fire when some perm var has at least 2 values in its domain (else
        // singleton-take above already did the work).
        var anyMultiValue = false
        for (p in perm) {
            val d = state.intDomains[p]
            if (d.max > d.min) {
                anyMultiValue = true
                break
            }
        }
        if (anyMultiValue && !reginOnPerm(state, ant)) return false

        // Pin-based sorted check (only fires when perm is fully assigned).
        var allPinned = true
        for (p in perm) {
            if (state.intDomains[p].min != state.intDomains[p].max) {
                allPinned = false
                break
            }
        }
        if (allPinned) {
            val pv = IntArray(n) { state.intDomains[perm[it]].min - permOffset }
            for (i in 0 until n - 1) {
                val a = pv[i]
                val b = pv[i + 1]
                if (!state.tightenIntMax(values[a], state.intDomains[values[b]].max, ant)) return false
                if (!state.tightenIntMin(values[b], state.intDomains[values[a]].min, ant)) return false
                if (a >= b) {
                    if (!state.tightenIntMax(values[a], state.intDomains[values[b]].max - 1, ant)) return false
                    if (!state.tightenIntMin(values[b], state.intDomains[values[a]].min + 1, ant)) return false
                }
            }
        }
        // Record post-prune refs so the next fire can detect a true fixpoint.
        for (k in intVars.indices) payload.cached[k] = state.intDomains[intVars[k]]
        return true
    }

    /** Régin's matching + SCC pruning on `perm` (treated as an all-different over
     *  `[permOffset, permOffset+n-1]`). Returns false on infeasibility. */
    private fun reginOnPerm(state: PropagationState, ant: IntArray?): Boolean {
        val n = perm.size
        val numVals = n // values are exactly [permOffset, permOffset+n-1]

        val varAdj = Array(n) { i ->
            val d = state.intDomains[perm[i]]
            val out = ArrayList<Int>()
            // Walk the actual in-domain values (respects holes).
            d.forEach { v ->
                if (v in permOffset..(permOffset + n - 1)) out += v - permOffset
            }
            out.toIntArray()
        }

        // Maximum matching via augmenting paths.
        val matchVarToVal = IntArray(n) { -1 }
        val matchValToVar = IntArray(numVals) { -1 }
        for (i in 0 until n) {
            val visited = BooleanArray(numVals)
            if (!augmentReg(i, varAdj, matchVarToVal, matchValToVar, visited)) return false
        }

        // Build oriented residual graph: matched (val→var), unmatched (var→val).
        val totalV = n + numVals
        val adj = Array(totalV) { ArrayList<Int>() }
        for (i in 0 until n) {
            for (vi in varAdj[i]) {
                if (matchVarToVal[i] == vi) {
                    adj[n + vi].add(i)
                } else {
                    adj[i].add(n + vi)
                }
            }
        }
        // No free vertices (matching is perfect); SCC alone determines vital edges.
        val sccId = IntArray(totalV) { -1 }
        run {
            val index = IntArray(totalV) { -1 }
            val low = IntArray(totalV)
            val onStack = BooleanArray(totalV)
            val stack = ArrayDeque<Int>()
            var idx = 0
            var sccCount = 0
            val callStack = ArrayDeque<IntArray>()
            for (root in 0 until totalV) {
                if (index[root] != -1) continue
                callStack.addLast(intArrayOf(root, 0))
                index[root] = idx
                low[root] = idx
                idx++
                stack.addLast(root)
                onStack[root] = true
                while (callStack.isNotEmpty()) {
                    val top = callStack.last()
                    val u = top[0]
                    val edges = adj[u]
                    if (top[1] < edges.size) {
                        val w = edges[top[1]]
                        top[1]++
                        if (index[w] == -1) {
                            index[w] = idx
                            low[w] = idx
                            idx++
                            stack.addLast(w)
                            onStack[w] = true
                            callStack.addLast(intArrayOf(w, 0))
                        } else if (onStack[w]) {
                            if (index[w] < low[u]) low[u] = index[w]
                        }
                    } else {
                        if (low[u] == index[u]) {
                            while (true) {
                                val w = stack.removeLast()
                                onStack[w] = false
                                sccId[w] = sccCount
                                if (w == u) break
                            }
                            sccCount++
                        }
                        callStack.removeLast()
                        if (callStack.isNotEmpty()) {
                            val parent = callStack.last()[0]
                            if (low[u] < low[parent]) low[parent] = low[u]
                        }
                    }
                }
            }
        }
        // Remove non-matching edges that cross SCC boundaries.
        for (i in 0 until n) {
            val d = state.intDomains[perm[i]]
            for (vi in varAdj[i]) {
                if (matchVarToVal[i] == vi) continue
                if (sccId[i] == sccId[n + vi]) continue
                val value = vi + permOffset
                if (value in d.min..d.max) {
                    if (!state.excludeIntValue(perm[i], value, ant)) return false
                }
            }
        }
        return true
    }

    private fun augmentReg(
        i: Int,
        varAdj: Array<IntArray>,
        matchVarToVal: IntArray,
        matchValToVar: IntArray,
        visited: BooleanArray,
    ): Boolean {
        for (vi in varAdj[i]) {
            if (visited[vi]) continue
            visited[vi] = true
            val w = matchValToVar[vi]
            if (w == -1 || augmentReg(w, varAdj, matchVarToVal, matchValToVar, visited)) {
                matchVarToVal[i] = vi
                matchValToVar[vi] = i
                return true
            }
        }
        return false
    }
}
