package com.eignex.klause.factor.global.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.ir.values
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

/*
 * Reversible, delta-driven matching GAC for plain alldifferent (no excepted values) — the incremental
 * counterpart to the full per-fire rebuild in reginFilter. State lives in ReginIncrementalState
 * (declared with ReginCache in ReginMatcher.kt): the maximum matching and the canonical SCC labels
 * ride the engine undo trail, so a backtrack restores them in O(changes) rather than triggering a
 * rebuild, and the next forward fire patches only the SCC components touched by the values that
 * left since the previous fire. Node space is stable across fires (variable i is node i; value-id
 * j is node n + j, ids from the cache's grow-only value universe), and SCC labels are canonical
 * (each component labelled by its minimum node id) so unchanged components cost no reversible write.
 *
 * Soundness is gated by enumerate-vs-brute across deep backtracking plus witness-checked Hall-set
 * conflict reasons (ReginIncrementalTest, AllFactorsOracle, AllDifferentTest, ReginGacTest).
 */

/** Incremental matching GAC for [vars] (plain alldifferent). Returns `null` after pruning to the GAC
 *  fixpoint, or the Hall-violator variable ids on infeasibility — same contract as [reginFilter]. */
internal fun reginIncremental(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    premises: IntArray,
): IntArray? {
    val inc = cache.incremental(state, vars)
    if (inc.valid.value == 0) return reginRebuild(state, vars, cache, inc, premises)
    return reginDelta(state, vars, cache, inc, premises)
}

/** Full rebuild: re-seed the matching (warm-started from the reversible matching), recompute SCC
 *  labels, reachability and prune. Used on the first fire, after a backtrack that invalidated the
 *  state, and whenever a matched edge breaks (the matching must be re-augmented). */
private fun reginRebuild(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    inc: ReginIncrementalState,
    premises: IntArray,
): IntArray? {
    val n = inc.n
    val cap = inc.valueCap
    val valuesPerVar = buildValuesPerVar(state, vars, cache, n)

    // Pigeonhole over distinct current values.
    val present = BooleanArray(cap)
    var distinct = 0
    for (i in 0 until n) {
        for (j in valuesPerVar[i]) {
            if (!present[j]) {
                present[j] = true
                distinct++
            }
        }
    }
    if (distinct < n) return vars.copyOf()

    // Warm-start the matching from the reversible matching, then augment the rest.
    val matchVar = IntArray(n) { -1 }
    val matchVal = IntArray(cap) { -1 }
    for (i in 0 until n) {
        val j = inc.matchVar[i]
        if (j in 0 until cap && matchVal[j] == -1 && cache.valueOfId[j] in state.intDomains[vars[i]]) {
            matchVar[i] = j
            matchVal[j] = i
        }
    }
    // Complete the seed to a maximum matching in one Hopcroft-Karp pass (O(|E|·√n)) rather than a
    // per-variable augmenting search — the dominant cost when the matching is built cold at the root
    // bake with no warm start. On a non-perfect matching, one augmenting search on a still-free variable
    // recovers the exact Hall violator (its `visited` set), unchanged from the per-variable path.
    if (!reginHopcroftKarp(n, valuesPerVar, matchVar, matchVal, IntArray(n), IntArray(n))) {
        val visited = BooleanArray(cap)
        for (i in 0 until n) {
            if (matchVar[i] != -1) continue
            reginTryAugment(i, valuesPerVar, matchVar, matchVal, visited)
            val hall = IntArrayList()
            hall.add(vars[i])
            for (j in 0 until cap) if (visited[j] && matchVal[j] >= 0) hall.add(vars[matchVal[j]])
            return hall.toIntArray()
        }
    }
    for (i in 0 until n) inc.matchVar[i] = matchVar[i]
    for (j in 0 until cap) inc.matchVal[j] = matchVal[j]

    val conflict = reginSccReachPrune(state, vars, cache, inc, valuesPerVar, dirtyLabels = null, premises = premises)
    if (conflict != null) return conflict
    inc.valid.set(1)
    for (i in 0 until n) inc.domRef[i].set(state.intDomains[vars[i]])
    cache.recordFixpoint(state, vars)
    return null
}

