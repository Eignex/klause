package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * `intVars[i] != intVars[j]` for every pair `i < j`. Stored payload:
 *
 *   refPayload[factorId] = State (counts: IntArray, duplicateCount: Int)
 *
 * `counts` is indexed by `value - domainMin` and tracks how many vars currently hold each
 * value across the union domain `[domainMin, domainMin + domainSize)`. `duplicateCount` is the
 * number of distinct values whose count is > 1; the factor is violated iff that's positive.
 */
class AllDifferent(
    val vars: IntArray,
    val domainMin: Int,
    val domainSize: Int,
) : LocalSearchFactor {

    init {
        require(vars.size >= 2) { "AllDifferent needs at least two variables" }
        require(domainSize >= 1) { "AllDifferent domainSize must be >= 1, got $domainSize" }
    }

    // Propagation strength: full GAC via Régin's matching + SCC algorithm. IntDomain
    // supports interior holes, so non-matching value pruning lands at the variable
    // domain level. Subsumes singleton conflict, singleton-value removal, Hall-interval
    // detection, and global pigeonhole.

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = vars

    /** Pre-computed `intVar → number of slots in [vars] holding it`. Used to compute the
     *  delta of changing a single var's value in O(1) without re-scanning [vars]; for the
     *  common case where each var appears exactly once this is always 1. */
    private val occurrencesByVar: com.eignex.klause.util.IntIntMap = run {
        val counts = HashMap<Int, Int>()
        for (v in vars) counts[v] = (counts[v] ?: 0) + 1
        com.eignex.klause.util.IntIntMap.build(
            keys = counts.keys.toIntArray(),
            values = counts.values.toIntArray(),
            absent = 0,
        )
    }

    private class State(val counts: IntArray, var duplicateCount: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // Sanity: every operand's domain must lie within the declared union range.
        for (v in vars) {
            val d = state.problem.intDomains[v]
            require(d.min >= domainMin && d.max < domainMin + domainSize) {
                "AllDifferent var $v has domain $d outside declared union " +
                    "[$domainMin..${domainMin + domainSize - 1}]"
            }
        }
        val counts = IntArray(domainSize)
        var dups = 0
        for (v in vars) {
            val idx = state.assignment.intValue(v) - domainMin
            val prev = counts[idx]
            counts[idx] = prev + 1
            if (prev == 1) dups++   // count goes 1 -> 2: new duplicate value.
        }
        state.refPayload[factorId] = State(counts, dups)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.duplicateCount > 0
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        val (oldDup, newDup) = simulate(s, occurrences(intVar), old, newValue)
        val wasViolated = s.duplicateCount > 0
        val willViolate = (s.duplicateCount + newDup - oldDup) > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = s.duplicateCount > 0
        val n = occurrences(intVar)
        // Decrement count for oldValue.
        val oldIdx = oldValue - domainMin
        val oldCount = s.counts[oldIdx]
        if (oldCount == 2) s.duplicateCount--
        s.counts[oldIdx] = oldCount - n
        // Increment count for newValue.
        val newIdx = cur - domainMin
        val newCount = s.counts[newIdx]
        val newPlus = newCount + n
        s.counts[newIdx] = newPlus
        if (newCount <= 1 && newPlus >= 2) s.duplicateCount++
        val nowViolated = s.duplicateCount > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /** Compute (oldDuplicateDelta, newDuplicateDelta) without mutating state. */
    private fun simulate(s: State, occurrences: Int, oldValue: Int, newValue: Int): Pair<Int, Int> {
        if (oldValue == newValue) return 0 to 0
        val oldCount = s.counts[oldValue - domainMin]
        val newCount = s.counts[newValue - domainMin]
        var lostDup = 0
        var gainedDup = 0
        if (oldCount >= 2 && oldCount - occurrences <= 1) lostDup = 1
        if (newCount <= 1 && newCount + occurrences >= 2) gainedDup = 1
        return lostDup to gainedDup
    }

    private fun occurrences(intVar: Int): Int = occurrencesByVar[intVar]

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = vars.size

        // Build compact value-id mapping and per-var sorted value-id lists from current
        // domains. Cost: O(Σ |dom(xᵢ)|).
        val valueId = HashMap<Int, Int>()
        val idToValue = ArrayList<Int>()
        val valuesPerVar = Array(n) { i ->
            val d = state.intDomains[vars[i]]
            val list = IntArray(d.size)
            var k = 0
            d.forEach { v ->
                val id = valueId.getOrPut(v) { idToValue.add(v); idToValue.size - 1 }
                list[k++] = id
            }
            list
        }
        val numValues = idToValue.size
        if (numValues < n) return false  // pigeonhole.

        // Maximum bipartite matching via successive augmenting paths. O(n · |E|).
        val matchVar = IntArray(n) { -1 }
        val matchVal = IntArray(numValues) { -1 }
        val visited = BooleanArray(numValues)
        for (i in 0 until n) {
            for (j in visited.indices) visited[j] = false
            if (!tryAugment(i, valuesPerVar, matchVar, matchVal, visited)) return false
        }

        // Build directed graph (Régin orientation):
        //  - Matched edges: value → var.
        //  - Non-matched in-domain edges: var → value.
        // Tarjan SCC over the combined node set (vars 0..n-1, values n..n+numValues-1).
        val total = n + numValues
        val adj = Array(total) { IntArray(0) }
        val adjCount = IntArray(total)
        for (i in 0 until n) {
            for (vid in valuesPerVar[i]) {
                if (matchVar[i] == vid) adjCount[n + vid]++ else adjCount[i]++
            }
        }
        for (i in 0 until total) adj[i] = IntArray(adjCount[i])
        val adjFill = IntArray(total)
        for (i in 0 until n) {
            for (vid in valuesPerVar[i]) {
                if (matchVar[i] == vid) {
                    val src = n + vid
                    adj[src][adjFill[src]++] = i
                } else {
                    adj[i][adjFill[i]++] = n + vid
                }
            }
        }

        // BFS from free values (matchVal[v] == -1) forward through `adj`. Any node
        // reachable corresponds to an edge that participates in *some* maximum matching
        // (alternating-path argument).
        val reachedFromFree = BooleanArray(total)
        val queue = IntArray(total)
        var qHead = 0
        var qTail = 0
        for (vid in 0 until numValues) {
            if (matchVal[vid] == -1) {
                val node = n + vid
                reachedFromFree[node] = true
                queue[qTail++] = node
            }
        }
        while (qHead < qTail) {
            val u = queue[qHead++]
            for (w in adj[u]) {
                if (!reachedFromFree[w]) {
                    reachedFromFree[w] = true
                    queue[qTail++] = w
                }
            }
        }

        // Tarjan SCC. Iterative to avoid stack blowup on large n.
        val sccId = IntArray(total) { -1 }
        run {
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
        }

        // Prune: any var→value edge that's neither matched, nor in the same SCC, nor
        // reachable from a free value cannot extend to a perfect matching and must be
        // removed from the variable's domain. LCG antecedents: each prune's reason is
        // the union of every *other* var's int antecedents — those domains together
        // determined the matching/SCC structure that forbade this value.
        val antecedents = composeAllDifferentAntecedents(state)
        for (i in 0 until n) {
            for (vid in valuesPerVar[i]) {
                if (matchVar[i] == vid) continue
                val valNode = n + vid
                if (sccId[i] == sccId[valNode]) continue
                if (reachedFromFree[valNode]) continue
                if (!state.excludeIntValue(vars[i], idToValue[vid], antecedents)) return false
            }
        }
        return true
    }

    /** Union the int antecedents of every var in this AllDifferent — coarse but sound
     *  reason for any Régin-SCC prune (every other var's bounds participated in the
     *  matching/SCC analysis). Returns `null` when no var has recorded antecedents. */
    private fun composeAllDifferentAntecedents(state: PropagationState): IntArray? {
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()
        for (v in vars) {
            state.intMinAntecedents[v]?.let { for (l in it) if (seen.add(l)) out.add(l) }
            state.intMaxAntecedents[v]?.let { for (l in it) if (seen.add(l)) out.add(l) }
        }
        if (out.isEmpty()) return null
        return out.toIntArray()
    }

    /** Hopcroft-Karp-style augmenting-path search for max bipartite matching. Returns
     *  true iff variable [i] can be matched (possibly re-routing earlier matches). */
    private fun tryAugment(
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
            if (holder == -1 || tryAugment(holder, valuesPerVar, matchVar, matchVal, visited)) {
                matchVar[i] = vid
                matchVal[vid] = i
                return true
            }
        }
        return false
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.duplicateCount == 0) return
        // Reservoir-sample a duplicated value (uniform across all values whose count > 1).
        var pickedIdx = -1
        var seenDups = 0
        for (idx in s.counts.indices) {
            if (s.counts[idx] <= 1) continue
            seenDups++
            if (state.rng.nextInt(seenDups) == 0) pickedIdx = idx
        }
        if (pickedIdx == -1) return
        val value = pickedIdx + domainMin
        // Reservoir-sample one of its occupants.
        var occupant = -1
        var seenOccupants = 0
        for (v in vars) {
            if (state.assignment.intValue(v) != value) continue
            seenOccupants++
            if (state.rng.nextInt(seenOccupants) == 0) occupant = v
        }
        if (occupant == -1) return
        val d = state.problem.intDomains[occupant]
        // Reservoir-sample up to MAX_REPAIR_TARGETS unused targets from the occupant's domain.
        // Giving the strategy a fan of candidates (instead of one) lets WalkSat/probSAT score
        // by break count and pick the move that disturbs the fewest currently-satisfied factors —
        // a real choice rather than coin-flipping a single sampled target.
        val targets = IntArray(MAX_REPAIR_TARGETS) { Int.MIN_VALUE }
        var filled = 0
        var seenTargets = 0
        // `forEach` skips holes for sparse domains; contiguous fast path is identical
        // to the previous `min..max` walk.
        d.forEach { target ->
            if (target != value) {
                val tIdx = target - domainMin
                if (tIdx in s.counts.indices && s.counts[tIdx] == 0) {
                    seenTargets++
                    if (filled < MAX_REPAIR_TARGETS) {
                        targets[filled++] = target
                    } else {
                        val r = state.rng.nextInt(seenTargets)
                        if (r < MAX_REPAIR_TARGETS) targets[r] = target
                    }
                }
            }
        }
        if (filled > 0) {
            for (i in 0 until filled) sink.addIntSet(occupant, targets[i])
            return
        }
        // No unused targets — every domain value is already taken. A single-var nudge
        // would just shuffle the duplicate. Propose value-swap candidates: pair the
        // occupant with the unique holder of another value in its domain. Within this
        // one AllDifferent the swap preserves the value multiset (so the local duplicate
        // count is unchanged), but in problems with multiple coupled AllDifferents (e.g.
        // Sudoku rows × columns) the swap may resolve a duplicate elsewhere. Cap to
        // [MAX_SWAP_CANDIDATES] — each Compound costs an apply-and-revert in scoring.
        var swapsAdded = 0
        for (w in d.min..d.max) {
            if (swapsAdded >= MAX_SWAP_CANDIDATES) break
            if (w == value) continue
            if (w !in d) continue  // sparse-aware: skip holes in occupant's domain
            val wIdx = w - domainMin
            if (wIdx !in s.counts.indices || s.counts[wIdx] != 1) continue
            // Locate the unique holder of w. O(|vars|) per candidate; bounded by
            // MAX_SWAP_CANDIDATES so total cost is fixed.
            var holder = -1
            for (v in vars) if (state.assignment.intValue(v) == w) { holder = v; break }
            if (holder == -1 || holder == occupant) continue
            val hd = state.problem.intDomains[holder]
            if (value !in hd) continue  // also sparse-aware on holder's domain
            sink.addCompound(listOf(Move.IntSet(occupant, w), Move.IntSet(holder, value)))
            swapsAdded++
        }
        if (swapsAdded > 0) return
        // Last-resort fallback: nudge occupant by ±1 within domain.
        val cur = state.assignment.intValue(occupant)
        if (cur < d.max) sink.addIntSet(occupant, cur + 1)
        if (cur > d.min) sink.addIntSet(occupant, cur - 1)
    }

    private companion object {
        /** Cap on candidate targets per repair call. Each candidate adds one O(arity) break-score
         *  evaluation in WalkSat/probSAT, so don't go wild — the fan only needs to be wide enough
         *  for the strategy to discriminate. */
        const val MAX_REPAIR_TARGETS: Int = 4
        /** Cap on swap-pair candidates per call. Each pair requires an O(|vars|) holder lookup
         *  plus the apply-and-revert in [LocalSearchState.evaluateCompound]; two is enough for
         *  the strategy to pick a swap over a single-var move when the domain is saturated. */
        const val MAX_SWAP_CANDIDATES: Int = 2
    }
}
