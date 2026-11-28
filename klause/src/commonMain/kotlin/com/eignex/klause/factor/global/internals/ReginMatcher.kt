package com.eignex.klause.factor.global.internals

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.propagation.RevRef
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.collections.MutableIntLongMap
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.collections.MutableLongIntMap

/**
 * Domain-consistency filtering for the alldifferent family (`AllDifferent` and both permutations of
 * `Inverse`). Returns null after pruning, or the Hall-violator variable ids on infeasibility.
 *
 * Régin, "A filtering algorithm for constraints of difference in CSPs", AAAI 1994,
 * https://mlanthology.org/aaai/1994/regin1994aaai-filtering
 *
 * Reachability walks the reverse graph from free values: a free value is a sink in the forward orientation,
 * so a forward walk would reach nothing and wrongly prune every slack edge.
 *
 * Each value in [exceptSet] becomes `n` capacity-1 copies, turning `alldifferent_except` into a pure
 * alldifferent over the expanded universe; excepted values are never pruned and the copies keep the
 * pigeonhole shortcut from misfiring.
 *
 * Antecedents come from [hallReason] over the sharp Hall set, citing bounds and holes as
 * [collectHoleAndBoundAntecedents] does, so learned clauses stay sound under holes.
 */
internal fun reginFilter(
    state: PropagationState,
    filteredVars: IntArray,
    exceptSet: LongHashSet,
    cache: ReginCache? = null,
    premises: IntArray = emptyIntArray(),
    tag: Int = 0,
): IntArray? {
    val n = filteredVars.size
    if (n < 2) return null

    // A domain too large to walk would make the value graph enumerate its span. Excepted values may repeat,
    // so that variant falls back to fixed non-excepted values rather than Hall intervals.
    if (filteredVars.any { state.intDomains[it].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null }) {
        return if (exceptSet.isEmpty()) boundsAllDifferentFilter(state, filteredVars, premises, tag)
        else filterFixedDistinctness(state, filteredVars, exceptSet, premises)
    }

    // The previous fixpoint still holds when no domain changed. The cached refs only miss after a backtrack
    // restores a different IntDomain, never falsely match, so they need no snapshot.
    if (cache != null && cache.fixpointHolds(state, filteredVars)) return null

    if (cache != null && exceptSet.isEmpty()) return reginIncremental(state, filteredVars, cache, premises)

    return reginFallbackFilter(state, filteredVars, exceptSet, cache, premises, n)
}

