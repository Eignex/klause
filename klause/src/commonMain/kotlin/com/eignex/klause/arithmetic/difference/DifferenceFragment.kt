package com.eignex.klause.arithmetic.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Factor

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

internal fun differenceFragmentOf(factors: Array<Factor>, numIntVars: Int, intBounds: IntBounds): DifferenceFragment? {
    val zero = DifferenceFragment.ZERO
    val edges = ArrayList<DifferenceEdge>()
    factors.forEach { f ->
        when (f) {
            is Linear ->
                f.integerConstants?.let { row ->
                    appendDifferenceEdges(f.vars, row::coeff, f.op, row.bound, zero, DifferenceEdge.ALWAYS, edges)
                }

            is ReifiedLinear ->
                // The aux is an equivalence, so both polarities constrain: the row under a true aux, its
                // integer negation under a false one. A wide row has no 64-bit reading, so its shape
                // cannot be read here at all.
                f.integerConstants?.let { row ->
                    appendDifferenceEdges(
                        f.vars,
                        row::coeff,
                        f.op,
                        row.bound,
                        zero,
                        Lit.make(f.auxBoolVar, true),
                        edges,
                    )
                    appendNegatedDifferenceEdges(
                        f.vars,
                        row::coeff,
                        f.op,
                        row.bound,
                        zero,
                        Lit.make(f.auxBoolVar, false),
                        edges,
                    )
                }

            else -> Unit
        }
    }
    if (edges.isEmpty()) return null
    val mentioned = HashSet<Int>()
    for (e in edges) {
        if (e.source != zero) mentioned.add(e.source)
        if (e.target != zero) mentioned.add(e.target)
    }
    for (v in mentioned.toIntArray().sortedArray()) {
        if (v >= numIntVars) continue
        if (intBounds.hasUpper(v)) edges.add(DifferenceEdge(zero, v, intBounds.upper(v), domainBound = true))
        if (intBounds.hasLower(v)) edges.add(DifferenceEdge(v, zero, -intBounds.lower(v), domainBound = true))
    }
    return DifferenceFragment(edges)
}

internal fun hasCompleteDifferenceCoverage(factors: Array<Factor>): Boolean {
    val scratch = ArrayList<DifferenceEdge>(2)
    for (factor in factors) {
        if (factor.intVars.isEmpty()) continue
        scratch.clear()
        when (factor) {
            is Linear -> {
                val row = factor.integerConstants ?: return false
                if (!appendDifferenceEdges(
                        factor.vars,
                        row::coeff,
                        factor.op,
                        row.bound,
                        DifferenceFragment.ZERO,
                        DifferenceEdge.ALWAYS,
                        scratch,
                    )
                ) {
                    return false
                }
            }

            is ReifiedLinear -> {
                val row = factor.integerConstants ?: return false
                if (!appendDifferenceEdges(
                        factor.vars,
                        row::coeff,
                        factor.op,
                        row.bound,
                        DifferenceFragment.ZERO,
                        Lit.make(factor.auxBoolVar, true),
                        scratch,
                    ) || !appendNegatedDifferenceEdges(
                        factor.vars,
                        row::coeff,
                        factor.op,
                        row.bound,
                        DifferenceFragment.ZERO,
                        Lit.make(factor.auxBoolVar, false),
                        scratch,
                    )
                ) {
                    return false
                }
            }

            else -> return false
        }
    }
    return true
}

internal fun supportsCompleteDifferenceTheory(factors: Array<Factor>, numIntVars: Int, intBounds: IntBounds): Boolean =
    hasCompleteDifferenceCoverage(factors) &&
        (differenceFragmentOf(factors, numIntVars, intBounds)?.carriesAPotential() ?: true)

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
