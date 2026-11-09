package com.eignex.klause.arithmetic.difference

import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.collections.IntHashSet

internal class DifferenceFragment(val edges: List<DifferenceEdge>) {
    val nodes: IntArray = run {
        val seen = IntHashSet()
        for (e in edges) {
            if (e.source != ZERO) seen.add(e.source)
            if (e.target != ZERO) seen.add(e.target)
        }
        seen.toIntArray().also { it.sort() }
    }

    val numNodes: Int get() = nodes.size + 1

    val zeroNode: Int get() = nodes.size

    fun nodeOf(endpoint: Int): Int =
        if (endpoint == ZERO) zeroNode else nodes.binarySearchInt(endpoint).coerceAtLeast(-1)

    fun carriesAPotential(): Boolean = numNodes > 0 && edges.none { it.exceedsPotentialBound() }

    /**
     * Discard only declared-range edges too wide for the incremental graph's `Long` potentials.
     *
     * Those edges express a finite CP domain that remains enforced by its owning propagator. Omitting
     * them from this redundant graph only weakens its deductions; every retained guarded refutation is
     * still implied by the source model. A non-domain edge outside the range cannot be dropped because
     * it is a model row, so no usable fragment exists in that case.
     */
    fun withoutOverHeavyDomainEdges(): DifferenceFragment? {
        val retained = edges.filterNot { it.domainBound && it.exceedsPotentialBound() }
        if (retained.size == edges.size) return takeIf(DifferenceFragment::carriesAPotential)
        return DifferenceFragment(retained).takeIf(DifferenceFragment::carriesAPotential)
    }

    private fun potentialBoundLimit(): Long = Long.MAX_VALUE / (8L * (numNodes + 1).toLong())

    private fun DifferenceEdge.exceedsPotentialBound(): Boolean {
        val limit = potentialBoundLimit()
        return bound < -limit || bound > limit
    }

    fun graph(): DifferenceGraph {
        val g = DifferenceGraph(numNodes)
        for (e in edges) g.addEdge(nodeOf(e.source), nodeOf(e.target), e.bound)
        return g
    }

    internal companion object {
        const val ZERO: Int = -1
    }
}

/** One value per integer column witnessing feasibility, or an infeasible/abandoned outcome. */
internal fun DifferenceFragment.potentialSample(
    numIntVars: Int,
    bools: BooleanArray,
    cancelled: () -> Boolean = { false },
): Potentials {
    val active = BooleanArray(edges.size)
    for (edge in edges.indices) {
        val guard = edges[edge].guard
        if (guard != DifferenceEdge.ALWAYS && bools[Lit.variable(guard)] != Lit.isPositive(guard)) continue
        active[edge] = true
    }
    val potential = when (val outcome = graph().potentials(active, cancelled)) {
        is Potentials.Found -> outcome.values
        else -> return outcome
    }
    val zero = potential[zeroNode]
    return Potentials.Found(
        LongArray(numIntVars).also { values ->
            for (node in nodes.indices) values[nodes[node]] = potential[node] - zero
        },
    )
}