@Suppress("LongParameterList")
private fun reginFallbackFilter(
    state: PropagationState,
    filteredVars: IntArray,
    exceptSet: LongHashSet,
    cache: ReginCache?,
    premises: IntArray,
    n: Int,
): IntArray? {
    // Each except value gets n contiguous capacity-1 copy ids so up to n variables share it.
    val valueId = MutableLongIntMap()
    val exceptBase = MutableLongIntMap() // except value → first of its n copy ids
    val idToValue = LongArrayList()
    val valuesPerVar = Array(n) { i ->
        val d = state.intDomains[filteredVars[i]]
        val list = IntArrayList()
        d.values.forEach { v ->
            if (v in exceptSet) {
                var base = exceptBase.getOrDefault(v, -1)
                if (base < 0) {
                    base = idToValue.size
                    repeat(n) { idToValue.add(v) }
                    exceptBase.put(v, base)
                }
                for (c in 0 until n) list.add(base + c)
            } else {
                var id = valueId.getOrDefault(v, -1)
                if (id < 0) {
                    idToValue.add(v)
                    id = idToValue.size - 1
                    valueId.put(v, id)
                }
                list.add(id)
            }
        }
        list.toIntArray()
    }
    val numValues = idToValue.size
    if (numValues < n) return filteredVars

    val matchVar = IntArray(n) { -1 }
    val matchVal = IntArray(numValues) { -1 }
    val visited = BooleanArray(numValues)
    // Warm start from the previous matching's surviving edges: pruning is matching-independent, so this
    // changes speed, not results.
    if (cache != null) {
        for (i in 0 until n) {
            if (!cache.matchedValue.containsKey(filteredVars[i])) continue
            val prev = cache.matchedValue.getOrDefault(filteredVars[i], 0L)
            if (prev !in state.intDomains[filteredVars[i]]) continue
            if (prev in exceptSet) {
                val base = exceptBase.getOrDefault(prev, -1)
                if (base < 0) continue
                for (c in 0 until n) {
                    if (matchVal[base + c] == -1) {
                        matchVar[i] = base + c
                        matchVal[base + c] = i
                        break
                    }
                }
            } else {
                val id = valueId.getOrDefault(prev, -1)
                if (id < 0) continue
                if (matchVal[id] == -1) {
                    matchVar[i] = id
                    matchVal[id] = i
                }
            }
        }
    }
    if (!reginHopcroftKarp(n, valuesPerVar, matchVar, matchVal, IntArray(n), IntArray(n))) {
        for (i in 0 until n) {
            if (matchVar[i] != -1) continue
            for (j in visited.indices) visited[j] = false
            reginTryAugment(i, valuesPerVar, matchVar, matchVal, visited)
            // The visited values' occupants and i form a Hall violator: their domains lie within those values.
            val hall = IntArrayList()
            hall.add(filteredVars[i])
            for (vid in 0 until numValues) {
                if (visited[vid] && matchVal[vid] >= 0) hall.add(filteredVars[matchVal[vid]])
            }
            return hall.toIntArray()
        }
    }

    // Matched value→var, unmatched var→value; variables are nodes 0 until n, values after.
    val total = n + numValues
    val (adj, radj) = cache?.graphBuffers(total)
        ?: (Array(total) { IntArrayList() } to Array(total) { IntArrayList() })
    for (i in 0 until n) {
        for (vid in valuesPerVar[i]) {
            if (matchVar[i] == vid) {
                adj[n + vid].add(i)
                radj[i].add(n + vid)
            } else {
                adj[i].add(n + vid)
                radj[n + vid].add(i)
            }
        }
    }

    val reachedFromFree = BooleanArray(total)
    val queue = IntArray(total)
    var qHead = 0
    var qTail = 0
    for (vid in 0 until numValues) {
        if (matchVal[vid] == -1) {
            reachedFromFree[n + vid] = true
            queue[qTail++] = n + vid
        }
    }
    while (qHead < qTail) {
        val u = queue[qHead++]
        val r = radj[u]
        for (k in 0 until r.size) {
            val w = r[k]
            if (!reachedFromFree[w]) {
                reachedFromFree[w] = true
                queue[qTail++] = w
            }
        }
    }

    val sccId = reginTarjanScc(adj, total)

    // An unmatched edge in a different SCC and unreachable from a free value is in no maximum matching.
    val sccHallVars = MutableIntObjectMap<IntArray>()
    val hallReasons = HashMap<Int, IntArray?>()
    fun hallVarsFor(valNode: Int): IntArray = sccHallVars.getOrPut(sccId[valNode]) {
        reginHallVarsFrom(valNode, adj, filteredVars)
    }
    for (i in 0 until n) {
        for (vid in valuesPerVar[i]) {
            if (matchVar[i] == vid) continue
            val value = idToValue[vid]
            if (value in exceptSet) continue
            val valNode = n + vid
            if (sccId[i] == sccId[valNode]) continue
            if (reachedFromFree[valNode]) continue
            val hall = hallVarsFor(valNode)
            val ant = hallReasons.getOrPut(sccId[valNode]) { hallReason(state, hall, premises) }
            if (!state.excludeIntValue(filteredVars[i], value, ant)) {
                val withI = hall.copyOf(hall.size + 1)
                withI[hall.size] = filteredVars[i]
                return withI
            }
        }
    }
    if (cache != null) {
        cache.matchedValue.clear()
        for (i in 0 until n) {
            if (matchVar[i] != -1) cache.matchedValue.put(filteredVars[i], idToValue[matchVar[i]])
        }
        cache.recordFixpoint(state, filteredVars)
    }
    return null
}

// Warm-start state per factor. The matching is only a seed that reginFilter revalidates and completes, so it
// drifts across backtracks without a snapshot; staleness costs augmenting searches, never correctness.
internal class ReginCache {
    // Variable id → matched value.
    val matchedValue = MutableIntLongMap()

    // The variables and domain refs at the last fixpoint; a stale entry only misses, never falsely matches.
    private var lastVars: IntArray? = null
    private var lastDoms: Array<IntDomain?> = emptyArray()

