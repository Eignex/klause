package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import kotlin.math.abs

/**
 * Hamiltonian-cycle constraint: `succ` is an array of `n` variables, each holding the index
 * of the next node in the circuit. A valid assignment forms exactly one cycle that visits
 * every node — starting from any node and following `succ` repeatedly returns to the start
 * after exactly `n` steps with all `n` nodes visited.
 *
 * Semantics:
 *  - `succ[i] = j` reads "node `j` is the successor of node `i`".
 *  - Domain: each `succ[i]` must hold a value in `[0, n)`. Out-of-range values count as
 *    violations.
 *  - Self-loops (`succ[i] = i`) are violations when `n ≥ 2` — use [Subcircuit] for the
 *    self-loop-as-excluded variant.
 *  - Sub-cycles (e.g. `succ[0]=1, succ[1]=0` with `n ≥ 3`) are violations.
 *
 * LS cost is graded:
 *   `cost = |numCycles − 1| + (n − nodesInCycles) + numSelfLoops + numOutOfBounds`
 * — broken assignments rank in proportion to "how far off Hamiltonian" they are
 * (multiple disjoint cycles are worse than one near-cycle missing a couple of nodes), so
 * strategies see a useful gradient instead of a flat broken/satisfied bit.
 *
 * Propagation:
 *  - Bounds: every `succ[i]` is tightened to `[0, n)`.
 *  - Self-loop shaving: `succ[i] != i` for `n ≥ 2` (shaves at domain endpoints).
 *  - AllDifferent pigeonhole: every value held as a singleton by some `succ[i]` is
 *    shaved from every other variable's domain endpoints.
 *  - Sub-cycle prevention: for each non-singleton variable, walks backward through
 *    singleton predecessors to find the start of the fixed chain ending at this node.
 *    If the chain spans fewer than `n` nodes, the chain-start value is forbidden (closing
 *    would form a sub-cycle of length < `n`). If the chain spans `n − 1` nodes (one
 *    successor still to choose), the chain-start value is *forced* — the only completion.
 *  - Worklist-driven: one propagate() call does one pass; the engine re-fires on
 *    subsequent tightenings, so cascades resolve over multiple calls.
 */
