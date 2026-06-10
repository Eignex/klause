package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import kotlin.math.abs

/**
 * Hamiltonian-cycle constraint with optional exclusions. Like [Circuit], but `succ[i] = i`
 * (a self-loop) is permitted and reads "node `i` is not in the cycle". The included nodes
 * (those with `succ[i] != i`) must form a single closed cycle visiting every included node.
 *
 * Semantics:
 *  - `succ[i] = j ≠ i` → "j is the successor of i in the cycle".
 *  - `succ[i] = i` → "i is excluded".
 *  - Included nodes must form a single cycle; pointing to an excluded node is a violation;
 *    sub-cycles among included nodes are a violation.
 *  - All-excluded (every `succ[i]` = i) is valid as the empty subcircuit.
 *  - Exactly-one-included is invalid (a single node can't form a cycle without self-loop,
 *    which would mark it excluded — contradiction).
 *
 * LS cost is graded:
 *   `cost = |numCycles − 1|·(numIncluded > 0) + (numIncluded − nodesInCycles)
 *           + numPointToExcluded + numOob`
 * — multi-cycle is worse than single-cycle missing a couple of nodes; broken assignments
 * have a useful gradient.
 *
 * Propagation: bounds + pigeonhole on non-self-loop singletons. Stronger sub-cycle
 * reasoning is harder for Subcircuit because the included set is determined by the
 * assignment (a chain's "closing" is only forbidden if it doesn't capture every
 * non-excluded node, and "non-excluded" itself depends on other vars). Worklist-driven.
 */