    fun fixpointHolds(state: PropagationState, vars: IntArray): Boolean {
        val lv = lastVars ?: return false
        if (!lv.contentEquals(vars)) return false
        for (i in vars.indices) if (lastDoms[i] !== state.intDomains[vars[i]]) return false
        return true
    }

    fun recordFixpoint(state: PropagationState, vars: IntArray) {
        if (lastDoms.size < vars.size) lastDoms = arrayOfNulls(vars.size)
        lastVars = vars.copyOf()
        for (i in vars.indices) lastDoms[i] = state.intDomains[vars[i]]
    }

    // A grow-only value universe gives values stable node ids across fires, which carrying reversible graph
    // state across fires requires.
    private val idOfValue = MutableLongIntMap()
    val valueOfId = LongArrayList()

    fun idFor(value: Long): Int {
        var id = idOfValue.getOrDefault(value, -1)
        if (id < 0) {
            id = valueOfId.size
            valueOfId.add(value)
            idOfValue.put(value, id)
        }
        return id
    }

    private var inc: ReginIncrementalState? = null

    // Recreated when the variable set changes or the universe outgrows its capacity; a fresh state starts
    // invalid, forcing a rebuild.
    fun incremental(state: PropagationState, vars: IntArray): ReginIncrementalState {
        for (vi in vars) state.intDomains[vi].values.forEach { v -> idFor(v) }
        val need = valueOfId.size
        val cur = inc
        if (cur == null || !cur.vars.contentEquals(vars) || cur.valueCap < need) {
            val cap = maxOf(need, cur?.valueCap ?: 0)
            return ReginIncrementalState(state, vars, cap).also { inc = it }
        }
        return cur
    }

    // Reused adjacency buffers: fresh lists were the dominant per-fire allocation. They hold no level state.
    private var adjBuf: Array<IntArrayList> = emptyArray()
    private var radjBuf: Array<IntArrayList> = emptyArray()

    fun graphBuffers(total: Int): Pair<Array<IntArrayList>, Array<IntArrayList>> {
        if (adjBuf.size < total) {
            val oldA = adjBuf
            val oldR = radjBuf
            adjBuf = Array(total) { if (it < oldA.size) oldA[it] else IntArrayList() }
            radjBuf = Array(total) { if (it < oldR.size) oldR[it] else IntArrayList() }
        }
        for (i in 0 until total) {
            adjBuf[i].clear()
            radjBuf[i].clear()
        }
        return adjBuf to radjBuf
    }

    // The Hall violator of the latest propagate failure, read right after by the analyzer. It lives in the
    // per-session cache, not the factor, so workers sharing one Problem never read another session's reason.
    var conflictVars: IntArray? = null
}

// Matching and SCC labels on the undo trail, so a backtrack restores them in O(changes). Variable i is
// node i, value-id j is node n + j.
internal class ReginIncrementalState(state: PropagationState, val vars: IntArray, val valueCap: Int) {
    val n = vars.size
    val total = n + valueCap

    // Drops to 0 after a backtrack above the seeding level, forcing a rebuild.
    val valid = RevInt(state, 0)

    // -1 when unmatched or free.
    val matchVar = RevIntArray(state, n, -1)
    val matchVal = RevIntArray(state, valueCap, -1)

    // Each component labelled by its minimum node id.
    val sccId = RevIntArray(state, total, -1)

    // Each variable's domain at its last fire, the delta base; current ⊆ domRef holds forward.
    val domRef = Array(n) { RevRef<IntDomain?>(state, null) }
}

// Completes a partial seed to a maximum matching in place, O(|E|·√n). True iff every variable is matched;
// on false the caller runs reginTryAugment from a free variable to recover the Hall violator.
// Hopcroft, Karp, "An n^5/2 algorithm for maximum matchings in bipartite graphs", SIAM J. Comput. 2(4),
// 1973, https://doi.org/10.1137/0202019
internal fun reginHopcroftKarp(
    n: Int,
    valuesPerVar: Array<IntArray>,
    matchVar: IntArray,
    matchVal: IntArray,
    distScratch: IntArray,
    queueScratch: IntArray,
): Boolean {
    val unmatchedLayer = Int.MAX_VALUE
    while (true) {
        // Layer variables by BFS distance from the free variables over alternating edges; a free
        // value reached in the sweep means an augmenting path of the current shortest length exists.
        var qHead = 0
        var qTail = 0
        for (i in 0 until n) {
            if (matchVar[i] == -1) {
                distScratch[i] = 0
                queueScratch[qTail++] = i
            } else {
                distScratch[i] = unmatchedLayer
            }
        }
        var augmentingPathExists = false
        while (qHead < qTail) {
            val u = queueScratch[qHead++]
            val vids = valuesPerVar[u]
            for (k in vids.indices) {
                val holder = matchVal[vids[k]]
                if (holder == -1) {
                    augmentingPathExists = true
                } else if (distScratch[holder] == unmatchedLayer) {
                    distScratch[holder] = distScratch[u] + 1
                    queueScratch[qTail++] = holder
                }
            }
        }
        if (!augmentingPathExists) break
        for (i in 0 until n) {
            if (matchVar[i] == -1) reginHkAugment(i, valuesPerVar, matchVar, matchVal, distScratch)
        }
    }
    for (i in 0 until n) if (matchVar[i] == -1) return false
    return true
}

