package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Generalised `alldifferent_except(xs, except)` — `xs[i] != xs[j]` for every pair `i < j`
 * unless one of the two values is in [except]. The classic [AllDifferentExceptZero] is the
 * `except = {0}` specialisation; this factor uses a HashSet membership check so propagation
 * over arbitrary excluded-value sets remains O(N · |except|) per call.
 *
 * Propagation is the same singleton-take filter as the zero-only variant: any var pinned to
 * a non-excluded value `v` removes `v` from every other var's domain.
 */
class AllDifferentExcept(
    /** Integer variable ids required to be pairwise distinct outside [except]. */
    val xs: IntArray,
    except: IntArray,
) : LocalSearchFactor {

    val except: IntArray = except.distinct().sorted().toIntArray()
    private val exceptSet: Set<Int> = this.except.toHashSet()

    init {
        require(xs.size >= 2) { "AllDifferentExcept needs at least two variables" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** Per-value count among non-excluded values. `violatedPairs` is the number of (i, j) with
     *  i < j and xs[i] = xs[j] ∉ except. */
    private class State(val counts: HashMap<Int, Int>, var violatedPairs: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = HashMap<Int, Int>()
        var bad = 0
        for (v in xs) {
            val value = state.assignment.intValue(v)
            if (value in exceptSet) continue
            val prev = counts[value] ?: 0
            counts[value] = prev + 1
            if (prev >= 1) bad++
        }
        state.refPayload[factorId] = State(counts, bad)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.violatedPairs > 0
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences == 0) return 0
        var bad = s.violatedPairs
        if (old !in exceptSet) {
            val cnt = s.counts[old] ?: 0
            val after = cnt - occurrences
            bad -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
        }
        if (newValue !in exceptSet) {
            val cnt = s.counts[newValue] ?: 0
            val after = cnt + occurrences
            bad += pairsAt(after) - pairsAt(cnt)
        }
        val wasViolated = s.violatedPairs > 0
        val willViolate = bad > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences == 0) return 0
        val wasViolated = s.violatedPairs > 0
        if (oldValue !in exceptSet) {
            val cnt = s.counts[oldValue] ?: 0
            val after = cnt - occurrences
            s.violatedPairs -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
            if (after <= 0) s.counts.remove(oldValue) else s.counts[oldValue] = after
        }
        if (cur !in exceptSet) {
            val cnt = s.counts[cur] ?: 0
            val after = cnt + occurrences
            s.violatedPairs += pairsAt(after) - pairsAt(cnt)
            s.counts[cur] = after
        }
        val nowViolated = s.violatedPairs > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun pairsAt(k: Int): Int = if (k <= 1) 0 else k * (k - 1) / 2

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Phase 1: singleton-take filter (cheap, runs first).
        val taken = HashSet<Int>()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (d.min in exceptSet) continue
            if (!taken.add(d.min)) return false
        }
        if (taken.isNotEmpty()) {
            val ant = state.composeIntVarAtomAntecedents(xs)
            for (v in xs) {
                val d = state.intDomains[v]
                if (d.min == d.max) continue
                for (t in taken) {
                    if (t < d.min || t > d.max) continue
                    if (!state.excludeIntValue(v, t, ant)) return false
                }
            }
        }
        // Phase 2: Régin-style matching-and-SCC pruning. Runs only on the residual
        // (var, non-except value) bipartite graph. Vars whose domain intersects `except`
        // get an "escape" via a virtual exception sink, so they don't have to match.
        return reginPropagate(state)
    }

    /**
     * Régin's domain consistency for alldifferent (extended to alldifferent_except).
     *
     * Steps:
     *  1. Restrict to non-except values across all xs' current domains.
     *  2. Augmenting-path matching of vars to non-except values. Vars whose domain ∩ except
     *     is non-empty have an extra "escape" — they can be unmatched without failure.
     *  3. If any "must-take-non-except" var (no escape) is unmatched, fail.
     *  4. Build the oriented residual graph: matched (val→var), unmatched (var→val); add a
     *     sink with edges from every "may-escape" var to it (acts as a free unmatched val).
     *  5. Forward-DFS from every free vertex (unmatched non-except value or the sink). Mark
     *     reachable vertices.
     *  6. Tarjan SCC on the oriented graph.
     *  7. Remove value v from var x's domain when:
     *      - (x, v) is not the matching edge for x AND
     *      - v is not reachable from any free vertex AND
     *      - x and v are in different SCCs.
     *
     * Complexity O((|V|+|E|) · √|V|) for Hopcroft-Karp; here we use simple O(VE) augmenting
     * paths for clarity.
     */
    private fun reginPropagate(state: PropagationState): Boolean {
        val n = xs.size
        val ant = state.composeIntVarAtomAntecedents(xs)

        // Collect non-except values present across domains (hole-aware).
        val valueSet = HashSet<Int>()
        var domHi = Int.MIN_VALUE
        var domLo = Int.MAX_VALUE
        for (v in xs) {
            val d = state.intDomains[v]
            d.forEach { vv -> if (vv !in exceptSet) valueSet += vv }
            if (d.min < domLo) domLo = d.min
            if (d.max > domHi) domHi = d.max
        }
        if (valueSet.isEmpty()) return true
        val values = valueSet.toIntArray().also { it.sort() }
        val valueIndex = HashMap<Int, Int>(values.size)
        for ((i, v) in values.withIndex()) valueIndex[v] = i
        val numVals = values.size

        // Build adjacency: var i → list of non-except value-indices in its domain.
        val varAdj = Array(n) { i ->
            val d = state.intDomains[xs[i]]
            val out = ArrayList<Int>()
            d.forEach { vv -> if (vv !in exceptSet) out += valueIndex[vv]!! }
            out.toIntArray()
        }
        val mayEscape = BooleanArray(n) { i ->
            val d = state.intDomains[xs[i]]
            // Domain intersects except.
            except.any { it in d }
        }

        // Find a maximum matching of vars to value-indices via augmenting paths.
        val matchVarToVal = IntArray(n) { -1 }
        val matchValToVar = IntArray(numVals) { -1 }
        for (i in 0 until n) {
            if (matchVarToVal[i] != -1) continue
            val visited = BooleanArray(numVals)
            if (!augment(i, varAdj, matchVarToVal, matchValToVar, visited)) {
                // Couldn't match — only fail if var must take a non-except value.
                if (!mayEscape[i]) return false
            }
        }

        // Build oriented residual graph for SCC / reachability.
        //   Vertices: 0..n-1 = vars; n..n+numVals-1 = values; n+numVals = exception sink.
        val totalV = n + numVals + 1
        val sinkV = n + numVals
        val adj = Array(totalV) { ArrayList<Int>() }
        for (i in 0 until n) {
            for (vi in varAdj[i]) {
                if (matchVarToVal[i] == vi) {
                    // Matched edge: orient val → var.
                    adj[n + vi].add(i)
                } else {
                    // Unmatched edge: orient var → val.
                    adj[i].add(n + vi)
                }
            }
            if (mayEscape[i]) {
                // Match an unmatched may-escape var to sink, else var → sink unmatched.
                if (matchVarToVal[i] == -1) {
                    adj[sinkV].add(i)
                } else {
                    adj[i].add(sinkV)
                }
            }
        }

        // Free vertices: unmatched non-except values + sink.
        val free = BooleanArray(totalV)
        for (vi in 0 until numVals) if (matchValToVar[vi] == -1) free[n + vi] = true
        free[sinkV] = true

        // Forward BFS from all free vertices.
        val reachableFromFree = BooleanArray(totalV)
        val queue = ArrayDeque<Int>()
        for (v in 0 until totalV) {
            if (free[v]) {
                reachableFromFree[v] = true
                queue.add(v)
            }
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (w in adj[u]) {
                if (!reachableFromFree[w]) {
                    reachableFromFree[w] = true
                    queue.add(w)
                }
            }
        }

        // Tarjan SCC on the same oriented graph.
        val sccId = IntArray(totalV) { -1 }
        run {
            val index = IntArray(totalV) { -1 }
            val low = IntArray(totalV)
            val onStack = BooleanArray(totalV)
            val stack = ArrayDeque<Int>()
            var idx = 0
            var sccCount = 0
            // Iterative Tarjan to avoid recursion depth issues.
            val callStack = ArrayDeque<IntArray>() // [node, edgeIndex]
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

        // Prune values that lie on no augmenting / SCC-internal path.
        for (i in 0 until n) {
            val d = state.intDomains[xs[i]]
            for (vi in varAdj[i]) {
                if (matchVarToVal[i] == vi) continue // matched edge keeps val
                val valVertex = n + vi
                if (reachableFromFree[valVertex]) continue // on some alternating path
                if (sccId[i] == sccId[valVertex]) continue // on a closed alternating cycle
                val value = values[vi]
                if (value in d.min..d.max) {
                    if (!state.excludeIntValue(xs[i], value, ant)) return false
                }
            }
        }
        return true
    }

    private fun augment(
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
            if (w == -1 || augment(w, varAdj, matchVarToVal, matchValToVar, visited)) {
                matchVarToVal[i] = vi
                matchValToVar[vi] = i
                return true
            }
        }
        return false
    }

    /** Reservoir-sample a duplicated value uniformly across all duplicates, then reservoir-
     *  sample one of its occupants, then propose multiple candidate targets: the excluded
     *  sentinels (which never break the constraint) plus reservoir-sampled in-domain
     *  unused values. Mirrors the structure of [AllDifferent.proposeRepairMoves] so the
     *  except variant has the same search diversity as the base. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.violatedPairs == 0) return
        // Reservoir-sample a duplicated value.
        var pickedValue = Int.MIN_VALUE
        var seenDups = 0
        for ((value, count) in s.counts) {
            if (count < 2) continue
            seenDups++
            if (state.rng.nextInt(seenDups) == 0) pickedValue = value
        }
        if (pickedValue == Int.MIN_VALUE) return
        // Reservoir-sample one occupant of that value.
        var occupant = -1
        var seenOccupants = 0
        for (v in xs) {
            if (state.assignment.intValue(v) != pickedValue) continue
            seenOccupants++
            if (state.rng.nextInt(seenOccupants) == 0) occupant = v
        }
        if (occupant == -1) return
        val d = state.problem.intDomains[occupant]
        // Excluded sentinels first — they're always safe targets.
        var emitted = 0
        for (e in except) {
            if (e in d && e != pickedValue) {
                sink.addChannelingIntSet(state, occupant, e)
                if (++emitted >= MAX_REPAIR_TARGETS) return
            }
        }
        // Reservoir-sample unused (count == 0) targets from the occupant's domain. Cap at
        // MAX_REPAIR_TARGETS combined with the sentinel emissions above so the proposal
        // set stays bounded.
        val budget = MAX_REPAIR_TARGETS - emitted
        if (budget <= 0) return
        val targets = IntArray(budget) { Int.MIN_VALUE }
        var filled = 0
        var seenTargets = 0
        d.forEach { target ->
            if (target == pickedValue) return@forEach
            if (target in exceptSet) return@forEach // already proposed above
            val count = s.counts[target] ?: 0
            if (count != 0) return@forEach
            seenTargets++
            if (filled < budget) {
                targets[filled++] = target
            } else {
                val r = state.rng.nextInt(seenTargets)
                if (r < budget) targets[r] = target
            }
        }
        for (i in 0 until filled) sink.addChannelingIntSet(state, occupant, targets[i])
    }

    private companion object {
        /** Cap on total target candidates proposed per call. Matches AllDifferent.MAX_REPAIR_TARGETS. */
        const val MAX_REPAIR_TARGETS: Int = 4
    }
}
