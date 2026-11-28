package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList

// Pinning `p` and observing a forced `q` proves `p -> q`. Presolve may rewrite with the graph; local search and
// LP use it only as a hint.
internal fun Problem.propagatedImplicationGraph(
    maxCandidates: Int,
    cancellation: ExecutionControl = ExecutionControl.Never,
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
    cancellation: ExecutionControl,
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
