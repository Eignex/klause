package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
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
 *  - All-excluded (every succ[i] = i) is valid as the empty subcircuit.
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
class Subcircuit(val succ: IntArray) : LocalSearchFactor {

    init { require(succ.isNotEmpty()) { "Subcircuit needs at least one var, got ${succ.size}" } }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = succ

    private val n: Int = succ.size
    private val positionOfVar: Map<Int, Int> = succ.withIndex().associate { (i, v) -> v to i }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.intPayload[factorId] = computeCost(state, replaceAt = -1, replaceWith = 0)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.intPayload[factorId] > 0

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = pos, replaceWith = newValue)
        return (if (newCost > 0) 1 else 0) - (if (oldCost > 0) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        if (positionOfVar[intVar] == null) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = -1, replaceWith = 0)
        state.intPayload[factorId] = newCost
        return (if (newCost > 0) 1 else 0) - (if (oldCost > 0) 1 else 0)
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
        val UNVISITED = 0; val ON_STACK = 1; val DONE = 2
        val markers = IntArray(n)
        val enterStep = IntArray(n)
        var globalStep = 0
        var numCycles = 0
        var nodesInCycles = 0
        for (start in 0 until n) {
            if (!included[start] || markers[start] != UNVISITED) continue
            var cur = start
            while (cur >= 0 && markers[cur] == UNVISITED && included[cur]) {
                markers[cur] = ON_STACK
                enterStep[cur] = globalStep++
                val s = effective[cur]
                cur = if (s in 0 until n && s != cur && included[s]) s else -1
            }
            if (cur >= 0 && markers[cur] == ON_STACK) {
                numCycles++
                nodesInCycles += globalStep - enterStep[cur]
            }
            for (i in 0 until n) if (markers[i] == ON_STACK) markers[i] = DONE
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
        // 2. Pigeonhole on non-self-loop singletons: if succ[i] is fixed to j (j ≠ i),
        //    then no other var can take j as its successor (the cycle has one entry per
        //    node). Self-loop singletons (succ[i] = i, i.e. excluded) don't take a value
        //    from other vars' domains.
        val taker = IntArray(n) { -1 }
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) {
                val target = d.min
                if (target == i) continue // self-loop → excluded; doesn't claim a target
                if (taker[target] != -1) return false // two non-self singletons → same target
                taker[target] = i
            }
        }
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) continue
            var newMin = d.min
            while (newMin < d.max && taker[newMin] != -1 && taker[newMin] != i) newMin++
            var newMax = d.max
            while (newMax > newMin && taker[newMax] != -1 && taker[newMax] != i) newMax--
            if (newMin > newMax) return false
            val ant = state.composeIntVarAntecedents(succ)
            if (newMin != d.min && !state.tightenIntMin(v, newMin, ant)) return false
            if (newMax != d.max && !state.tightenIntMax(v, newMax, ant)) return false
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
            if (i != cur && i in d) sink.addIntSet(v, i)
            val span = d.size
            if (span <= MAX_TARGETS) {
                d.forEach { target ->
                    if (target != cur) sink.addIntSet(v, target)
                }
            } else {
                if (cur < d.max) sink.addIntSet(v, cur + 1)
                if (cur > d.min) sink.addIntSet(v, cur - 1)
                repeat(MAX_TARGETS) {
                    val target = d.valueAt(state.rng.nextInt(span))
                    if (target != cur) sink.addIntSet(v, target)
                }
            }
        }
    }

    private companion object {
        const val MAX_TARGETS: Int = 4
    }
}