/** Delta fire: with the matching intact, patch only the SCC components touched by the values that
 *  left since the last fire, then re-prune. Falls back to [reginRebuild] if a matched edge broke
 *  (re-augmentation needed) or the deletions-only invariant is violated. */
private fun reginDelta(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    inc: ReginIncrementalState,
    premises: IntArray,
): IntArray? {
    val n = inc.n
    // Dirty SCC labels: an SCC can split only when an *intra-component* edge is deleted. Deleting a
    // cross-component edge (which carries no cycle) leaves every SCC intact, so it is ignored here.
    val dirtyLabels = IntHashSet()
    for (i in 0 until n) {
        val prev = inc.domRef[i].value ?: return reginRebuild(state, vars, cache, inc, premises)
        val cur = state.intDomains[vars[i]]
        if (cur === prev) continue
        var widened = false
        cur.values.forEach { v -> if (v !in prev) widened = true }
        if (widened) return reginRebuild(state, vars, cache, inc, premises) // not deletions-only → rebuild
        val matchedId = inc.matchVar[i]
        // Values that left var i since the last fire (deletions only — widening ruled out above).
        var brokeMatch = false
        prev.values.forEach { v ->
            if (v !in cur) {
                val vid = cache.idFor(v)
                if (vid == matchedId) {
                    brokeMatch = true // matched edge broke → re-augment
                } else if (inc.sccId[i] == inc.sccId[n + vid]) {
                    dirtyLabels.add(inc.sccId[i])
                }
            }
        }
        if (brokeMatch) return reginRebuild(state, vars, cache, inc, premises)
    }
    // Matching still maximum (no matched edge lost): patch the dirty components and re-prune.
    val valuesPerVar = buildValuesPerVar(state, vars, cache, n)
    val conflict = reginSccReachPrune(state, vars, cache, inc, valuesPerVar, dirtyLabels, premises)
    if (conflict != null) return conflict
    for (i in 0 until n) inc.domRef[i].set(state.intDomains[vars[i]])
    cache.recordFixpoint(state, vars)
    return null
}

/** Value-id list per variable over the current domains (stable ids from the universe). */
private fun buildValuesPerVar(state: PropagationState, vars: IntArray, cache: ReginCache, n: Int): Array<IntArray> =
    Array(n) { i ->
        val list = IntArrayList()
        state.intDomains[vars[i]].values.forEach { v -> list.add(cache.idFor(v)) }
        list.toIntArray()
    }

/** Build the oriented graph (matched value→var, unmatched var→value), recompute free-value
 *  reachability and SCC labels into [ReginIncrementalState.sccId], then prune every unmatched
 *  var→value edge with no support. Returns `null` or the Hall-violator ids on a domain wipeout.
 *  The matching is read from [inc] (already current).
 *
 *  [dirtyLabels] = `null` recomputes every SCC label (the rebuild path). A non-null set recomputes
 *  only the components with those labels (the delta path): deleting an intra-component edge can
 *  only *split* that component, never merge or alter any other, so the partition restricted to a
 *  dirty old component is recovered by re-running the SCC pass on that component's nodes alone (edges kept
 *  only between same-old-label endpoints). Canonical min-node labels make the two paths agree. */
