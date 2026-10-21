package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

/**
 * Harvest a literal-indexed implication graph with bounded propagation probes.
 *
 * Pinning literal `p` and observing a forced literal `q` proves `p -> q`. The graph is read-only
 * engine information: presolve may use it for rewrites, while local search and LP use it as a hint.
 */
internal fun Problem.propagatedImplicationGraph(
    maxCandidates: Int,
    cancellation: Cancellation = Cancellation.Never,
): Array<IntArray> {
    val adjacency = Array(2 * numBoolVars) { IntArrayList() }
    for (variable in freeBooleanProbeCandidates(maxCandidates, cancellation)) {
        if (cancellation()) break
        recordImplications(variable, value = true, adjacency, cancellation)
        recordImplications(variable, value = false, adjacency, cancellation)
    }
    return Array(adjacency.size) { adjacency[it].toIntArray() }
}

private fun Problem.recordImplications(
    variable: Int,
    value: Boolean,
    adjacency: Array<IntArrayList>,
    cancellation: Cancellation,
) {
    if (cancellation()) return
    val implied = propagate(Assumptions.None.withBool(variable, value), cancellation)
    if (implied !is PropagationResult.Implied) return
    val from = Lit.make(variable, value)
    for (k in implied.boolKeys.indices) {
        cancellation.charge(10L)
        if (cancellation()) return
        val other = implied.boolKeys[k]
        if (other != variable) adjacency[from].add(Lit.make(other, implied.boolValues[k]))
    }
}
