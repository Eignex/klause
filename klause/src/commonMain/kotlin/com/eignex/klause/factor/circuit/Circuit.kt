package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.circuit.internals.cycleScan
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
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
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.CIRCUIT) { ints(succ) }

    /**
     * Graded cost: `|numCycles − 1| + (n − nodesInCycles) + numSelfLoops + numOutOfBounds`.
     * Returns 0 iff the assignment (with optional override `succ[replaceAt] = replaceWith`)
     * is a single Hamiltonian cycle of length `n`. O(n).
     */
    override fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int {
        if (n == 1) {
            val v = if (replaceAt == 0) replaceWith else state.assignment.intValue(succ[0])
            return if (v == 0L) 0 else 1
        }
        val next = IntArray(n)
        var numSelfLoops = 0
        var numOob = 0
        for (i in 0 until n) {
            val s = if (i == replaceAt) replaceWith else state.assignment.intValue(succ[i])
            if (s < 0 || s >= n) {
                next[i] = -1
                numOob++
            } else if (s == i.toLong()) {
                next[i] = -1
                numSelfLoops++
            } else {
                next[i] = s.toInt()
            }
        }
        val scan = cycleScan(next, n)
        return abs(scan.numCycles - 1) + (n - scan.nodesInCycles) + numSelfLoops + numOob
    }

    override fun asPropagator(): Propagator = CircuitPropagator(succ, n)

    override fun asInvariant(): Invariant = CircuitInvariant(succ, n, ::computeCost)
}