class Subcircuit(
    /** Successor variable id per node; `succ[i] = i` excludes node i, the rest form one cycle. */
    val succ: IntArray,
) : Factor {

    init {
        require(succ.isNotEmpty()) { "Subcircuit needs at least one var, got ${succ.size}" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = succ

    private val n: Int = succ.size
    private val positionOfVar: Map<Int, Int> = succ.withIndex().associate { (i, v) -> v to i }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.intPayload[factorId] = computeCost(state, replaceAt = -1, replaceWith = 0)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    /** Graded violation: the [computeCost] distance to a valid sub-circuit — exposed as a
     *  magnitude (not a binary flag) so CBLS gets a descent gradient on routing structure. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = state.intPayload[factorId]

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = pos, replaceWith = newValue)
        return newCost - oldCost
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        if (positionOfVar[intVar] == null) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = -1, replaceWith = 0)
        state.intPayload[factorId] = newCost
        return newCost - oldCost
    }

    /**
     * Graded cost for the subcircuit. 0 iff included set forms a single cycle (or is empty).
     * O(n).
     */
    private fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Int {
        val effective = IntArray(n) { i ->
            if (i == replaceAt) replaceWith else state.assignment.intValue(succ[i])
        }
        var numOob = 0
        var numIncluded = 0
        var numPointToExcluded = 0
        // Classify each node, count included.
        val included = BooleanArray(n)
        for (i in 0 until n) {
            val s = effective[i]
            if (s < 0 || s >= n) {
                numOob++
                continue
            }
            if (s != i) {
                included[i] = true
                numIncluded++
            }
        }
        // Detect "successor points to excluded node".
        for (i in 0 until n) {
            if (!included[i]) continue
            val s = effective[i]
            if (s in 0 until n && !included[s] && effective[s] in 0 until n && effective[s] == s) {
                numPointToExcluded++
            }
        }
        if (numIncluded == 0) {
            // Empty subcircuit is valid; only oob counts as a violation.
            return numOob
        }
        // Cycle decomposition restricted to included nodes (use successor only when in
        // range, not self-loop, and successor is also included — otherwise dead-end).
        val unvisited = 0
        val onStack = 1
        val done = 2
        val markers = IntArray(n)
        val enterStep = IntArray(n)
        var globalStep = 0
        var numCycles = 0
        var nodesInCycles = 0
        for (start in 0 until n) {
            if (!included[start] || markers[start] != unvisited) continue
            var cur = start
            while (cur >= 0 && markers[cur] == unvisited && included[cur]) {
                markers[cur] = onStack
                enterStep[cur] = globalStep++
                val s = effective[cur]
                cur = if (s in 0 until n && s != cur && included[s]) s else -1
            }
            if (cur >= 0 && markers[cur] == onStack) {
                numCycles++
                nodesInCycles += globalStep - enterStep[cur]
            }
            for (i in 0 until n) if (markers[i] == onStack) markers[i] = done
        }
        return abs(numCycles - 1) + (numIncluded - nodesInCycles) + numPointToExcluded + numOob
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // 1. Tighten domains to [0, n).
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            val newLo = maxOf(d.min, 0)
            val newHi = minOf(d.max, n - 1)
            if (newLo > newHi) return false
            if (newLo != d.min && !state.tightenIntMin(v, newLo)) return false
            if (newHi != d.max && !state.tightenIntMax(v, newHi)) return false
        }
        if (n == 1) return true // single node: self-loop is the only choice, no constraint.
        // Every prune below is global (Hamiltonian-over-included reasoning depends on the joint
        // state of all succ vars), so union the antecedents once.
        val ant = state.composeIntVarAtomAntecedents(succ)
        // 2. Collect singletons. A fixed non-self successor (succ[i] = j ≠ i) claims target j — no
        //    other var may also point at j (one entry per node). A self-loop (succ[i] = i) excludes
        //    i and ALSO claims index i, because an excluded node has no predecessor in the cycle:
        //    nobody else may point at it. A second claim on any value — including a successor aimed
        //    at an excluded node — is a conflict.
        val claimed = IntArray(n) { -1 }
        val pred = IntArray(n) { -1 } // pred[target] = node whose fixed (non-self) successor is target
        for (i in succ.indices) {
            val d = state.intDomains[succ[i]]
            if (d.min != d.max) continue
            val target = d.min
            if (claimed[target] != -1) return false
            claimed[target] = i
            if (target != i) pred[target] = i
        }
        // 3. Shave every claimed value off the other vars' domain endpoints (pigeonhole + the
        //    excluded-target rule fold together: both forbid pointing at a claimed index).
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) continue
            var newMin = d.min
            while (newMin < d.max && claimed[newMin] != -1 && claimed[newMin] != i) newMin++
            var newMax = d.max
            while (newMax > newMin && claimed[newMax] != -1 && claimed[newMax] != i) newMax--
            if (newMin > newMax) return false
            if (newMin != d.min && !state.tightenIntMin(v, newMin, ant)) return false
            if (newMax != d.max && !state.tightenIntMax(v, newMax, ant)) return false
        }
        // 4. Count definitely-included nodes: a node whose domain excludes its own index can never
        //    self-loop, so it must lie on the cycle. Re-read domains — step 3 may have tightened.
        var includedCount = 0
        for (i in succ.indices) {
            val d = state.intDomains[succ[i]]
            if (i < d.min || i > d.max) includedCount++
        }
        // 5. Closed fixed-edge sub-cycle: walk the singleton-successor graph. A closed cycle of
        //    length L is a *complete* circuit over its L nodes; if more nodes are definitely
        //    included they can never join it, so the assignment is infeasible. Catches premature
        //    fully-pinned cycles, including two disjoint pinned cycles.
        val visited = BooleanArray(n)
        for (s in 0 until n) {
            if (visited[s]) continue
            val path = IntArrayList()
            val onPath = BooleanArray(n)
            var cur = s
            while (cur in 0 until n && !visited[cur] && !onPath[cur]) {
                val d = state.intDomains[succ[cur]]
                if (d.min != d.max || d.min == cur) break // non-singleton or self-loop ends the chain
                path.add(cur)
                onPath[cur] = true
                cur = d.min
            }
            if (cur in 0 until n && onPath[cur]) {
                val cycleLen = path.size - path.indexOf(cur)
                if (includedCount > cycleLen) return false
            }
            for (k in 0 until path.size) visited[path[k]] = true
        }
        // 6. Chain-walk forbid: for each open (non-singleton) node, follow its fixed-predecessor
        //    chain back to the start. Closing succ[i] onto that start would seal a cycle of
        //    `chainNodes` nodes; if more nodes are definitely included, sealing strands them, so
        //    forbid the start value at the relevant domain endpoint.
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) continue
            var start = i
            var chainNodes = 1
            var cur = i
            while (true) {
                val prev = pred[cur]
                if (prev == -1 || prev == start) break // chain start, or a fixed cycle (handled in 5)
                start = prev
                chainNodes++
                cur = prev
                if (chainNodes > n) break
            }
            // start == i means the chain is just i (closing it is a self-loop = exclusion, always
            // legal); only forbid when sealing a real cycle would strand included nodes.
            if (start != i && includedCount > chainNodes) {
                if (start == d.min && d.min < d.max) {
                    if (!state.tightenIntMin(v, d.min + 1, ant)) return false
                } else if (start == d.max && d.min < d.max) {
                    if (!state.tightenIntMax(v, d.max - 1, ant)) return false
                } else if (d.min == d.max && d.min == start) {
                    return false
                }
                // interior start: a contiguous IntDomain can't punch a hole — left for LS / search.
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == 0) return
        // Single-var repair: alternative successors + self-loop (exclude) option.
        for (i in succ.indices) {
            val v = succ[i]
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            // Self-loop as an option when not currently self-looping.
            if (i != cur && i in d) sink.addChannelingIntSet(state, v, i)
            val span = d.size
            if (span <= MAX_TARGETS) {
                d.forEach { target ->
                    if (target != cur) sink.addChannelingIntSet(state, v, target)
                }
            } else {
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                repeat(MAX_TARGETS) {
                    val target = d.valueAt(state.rng.nextInt(span))
                    if (target != cur) sink.addChannelingIntSet(state, v, target)
                }
            }
        }
    }

    private companion object {
        const val MAX_TARGETS: Int = 4
    }
}
