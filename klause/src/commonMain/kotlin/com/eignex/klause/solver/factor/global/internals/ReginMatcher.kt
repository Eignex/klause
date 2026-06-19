package com.eignex.klause.solver.factor.global.internals

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.factor.arithmetic.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt
import com.eignex.klause.solver.propagation.RevIntArray
import com.eignex.klause.solver.propagation.RevRef
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap

/**
 * Shared Régin domain-consistency filtering for the alldifferent family — [AllDifferent]
 * routes its matching pass through
 * here, so the bipartite matching / reverse-graph free-value reachability / Tarjan SCC / Hall
 * pruning machinery lives once rather than being copy-pasted (and drifting) per variant.
 *
 * The graph: variables on the left, distinct in-domain values on the right (matched
 * value→var, unmatched var→value). A value with no support after matching is pruned from the
 * variable's domain. Reachability is walked over the **reverse** graph from free (unmatched)
 * values — a free value is a sink in the forward orientation, so a forward walk would reach
 * nothing and wrongly prune every slack edge (this was the historical false-UNSAT defect).
 *
 * `alldifferent_except`: each value in [exceptSet] may be shared by any number of variables.
 * It is modelled as `n` capacity-1 copies (distinct value-ids over the same value), turning
 * `alldifferent_except` into a *pure* alldifferent over the expanded value universe — so the
 * exact proven matching/reachability/SCC machinery applies unchanged. Except values are never
 * pruned (there is always a free copy), and copies inflate the value count so the pigeonhole
 * shortcut never misfires.
 *
 * Prune antecedents are the sharp Hall set (forward-reachable vars from the pruned value-node)
 * with hole-aware [collectHoleAndBoundAntecedents], so learned clauses stay sound under holes.
 *
 * Returns `null` on success (after pruning in place), or the Hall-violator variable ids on
 * infeasibility — the caller stores them as its conflict reason.
 */
