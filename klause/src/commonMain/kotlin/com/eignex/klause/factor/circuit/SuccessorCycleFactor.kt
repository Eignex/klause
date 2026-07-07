package com.eignex.klause.factor.circuit

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.Factor
import com.eignex.klause.util.EmptyIntArray

/** Shared scaffolding for the successor-array cycle factors [Circuit] and [Subcircuit]: LS cost
 *  plumbing plus the domain-range / pigeonhole / cycle-scan pruning helpers. */
abstract class SuccessorCycleFactor(
    /** Successor variable id per node. */
    val succ: IntArray,
) : Factor {

    /** Number of nodes; equal to `succ.size`. */
    val n: Int = succ.size

    final override val boolVars: IntArray = EmptyIntArray
    final override val intVars: IntArray = succ

    protected abstract fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int
}
