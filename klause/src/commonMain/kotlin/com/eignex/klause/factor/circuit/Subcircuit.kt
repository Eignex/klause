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
 * Hamiltonian-cycle constraint with optional exclusions. Like [Circuit], but `succ(i) = i`
 * (a self-loop) is permitted and reads "node `i` is not in the cycle". The included nodes
 * (those with `succ(i) != i`) must form a single closed cycle visiting every included node.
 *
 * Semantics:
 *  - `succ(i) = j ≠ i` → "j is the successor of i in the cycle".
 *  - `succ(i) = i` → "i is excluded".
 *  - Included nodes must form a single cycle; pointing to an excluded node is a violation;
 *    sub-cycles among included nodes are a violation.
 *  - All-excluded (every `succ(i)` = i) is valid as the empty subcircuit.
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
    /** Successor variable id per node; `succ(i) = i` excludes node i, the rest form one cycle. */
    succ: IntArray,
) : SuccessorCycleFactor(succ) {

    init {
        require(succ.isNotEmpty()) { "Subcircuit needs at least one var, got ${succ.size}" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Subcircuit(succ.remapVars(intMap))

    /** Position-faithful: `succ(i)` is node i's successor (`succ(i) = i` excludes node i), so the
     *  array order is meaningful — the key keeps the variables in order, not sorted (#443). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.SUBCIRCUIT) { ints(succ) }

    /**
     * Graded cost for the subcircuit. 0 iff included set forms a single cycle (or is empty).
     * O(n).
     */
    override fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Int {
        val effective = IntArray(n) { i ->
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
            if (s != i) {
                included[i] = true
                numIncluded++
            }
        }
        for (i in 0 until n) {
            if (!included[i]) continue
            val s = effective[i]
            if (s in 0 until n && !included[s] && effective[s] in 0 until n && effective[s] == s) {
                numPointToExcluded++
            }
        }
        if (numIncluded == 0) return numOob
        val next = IntArray(n) { i ->
            if (!included[i]) {
                -1
            } else {
                val s = effective[i]
                if (s in 0 until n && s != i && included[s]) s else -1
            }
        }
        val scan = cycleScan(next, n)
        return abs(scan.numCycles - 1) + (numIncluded - scan.nodesInCycles) + numPointToExcluded + numOob
    }

    override fun asPropagator(): Propagator = SubcircuitPropagator(succ, n)

    override fun asInvariant(): Invariant = SubcircuitInvariant(succ, n, ::computeCost)
}
