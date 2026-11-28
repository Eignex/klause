package com.eignex.klause.factor.global.internals

import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.MutableIntObjectMap

/*
 * Reversible matching GAC for plain alldifferent. ReginIncrementalState keeps the maximum
 * matching and canonical SCC labels on the undo trail; forward fires patch SCC components
 * touched by domain removals, and backtracking restores them in O(changes).
 *
 * Variable i is node i; value-id j is node n + j in the cache's grow-only value universe.
 * Each SCC is labelled by its minimum node id, avoiding reversible writes to unchanged components.
 */

// Same contract as reginFilter: null at the GAC fixpoint, else the Hall-violator variable ids.
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

// On the first fire, after a backtrack that invalidated the state, and whenever a matched edge breaks.
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

    val matchingConflict = rebuildReginMatching(state, vars, cache, inc, valuesPerVar, n, cap)
    if (matchingConflict != null) return matchingConflict

    val conflict = reginSccReachPrune(state, vars, cache, inc, valuesPerVar, dirtyLabels = null, premises = premises)
    if (conflict != null) return conflict
    inc.valid.set(1)
    for (i in 0 until n) inc.domRef[i].set(state.intDomains[vars[i]])
    cache.recordFixpoint(state, vars)
    return null
}

@Suppress("LongParameterList")
private fun rebuildReginMatching(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    inc: ReginIncrementalState,
    valuesPerVar: Array<IntArray>,
    n: Int,
    cap: Int,
): IntArray? {
    val matchVar = IntArray(n) { -1 }
    val matchVal = IntArray(cap) { -1 }
    for (i in 0 until n) {
        val j = inc.matchVar[i]
        if (j in 0 until cap && matchVal[j] == -1 && cache.valueOfId[j] in state.intDomains[vars[i]]) {
            matchVar[i] = j
            matchVal[j] = i
        }
    }
    // One Hopcroft-Karp pass (O(|E|·√n)) instead of per-variable augmenting searches, which dominate a cold
    // build at the root bake. On a non-perfect matching, one augmenting search from a free variable recovers
    // the Hall violator in its visited set.
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
    return null
}

// With the matching intact, patches only the SCC components touched by values that left since the last
// fire; rebuilds when a matched edge broke or a domain widened.
private fun reginDelta(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    inc: ReginIncrementalState,
    premises: IntArray,
): IntArray? {
    val n = inc.n
    // Only deleting an intra-component edge can split an SCC; cross-component edges carry no cycle.
    val dirtyLabels = IntHashSet()
    for (i in 0 until n) {
        val prev = inc.domRef[i].value ?: return reginRebuild(state, vars, cache, inc, premises)
        val cur = state.intDomains[vars[i]]
        if (cur === prev) continue
        var widened = false
        cur.values.forEach { v -> if (v !in prev) widened = true }
        if (widened) return reginRebuild(state, vars, cache, inc, premises)
        val matchedId = inc.matchVar[i]
        var brokeMatch = false
        prev.values.forEach { v ->
            if (v !in cur) {
                val vid = cache.idFor(v)
                if (vid == matchedId) {
                    brokeMatch = true
                } else if (inc.sccId[i] == inc.sccId[n + vid]) {
                    dirtyLabels.add(inc.sccId[i])
                }
            }
        }
        if (brokeMatch) return reginRebuild(state, vars, cache, inc, premises)
    }
    val valuesPerVar = buildValuesPerVar(state, vars, cache, n)
    val conflict = reginSccReachPrune(state, vars, cache, inc, valuesPerVar, dirtyLabels, premises)
    if (conflict != null) return conflict
    for (i in 0 until n) inc.domRef[i].set(state.intDomains[vars[i]])
    cache.recordFixpoint(state, vars)
    return null
}

private fun buildValuesPerVar(state: PropagationState, vars: IntArray, cache: ReginCache, n: Int): Array<IntArray> =
    Array(n) { i ->
        val list = IntArrayList()
        state.intDomains[vars[i]].values.forEach { v -> list.add(cache.idFor(v)) }
        list.toIntArray()
    }