class Circuit(
    /** Successor variable id per node; the assignment must form one Hamiltonian cycle. */
    succ: IntArray,
) : SuccessorCycleFactor(succ) {

    init {
        require(succ.isNotEmpty()) { "Circuit needs at least one var, got ${succ.size}" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Circuit(succ.remapVars(intMap))

    /** Position-faithful: `succ(i)` is node i's successor, so the array order is meaningful — the key
     *  keeps the variables in order rather than sorting them (#443). */
    override fun structuralKey(): String = "circuit:" + succ.joinToString(",")

    /**
     * Graded cost: `|numCycles − 1| + (n − nodesInCycles) + numSelfLoops + numOutOfBounds`.
     * Returns 0 iff the assignment (with optional override `succ[replaceAt] = replaceWith`)
     * is a single Hamiltonian cycle of length `n`. O(n).
     */
    override fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Int {
        if (n == 1) {
            val v = if (replaceAt == 0) replaceWith else state.assignment.intValue(succ[0])
            return if (v == 0) 0 else 1
        }
        // Effective next value per node.
        val next = IntArray(n)
        var numSelfLoops = 0
        var numOob = 0
        for (i in 0 until n) {
            val s = if (i == replaceAt) replaceWith else state.assignment.intValue(succ[i])
            if (s < 0 || s >= n) {
                next[i] = -1
                numOob++
            } else if (s == i) {
                next[i] = -1
                numSelfLoops++ // self-loop forbidden for n ≥ 2
            } else {
                next[i] = s
            }
        }
        // Functional-graph cycle decomposition: each valid node has one out-edge, nodes
        // with next(i) = -1 are sinks.
        val scan = cycleScan(next)
        return abs(scan.numCycles - 1) + (n - scan.nodesInCycles) + numSelfLoops + numOob
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // LCG antecedents: every Circuit prune depends on the joint state of all
        // succ vars (Hamiltonian-cycle reasoning is global). Union once at entry.
        val ant = state.composeIntVarAtomAntecedents(succ)
        // 1. Tighten each succ[i] to the domain [0, n). Structural; antecedent null.
        if (!tightenSuccToRange(state)) return false
        if (n == 1) {
            val v = succ[0]
            val d = state.intDomains[v]
            if (0 !in d) return false
            if (d.min != 0 && !state.tightenIntMin(v, 0)) return false
            if (d.max != 0 && !state.tightenIntMax(v, 0)) return false
            return true
        }
        // 2. Self-loop shaving (n ≥ 2): succ[i] != i.
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == i && d.min < d.max) {
                if (!state.tightenIntMin(v, d.min + 1, ant)) return false
            } else if (d.max == i && d.min < d.max) {
                if (!state.tightenIntMax(v, d.max - 1, ant)) return false
            } else if (d.min == d.max && d.min == i) {
                return false
            }
        }
        // 3. Collect singletons; flag pigeonhole violations.
        val pred = IntArray(n) { -1 } // pred[target] = pos whose singleton succ = target
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) {
                val target = d.min
                if (pred[target] != -1) return false // two singletons → same node → no Hamiltonian
                pred[target] = i
            }
        }
        // 4. Pigeonhole: shave singleton-taken values from non-singleton endpoints.
        if (!shaveClaimedFromEndpoints(state, pred, ant)) return false
        // 5. Cycle-detect on singletons: walk the singleton-successor graph from each
        //    unvisited node. If a closed cycle has length < n, it's a sub-cycle that
        //    can never be extended into a Hamiltonian — fail. This also catches
        //    "all-singletons assignments" that the search committed to.
        val visited = BooleanArray(n)
        // `posOnPath[node]` = its index in the current walk, or -1 if not on it. A dense marker
        // hoisted out of the loop: it both replaces the per-start `onPath` BooleanArray (one alloc
        // total, not one per start) and yields the cycle-start index in O(1), versus the old
        // `path.indexOf(cur)` linear scan. Only the touched nodes are reset between walks.
        val posOnPath = IntArray(n) { -1 }
        val path = IntArrayList()
        for (start in 0 until n) {
            if (visited[start]) continue
            path.clear()
            var cur = start
            while (cur in 0 until n && !visited[cur] && posOnPath[cur] < 0) {
                posOnPath[cur] = path.size
                path.add(cur)
                val sV = succ[cur]
                val sD = state.intDomains[sV]
                if (sD.min != sD.max) {
                    cur = -2
                    break
                } // not singleton; chain ends here
                cur = sD.min
            }
            if (cur in 0 until n && posOnPath[cur] >= 0) {
                val cycleLen = path.size - posOnPath[cur]
                if (cycleLen < n) return false
            }
            for (k in 0 until path.size) {
                visited[path[k]] = true
                posOnPath[path[k]] = -1
            }
        }
        // 6. Chain analysis: for each non-singleton, walk backward via pred[] to chain
        //    start; forbid (or force) chain start as successor.
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) continue
            var start = i
            var chainNodes = 1
            var cur = i
            while (true) {
                val prev = pred[cur]
                if (prev == -1) break
                if (prev == start) {
                    // Walked back to a node already on the current chain — singleton
                    // sub-cycle that doesn't include this non-singleton. Hard violation.
                    return false
                }
                start = prev
                chainNodes++
                cur = prev
                if (chainNodes > n) return false
            }
            if (chainNodes == n) {
                if (start !in d) return false
                if (!state.tightenIntMin(v, start, ant)) return false
                if (!state.tightenIntMax(v, start, ant)) return false
            } else {
                if (start == d.min && d.min < d.max) {
                    if (!state.tightenIntMin(v, d.min + 1, ant)) return false
                } else if (start == d.max && d.min < d.max) {
                    if (!state.tightenIntMax(v, d.max - 1, ant)) return false
                } else if (d.min == d.max && d.min == start) {
                    return false
                }
                // else: start is interior — contiguous IntDomain can't punch a hole.
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == 0) return
        // Single-var repair: try alternative successors at each node. Plus 2-var swap
        // moves that merge two cycles into one (high-leverage when the current state
        // has > 1 cycle and single-var moves can only shuffle them).
        for (i in succ.indices) {
            val v = succ[i]
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            val span = d.size
            if (span <= MAX_TARGETS) {
                d.forEach { target ->
                    if (target != cur && target != i) sink.addChannelingIntSet(state, v, target)
                }
            } else {
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                repeat(MAX_TARGETS) {
                    val target = d.valueAt(state.rng.nextInt(span))
                    if (target != cur && target != i) sink.addChannelingIntSet(state, v, target)
                }
            }
        }
        // 2-cycle merge: when the current configuration has multiple cycles, propose
        // edge swaps. For two edges (i, succ[i]) and (j, succ[j]) with i, j in different
        // cycles, the swap (i → succ[j]) and (j → succ[i]) merges them. Cap at a few
        // candidates; full enumeration is O(n²).
        proposeMergeSwaps(state, sink)
    }

    /** Walk current assignment, find nodes in different cycles, propose Compound swaps
     *  that merge their cycles. Capped at [MAX_SWAP_CANDIDATES] for sink size. */
    private fun proposeMergeSwaps(state: LocalSearchState, sink: MoveSink) {
        if (n < 3) return
        // Compute cycle id per node from the current assignment.
        val cycleOf = IntArray(n) { -1 }
        val unvisited = -1
        var cycleId = 0
        val effective = IntArray(n) { i ->
            val s = state.assignment.intValue(succ[i])
            if (s < 0 || s >= n || s == i) -1 else s
        }
        // `posOnPath[node]` = its index in the current walk, -1 if not on it. Replaces the
        // `pathBuf.contains(cur)` per-step O(n) scan (and the `indexOf`) with O(1) lookups; one
        // shared array reset only on touched nodes, instead of an IntArrayList per start.
        val posOnPath = IntArray(n) { -1 }
        val pathBuf = IntArrayList()
        for (start in 0 until n) {
            if (cycleOf[start] != unvisited) continue
            // Walk; detect cycle.
            pathBuf.clear()
            var cur = start
            while (cur >= 0 && cycleOf[cur] == unvisited && posOnPath[cur] < 0) {
                posOnPath[cur] = pathBuf.size
                pathBuf.add(cur)
                cur = effective[cur]
            }
            if (cur >= 0 && posOnPath[cur] >= 0) {
                val cycleStartIdx = posOnPath[cur]
                for (idx in cycleStartIdx until pathBuf.size) cycleOf[pathBuf[idx]] = cycleId
                cycleId++
            }
            // Nodes outside any cycle (in tails) stay at unvisited; ignored for swaps.
            for (k in 0 until pathBuf.size) posOnPath[pathBuf[k]] = -1
        }
        if (cycleId < 2) return // single cycle (or none) — no merge swaps.
        // Pick up to MAX_SWAP_CANDIDATES cross-cycle pairs.
        var swapsAdded = 0
        for (i in 0 until n) {
            if (swapsAdded >= MAX_SWAP_CANDIDATES) break
            if (cycleOf[i] < 0) continue
            for (j in i + 1 until n) {
                if (swapsAdded >= MAX_SWAP_CANDIDATES) break
                if (cycleOf[j] < 0 || cycleOf[j] == cycleOf[i]) continue
                // Domains must allow the swap.
                val si = effective[i]
                val sj = effective[j]
                if (si < 0 || sj < 0) continue
                val di = state.problem.intDomains[succ[i]]
                val dj = state.problem.intDomains[succ[j]]
                if (sj !in di.min..di.max || si !in dj.min..dj.max) continue
                sink.addCompound(listOf(Move.IntSet(succ[i], sj), Move.IntSet(succ[j], si)))
                swapsAdded++
            }
        }
    }

    private companion object {
        const val MAX_TARGETS: Int = 4
        const val MAX_SWAP_CANDIDATES: Int = 4
    }
}