private fun reginSccReachPrune(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    inc: ReginIncrementalState,
    valuesPerVar: Array<IntArray>,
    dirtyLabels: IntHashSet?,
    premises: IntArray,
): IntArray? {
    val n = inc.n
    val total = inc.total
    val (adj, radj) = cache.graphBuffers(total)
    for (i in 0 until n) {
        for (vid in valuesPerVar[i]) {
            val vNode = n + vid
            if (inc.matchVar[i] == vid) {
                adj[vNode].add(i)
                radj[i].add(vNode)
            } else {
                adj[i].add(vNode)
                radj[vNode].add(i)
            }
        }
    }

    // Reachability from free (unmatched) values over the reverse graph.
    val reached = BooleanArray(total)
    val queue = IntArray(total)
    var qHead = 0
    var qTail = 0
    for (vid in 0 until inc.valueCap) {
        if (inc.matchVal[vid] == -1) {
            reached[n + vid] = true
            queue[qTail++] = n + vid
        }
    }
    while (qHead < qTail) {
        val u = queue[qHead++]
        val r = radj[u]
        for (k in 0 until r.size) {
            val w = r[k]
            if (!reached[w]) {
                reached[w] = true
                queue[qTail++] = w
            }
        }
    }

    // SCC labels — canonical (min node id per component), written reversibly (no-op for unchanged).
    if (dirtyLabels == null) {
        val raw = reginTarjanScc(adj, total)
        val canon = IntArray(total) { Int.MAX_VALUE }
        for (node in 0 until total) if (node < canon[raw[node]]) canon[raw[node]] = node
        for (node in 0 until total) inc.sccId[node] = canon[raw[node]]
    } else if (!dirtyLabels.isEmpty()) {
        reginRecomputeDirtyScc(adj, total, inc, dirtyLabels)
    }
    // dirtyLabels non-null and empty → no intra-component deletion → every SCC label still valid.

    // Prune unsupported unmatched edges: different SCC and not reachable from a free value.
    val sccHallVars = MutableIntObjectMap<IntArray>()
    for (i in 0 until n) {
        for (vid in valuesPerVar[i]) {
            if (inc.matchVar[i] == vid) continue
            val vNode = n + vid
            if (inc.sccId[i] == inc.sccId[vNode]) continue
            if (reached[vNode]) continue
            val hall = sccHallVars.getOrPut(inc.sccId[vNode]) { hallVarsFrom(vNode, adj, n, vars) }
            val ant = antecedentsWithPremises(state, hall, premises)
            if (!state.excludeIntValue(vars[i], cache.valueOfId[vid], ant)) {
                val withI = hall.copyOf(hall.size + 1)
                withI[hall.size] = vars[i]
                return withI
            }
        }
    }
    return null
}

/** Recompute SCC labels only for nodes whose current (old) label is in [dirtyLabels]. Each such
 *  component is re-decomposed on its own nodes, keeping only edges between same-old-label endpoints
 *  (so distinct dirty components are not merged), and the resulting sub-components are canonically
 *  relabelled by their minimum global node id — matching what a full canonical SCC pass would yield,
 *  since deletion only splits components. */
private fun reginRecomputeDirtyScc(
    adj: Array<IntArrayList>,
    total: Int,
    inc: ReginIncrementalState,
    dirtyLabels: IntHashSet,
) {
    val compact = IntArray(total) { -1 }
    val dirtyNodes = IntArrayList()
    for (node in 0 until total) {
        if (dirtyLabels.contains(inc.sccId[node])) {
            compact[node] = dirtyNodes.size
            dirtyNodes.add(node)
        }
    }
    val d = dirtyNodes.size
    val sub = Array(d) { IntArrayList() }
    for (c in 0 until d) {
        val u = dirtyNodes[c]
        val a = adj[u]
        for (k in 0 until a.size) {
            val w = a[k]
            if (inc.sccId[w] == inc.sccId[u]) sub[c].add(compact[w]) // same old component only
        }
    }
    val rawSub = reginTarjanScc(sub, d)
    val canonSub = IntArray(d) { Int.MAX_VALUE }
    for (c in 0 until d) {
        val r = rawSub[c]
        if (dirtyNodes[c] < canonSub[r]) canonSub[r] = dirtyNodes[c]
    }
    for (c in 0 until d) inc.sccId[dirtyNodes[c]] = canonSub[rawSub[c]]
}

/** Variable ids forward-reachable from a value node [valNode] over [adj] — the sharp Hall set
 *  behind pruning that value's edges (matches [reginFilter]'s `hallVarsFor`). */
private fun hallVarsFrom(valNode: Int, adj: Array<IntArrayList>, n: Int, vars: IntArray): IntArray {
    val total = adj.size
    val vis = BooleanArray(total)
    val bfs = IntArray(total)
    var qh = 0
    var qt = 0
    vis[valNode] = true
    bfs[qt++] = valNode
    val acc = IntArrayList()
    while (qh < qt) {
        val u = bfs[qh++]
        if (u < n) acc.add(vars[u])
        val a = adj[u]
        for (k in 0 until a.size) {
            val w = a[k]
            if (!vis[w]) {
                vis[w] = true
                bfs[qt++] = w
            }
        }
    }
    return acc.toIntArray()
}
