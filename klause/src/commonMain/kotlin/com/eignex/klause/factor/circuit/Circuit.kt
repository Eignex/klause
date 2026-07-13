package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.circuit.internals.cycleScan
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyIntArray
import kotlin.math.abs

/**
 * Successor-array single-cycle constraint over `n` nodes: `succ(i)` holds the index of node `i`'s
 * successor. Two modes selected by [subcircuit]:
 *
 *  - **Circuit** (`subcircuit = false`): a Hamiltonian cycle — following `succ` from any node visits
 *    every node and returns to the start after exactly `n` steps. Self-loops (`succ(i) = i`) are
 *    violations for `n ≥ 2`; sub-cycles are violations.
 *  - **Subcircuit** (`subcircuit = true`): `succ(i) = i` reads "node `i` is excluded"; the included
 *    nodes (those with `succ(i) ≠ i`) must form a single closed cycle. All-excluded is the valid empty
 *    subcircuit; pointing to an excluded node, or a sub-cycle among included nodes, is a violation.
 *
 * `succ(i) = j` reads "node `j` is the successor of node `i`"; each `succ(i)` must hold a value in
 * `[0, n)` (out-of-range counts as a violation). LS cost is graded ("how far off single-cycle") so
 * strategies see a gradient rather than a broken/satisfied bit.
 *
 * Propagation and the LP relaxation are mode-specific and dispatched by [subcircuit]: the propagator is
 * a [CircuitPropagator] (Hamiltonian bounds / pigeonhole / sub-cycle prevention) or a
 * [SubcircuitPropagator] (self-loop-aware reachability); the invariant likewise.
 */
class Circuit(
    /** Successor variable id per node; the assignment must form one (sub)circuit over `succ`. */
    val succ: IntArray,
    /** When true, `succ(i) = i` excludes node `i` and only the included nodes must form the cycle. */
    val subcircuit: Boolean = false,
) : Factor {

    /** Number of nodes; equal to `succ.size`. */
    val n: Int = succ.size

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = succ

    init {
        require(succ.isNotEmpty()) { "Circuit needs at least one var, got ${succ.size}" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Circuit(succ.remapVars(intMap), subcircuit)

    /** Position-faithful: `succ(i)` is node i's successor, so the array order is meaningful — the key
     *  keeps the variables in order rather than sorting them (#443). The circuit vs subcircuit mode is
     *  kept in the [FactorKind] so the two never share a structural-key bucket. */
    override fun structuralKey(): StructuralKey = materializeKey(kind(), ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(kind(), boolMap, intMap, ::buildKey)

    private fun kind(): FactorKind = if (subcircuit) FactorKind.SUBCIRCUIT else FactorKind.CIRCUIT

    private fun buildKey(sink: KeySink) = sink.intVars(succ)

    /** Graded cost, dispatched by mode; 0 iff the assignment forms the required single (sub)cycle. */
    private fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int =
        if (subcircuit) subcircuitCost(state, replaceAt, replaceWith) else circuitCost(state, replaceAt, replaceWith)

    /**
     * Hamiltonian cost: `|numCycles − 1| + (n − nodesInCycles) + numSelfLoops + numOutOfBounds`.
     * Returns 0 iff the assignment (with optional override `succ[replaceAt] = replaceWith`) is a single
     * Hamiltonian cycle of length `n`. O(n).
     */
    private fun circuitCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int {
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

    /** Subcircuit cost: 0 iff the included set (non-self-loop nodes) forms a single cycle (or is empty). O(n). */
    private fun subcircuitCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int {
        val effective = LongArray(n) { i ->
            if (i == replaceAt) replaceWith else state.assignment.intValue(succ[i])
        }
        var numOob = 0
        var numIncluded = 0
        var numPointToExcluded = 0
        val included = BooleanArray(n)
        for (i in 0 until n) {
            val s = effective[i]
            if (s < 0 || s >= n) {
                numOob++
                continue
            }
            if (s != i.toLong()) {
                included[i] = true
                numIncluded++
            }
        }
        for (i in 0 until n) {
            if (!included[i]) continue
            val s = effective[i]
            if (s in 0 until n && !included[s.toInt()] && effective[s.toInt()] in 0 until n &&
                effective[s.toInt()] == s
            ) {
                numPointToExcluded++
            }
        }
        if (numIncluded == 0) return numOob
        val next = IntArray(n) { i ->
            if (!included[i]) {
                -1
            } else {
                val s = effective[i]
                if (s in 0 until n && s != i.toLong() && included[s.toInt()]) s.toInt() else -1
            }
        }
        val scan = cycleScan(next, n)
        return abs(scan.numCycles - 1) + (numIncluded - scan.nodesInCycles) + numPointToExcluded + numOob
    }

    override fun asPropagator(): Propagator =
        if (subcircuit) SubcircuitPropagator(succ, n) else CircuitPropagator(succ, n)

    override fun asInvariant(): Invariant =
        if (subcircuit) SubcircuitInvariant(succ, n, ::computeCost) else CircuitInvariant(succ, n, ::computeCost)
}