// Orients the graph (matched value→var, unmatched var→value) and prunes unmatched edges with no support.
// Null dirtyLabels relabels every SCC; otherwise only the dirty components are re-decomposed, since a
// deletion can only split its own component. Canonical min-node labels make both paths agree.
private fun reginSccReachPrune(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    inc: ReginIncrementalState,
    valuesPerVar: Array<IntArray>,
    dirtyLabels: IntHashSet?,
    premises: IntArray,
): IntArray? {
    val total = inc.total
    val (adj, radj) = cache.graphBuffers(total)
    orientMatchedGraph(inc, valuesPerVar, adj, radj)
    val reached = reachedFromFreeValues(inc, radj)
    relabelScc(adj, inc, dirtyLabels)
    return pruneUnsupportedEdges(state, vars, cache, inc, valuesPerVar, adj, reached, premises)
}

// The passes are separate methods so each compiles apart: one body holding every loop is recompiled whole for each
// loop it enters on-stack.
private fun orientMatchedGraph(
    inc: ReginIncrementalState,
    valuesPerVar: Array<IntArray>,
    adj: Array<IntArrayList>,
    radj: Array<IntArrayList>,
) {
    val n = inc.n
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
}

// Reachability from free (unmatched) values over the reverse graph.
private fun reachedFromFreeValues(inc: ReginIncrementalState, radj: Array<IntArrayList>): BooleanArray {
    val n = inc.n
    val total = inc.total
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
    return reached
}

// Canonical min-node labels are written reversibly, a no-op for unchanged components. An empty dirtyLabels
// means no intra-component deletion, so every label is still valid.
private fun relabelScc(adj: Array<IntArrayList>, inc: ReginIncrementalState, dirtyLabels: IntHashSet?) {
    val total = inc.total
    if (dirtyLabels == null) {
        val raw = reginTarjanScc(adj, total)
        val canon = IntArray(total) { Int.MAX_VALUE }
        for (node in 0 until total) if (node < canon[raw[node]]) canon[raw[node]] = node
        for (node in 0 until total) inc.sccId[node] = canon[raw[node]]
    } else if (!dirtyLabels.isEmpty()) {
        reginRecomputeDirtyScc(adj, total, inc, dirtyLabels)
    }
}

// Prune unsupported unmatched edges: different SCC and not reachable from a free value.
@Suppress("LongParameterList")
private fun pruneUnsupportedEdges(
    state: PropagationState,
    vars: IntArray,
    cache: ReginCache,
    inc: ReginIncrementalState,
    valuesPerVar: Array<IntArray>,
    adj: Array<IntArrayList>,
    reached: BooleanArray,
    premises: IntArray,
): IntArray? {
    val n = inc.n
    val sccHallVars = MutableIntObjectMap<IntArray>()
    val hallReasons = HashMap<Int, IntArray?>()
    for (i in 0 until n) {
        for (vid in valuesPerVar[i]) {
            if (inc.matchVar[i] == vid) continue
            val vNode = n + vid
            if (inc.sccId[i] == inc.sccId[vNode]) continue
            if (reached[vNode]) continue
            val hall = sccHallVars.getOrPut(inc.sccId[vNode]) { reginHallVarsFrom(vNode, adj, vars) }
            val ant = hallReasons.getOrPut(inc.sccId[vNode]) { hallReason(state, hall, premises) }
            if (!state.excludeIntValue(vars[i], cache.valueOfId[vid], ant)) {
                val withI = hall.copyOf(hall.size + 1)
                withI[hall.size] = vars[i]
                return withI
            }
        }
    }
    return null
}

// Keeps only edges between endpoints with the same previous label, so distinct dirty components never merge.
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
            if (inc.sccId[w] == inc.sccId[u]) sub[c].add(compact[w])
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