internal fun reginFilter(
    state: PropagationState,
    filteredVars: IntArray,
    exceptSet: IntHashSet,
    cache: ReginCache? = null,
): IntArray? {
    val n = filteredVars.size
    if (n < 2) return null

    // Unchanged-domains fast path: if the previous fire on this var set succeeded (returned null,
    // i.e. pruned to a GAC fixpoint) and no var's domain has changed since, that fixpoint still
    // holds and there is nothing to prune — skip the matching/SCC rebuild entirely. Sound to drift:
    // the refs only ever *miss* after a backtrack restores a different IntDomain (never falsely
    // match), so no reversible/snapshot is needed (cf. GCC #584, Table #580, Element #581).
    if (cache != null && cache.fixpointHolds(state, filteredVars)) return null

    // Incremental path: plain alldifferent (no excepted values) with a cache carries reversible
    // matching + SCC state across fires (stable value-id universe), patching only the components
    // touched by the values that left since the last fire. The except/no-cache cases keep the
    // full per-fire rebuild below.
    if (cache != null && exceptSet.isEmpty()) return reginIncremental(state, filteredVars, cache)

    // Compact value-id mapping + per-var value-id lists (hole-aware). Non-except values get one
    // id; each except value gets `n` capacity-1 copies (contiguous ids) so up to n vars share it.
    // Both maps key on domain values and store non-negative ids, so -1 is a safe "absent"
    // sentinel for the primitive lookup (no real id is negative).
    val valueId = MutableIntIntMap() // non-except value → id
    val exceptBase = MutableIntIntMap() // except value → first of its n copy ids
    val idToValue = IntArrayList()
    val valuesPerVar = Array(n) { i ->
        val d = state.intDomains[filteredVars[i]]
        val list = IntArrayList()
        d.forEach { v ->
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
    // Pigeonhole: n vars confined to < n distinct values cannot all differ. (Except copies
    // inflate numValues to ≥ n, so this only fires when no excepted value is available.)
    if (numValues < n) return filteredVars

    // ---- Maximum bipartite matching via successive augmenting paths. O(n · |E|). ----
    val matchVar = IntArray(n) { -1 }
    val matchVal = IntArray(numValues) { -1 }
    val visited = BooleanArray(numValues)
    // Warm start (#96): reuse the previous matching for edges still valid after domain
    // shrinkage; only the now-unmatched vars need augmenting. The completed matching is still
    // maximum and Régin pruning is matching-independent, so this changes speed, not results.
    if (cache != null) {
        for (i in 0 until n) {
            if (!cache.matchedValue.containsKey(filteredVars[i])) continue
            val prev = cache.matchedValue.getOrDefault(filteredVars[i], 0)
            if (prev !in state.intDomains[filteredVars[i]]) continue // edge broke
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
    for (i in 0 until n) {
        if (matchVar[i] != -1) continue // already seeded from the warm-start matching
        for (j in visited.indices) visited[j] = false
        if (!reginTryAugment(i, valuesPerVar, matchVar, matchVal, visited)) {
            // Augment failed: the saturated values plus their occupants and i form a Hall
            // violator (every one of those vars' domains lies within the visited value set).
            val hall = IntArrayList()
            hall.add(filteredVars[i])
            for (vid in 0 until numValues) {
                if (visited[vid] && matchVal[vid] >= 0) hall.add(filteredVars[matchVal[vid]])
            }
            return hall.toIntArray()
        }
    }

    // ---- Oriented graph: matched value→var, unmatched var→value (vars 0..n-1, values after). ----
    val total = n + numValues
    // Reuse the per-session adjacency buffers (cleared to `total`) instead of allocating
    // 2·total fresh IntArrayLists every fire; fall back to fresh arrays when no cache is supplied.
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

    // ---- Reachability from free (unmatched) values over the REVERSE graph. ----
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

    // ---- Prune: a non-matched var→value edge with no support (different SCC and not reachable
    // from a free value) cannot extend to a feasible matching. Except values are never pruned
    // (their copies always leave slack). Antecedents cite the sharp Hall set forward-reachable
    // from the value-node (memoised per value-SCC), hole-aware. ----
    val sccHallVars = HashMap<Int, IntArray>()
    fun hallVarsFor(valNode: Int): IntArray = sccHallVars.getOrPut(sccId[valNode]) {
        val vis = BooleanArray(total)
        val bfs = IntArray(total)
        var qh = 0
        var qt = 0
        vis[valNode] = true
        bfs[qt++] = valNode
        val acc = IntArrayList()
        while (qh < qt) {
            val u = bfs[qh++]
            if (u < n) acc.add(filteredVars[u])
            val a = adj[u]
            for (k in 0 until a.size) {
                val w = a[k]
                if (!vis[w]) {
                    vis[w] = true
                    bfs[qt++] = w
                }
            }
        }
        acc.toIntArray()
    }
    for (i in 0 until n) {
        for (vid in valuesPerVar[i]) {
            if (matchVar[i] == vid) continue
            val value = idToValue[vid]
            if (value in exceptSet) continue // excepted values stay shareable, never pruned
            val valNode = n + vid
            if (sccId[i] == sccId[valNode]) continue
            if (reachedFromFree[valNode]) continue
            val hall = hallVarsFor(valNode)
            val ant = collectHoleAndBoundAntecedents(state, hall)
            if (!state.excludeIntValue(filteredVars[i], value, ant)) {
                // Excluding the value emptied var i's domain: the Hall set forced out i's last
                // feasible value. Reason = the Hall set plus i.
                val withI = hall.copyOf(hall.size + 1)
                withI[hall.size] = filteredVars[i]
                return withI
            }
        }
    }
    // Persist the matching as the next call's warm-start seed, and record the GAC fixpoint
    // (var set + per-var domain refs) for the unchanged-domains fast path above.
    if (cache != null) {
        cache.matchedValue.clear()
        for (i in 0 until n) {
            if (matchVar[i] != -1) cache.matchedValue.put(filteredVars[i], idToValue[matchVar[i]])
        }
        cache.recordFixpoint(state, filteredVars)
    }
    return null
}

/** Per-factor warm-start state for [reginFilter]: the previous maximum matching as
 *  `variable id → matched value`. A seed only — [reginFilter] revalidates every edge against
 *  the current domains and completes to a maximum matching, so a stale cache never affects
 *  correctness, only the number of augmenting searches. It therefore needs no backtrack snapshot:
 *  the cache simply **drifts** across push/pop (a stale post-backtrack matching just costs a few
 *  more augmenting searches), like CDCL watches — no longer a [PropagationState.SnapshottablePayload]. */
internal class ReginCache {
    val matchedValue = MutableIntIntMap()

    // Unchanged-domains fast-path state: the var-id list and each var's [IntDomain] ref at the
    // last successful (null-returning) fire. Drifts across backtrack — a stale entry only ever
    // *misses* (ref-inequality against the restored domain), never falsely matches — so no
    // reversible/snapshot is needed. `lastVars == null` means no fixpoint is on record yet.
    private var lastVars: IntArray? = null
    private var lastDoms: Array<IntDomain?> = emptyArray()

    /** True iff the previous fire on exactly [vars] pruned to a GAC fixpoint that still holds —
     *  same var set, and every var's [IntDomain] ref unchanged since. */
    fun fixpointHolds(state: PropagationState, vars: IntArray): Boolean {
        val lv = lastVars ?: return false
        if (!lv.contentEquals(vars)) return false
        for (i in vars.indices) if (lastDoms[i] !== state.intDomains[vars[i]]) return false
        return true
    }

    /** Record the post-prune GAC fixpoint for the next fire's [fixpointHolds] check. */
    fun recordFixpoint(state: PropagationState, vars: IntArray) {
        if (lastDoms.size < vars.size) lastDoms = arrayOfNulls(vars.size)
        lastVars = vars.copyOf()
        for (i in vars.indices) lastDoms[i] = state.intDomains[vars[i]]
    }

    // ---- Incremental Régin: a persistent grow-only value universe + the reversible matching/SCC
    //      state, used by [reginIncremental] for the no-except, stable-var-set path. The universe
    //      gives values stable node ids across fires (the per-fire compaction in [reginFilter] does
    //      not), which is the prerequisite for carrying reversible graph state across fires. ----
    private val idOfValue = MutableIntIntMap()
    val valueOfId = IntArrayList()

    /** Stable id for a domain [value] (grows the universe on first sight). */
    fun idFor(value: Int): Int {
        var id = idOfValue.getOrDefault(value, -1)
        if (id < 0) {
            id = valueOfId.size
            valueOfId.add(value)
            idOfValue.put(value, id)
        }
        return id
    }

    private var inc: ReginIncrementalState? = null

    /** The incremental state for var set [vars], (re)created when the var set changes or the value
     *  universe outgrows the current capacity. A fresh state starts invalid ([ReginIncrementalState.valid]
     *  == 0), forcing a full rebuild that re-seeds it. */
    fun incremental(state: PropagationState, vars: IntArray): ReginIncrementalState {
        for (vi in vars) state.intDomains[vi].forEach { v -> idFor(v) }
        val need = valueOfId.size
        val cur = inc
        if (cur == null || !cur.vars.contentEquals(vars) || cur.valueCap < need) {
            val cap = maxOf(need, cur?.valueCap ?: 0)
            return ReginIncrementalState(state, vars, cap).also { inc = it }
        }
        return cur
    }

    // Reusable oriented-graph adjacency buffers for [reginFilter] — the dominant per-fire
    // allocation (2·(n + numValues) IntArrayLists). Grown on demand, and the live `[0, total)`
    // lists are cleared and refilled every fire, so reuse is behaviour-identical; they hold no
    // level state (scratch rebuilt every fire), so like the matching seed they need no snapshot.
    private var adjBuf: Array<IntArrayList> = emptyArray()
    private var radjBuf: Array<IntArrayList> = emptyArray()

    /** Forward/reverse adjacency arrays sized `>= total`, with the `[0, total)` lists cleared. */
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

    /** The Hall violator behind the most recent propagate failure on this session — written
     *  at the failing point, read immediately afterwards by the analyzer via the factor's
     *  conflictReason. Lives here (per-session payload) rather than on the factor object so
     *  portfolio workers sharing one Problem never read another session's reason (#182).
     *  Propagate-to-analysis transient; never needs to survive a backtrack. */
    var conflictVars: IntArray? = null
}

/** Reversible, delta-driven incremental-Régin state for one stable variable set (plain
 *  alldifferent), driven by [reginIncremental]. The maximum matching and canonical SCC labels ride
 *  the engine undo trail, so a backtrack restores them in O(changes); [valid] (also reversible)
 *  drops to 0 when a backtrack lands above the level that seeded the state, forcing a fresh rebuild.
 *  Node space is stable: variable `i` is node `i`, value-id `j` is node `n + j`. */
internal class ReginIncrementalState(state: PropagationState, val vars: IntArray, val valueCap: Int) {
    val n = vars.size
    val total = n + valueCap

    /** 1 once [reginIncremental]'s rebuild has seeded this state at the current (or an ancestor)
     *  level; reversible, so a backtrack above the seeding level resets it to 0 → rebuild. */
    val valid = RevInt(state, 0)

    /** Maximum matching: `matchVar[i]` = the value-id var `i` is matched to (-1 if unmatched);
     *  `matchVal[j]` = the var matched to value-id `j` (-1 if free). Reversible. */
    val matchVar = RevIntArray(state, n, -1)
    val matchVal = RevIntArray(state, valueCap, -1)

    /** Canonical SCC label per node (`0 until total`) — each component labelled by its min node id.
     *  Reversible. */
    val sccId = RevIntArray(state, total, -1)

    /** Each var's [IntDomain] at the end of its last fire — the delta base. Reversible, so it rolls
     *  back with the domains and the `current` ⊆ `domRef` (deletions-only) invariant always holds. */
    val domRef = Array(n) { RevRef<IntDomain?>(state, null) }
}

/** Augmenting-path search for maximum bipartite matching. Returns true iff variable `i` can be
 *  matched (possibly re-routing earlier matches). Shared with the incremental path
 *  ([reginIncremental]), which seeds from the reversible matching and completes it here. */
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

/** Iterative Tarjan SCC over [adj] (adjacency lists on `0 until total`). Returns per-vertex
 *  component id. Iterative to avoid recursion-depth blowup on large graphs. SCC membership is
 *  reversal-invariant, so the forward orientation is used here. Shared with [GlobalCardinality],
 *  which materialises its residual graph and delegates here (#99). */
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
