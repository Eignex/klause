package com.eignex.klause.arithmetic.difference

import com.eignex.klause.ir.Lit

internal class DifferenceFragment(val edges: List<DifferenceEdge>) {
    val nodes: IntArray = run {
        val seen = HashSet<Int>()
        for (e in edges) {
            if (e.source != ZERO) seen.add(e.source)
            if (e.target != ZERO) seen.add(e.target)
        }
        seen.toIntArray().sortedArray()
    }

    val numNodes: Int get() = nodes.size + 1

    val zeroNode: Int get() = nodes.size

    fun nodeOf(endpoint: Int): Int = if (endpoint == ZERO) zeroNode else indexOfSorted(nodes, endpoint)

    fun carriesAPotential(): Boolean {
        var maxAbs = 0L
        for (e in edges) {
            val a = if (e.bound < 0L) -e.bound else e.bound
            if (a > maxAbs) maxAbs = a
        }
        return numNodes > 0 && maxAbs <= Long.MAX_VALUE / (8L * (numNodes + 1).toLong())
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

internal fun indexOfSorted(sorted: IntArray, value: Int): Int {
    var lo = 0
    var hi = sorted.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = sorted[mid]
        when {
            v < value -> lo = mid + 1
            v > value -> hi = mid - 1
            else -> return mid
        }
    }
    return -1
}

/** One value per integer column witnessing feasibility, or why none was produced. */
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