// Marks a dead-ended variable so a later free variable in the same phase skips it.
private fun reginHkAugment(
    u: Int,
    valuesPerVar: Array<IntArray>,
    matchVar: IntArray,
    matchVal: IntArray,
    dist: IntArray,
): Boolean {
    val vids = valuesPerVar[u]
    for (k in vids.indices) {
        val vid = vids[k]
        val holder = matchVal[vid]
        if (holder == -1 || (
                dist[holder] == dist[u] + 1 && reginHkAugment(
                    holder,
                    valuesPerVar,
                    matchVar,
                    matchVal,
                    dist,
                )
                )
        ) {
            matchVar[u] = vid
            matchVal[vid] = u
            return true
        }
    }
    dist[u] = Int.MAX_VALUE
    return false
}

internal fun reginTryAugment(
    i: Int,
    valuesPerVar: Array<IntArray>,
    matchVar: IntArray,
    matchVal: IntArray,
    visited: BooleanArray,
): Boolean {
    for (vid in valuesPerVar[i]) {
        if (visited[vid]) continue
        visited[vid] = true
        val holder = matchVal[vid]
        if (holder == -1 || reginTryAugment(holder, valuesPerVar, matchVar, matchVal, visited)) {
            matchVar[i] = vid
            matchVal[vid] = i
            return true
        }
    }
    return false
}

// Iterative to avoid recursion-depth blowup on large graphs.
// Tarjan, "Depth-first search and linear graph algorithms", SIAM J. Comput. 1(2), 1972,
// https://doi.org/10.1137/0201010
internal fun reginTarjanScc(adj: Array<IntArrayList>, total: Int): IntArray {
    val sccId = IntArray(total) { -1 }
    val index = IntArray(total) { -1 }
    val lowlink = IntArray(total)
    val onStack = BooleanArray(total)
    val tarjanStack = IntArray(total)
    var stackTop = 0
    var nextIndex = 0
    var nextScc = 0
    val callStack = IntArray(total)
    val iterStack = IntArray(total)
    for (start in 0 until total) {
        if (index[start] != -1) continue
        var depth = 0
        callStack[depth] = start
        iterStack[depth] = 0
        index[start] = nextIndex
        lowlink[start] = nextIndex
        nextIndex++
        tarjanStack[stackTop++] = start
        onStack[start] = true
        while (depth >= 0) {
            val v = callStack[depth]
            val it = iterStack[depth]
            val neigh = adj[v]
            if (it < neigh.size) {
                iterStack[depth] = it + 1
                val w = neigh[it]
                if (index[w] == -1) {
                    depth++
                    callStack[depth] = w
                    iterStack[depth] = 0
                    index[w] = nextIndex
                    lowlink[w] = nextIndex
                    nextIndex++
                    tarjanStack[stackTop++] = w
                    onStack[w] = true
                } else if (onStack[w]) {
                    if (index[w] < lowlink[v]) lowlink[v] = index[w]
                }
            } else {
                if (lowlink[v] == index[v]) {
                    while (true) {
                        val w = tarjanStack[--stackTop]
                        onStack[w] = false
                        sccId[w] = nextScc
                        if (w == v) break
                    }
                    nextScc++
                }
                depth--
                if (depth >= 0) {
                    val parent = callStack[depth]
                    if (lowlink[v] < lowlink[parent]) lowlink[parent] = lowlink[v]
                }
            }
        }
    }
    return sccId
}
