package com.eignex.klause.lowering

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.appendDifferenceEdges
import com.eignex.klause.arithmetic.difference.appendNegatedDifferenceEdges
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Factor

internal fun differenceFragmentOf(factors: Array<Factor>, numIntVars: Int, intBounds: IntBounds): DifferenceFragment? {
    val zero = DifferenceFragment.ZERO
    val edges = ArrayList<DifferenceEdge>()
    factors.forEach { factor -> appendFactorDifferenceEdges(factor, edges) }
    if (edges.isEmpty()) return null
    val mentioned = HashSet<Int>()
    for (edge in edges) {
        if (edge.source != zero) mentioned.add(edge.source)
        if (edge.target != zero) mentioned.add(edge.target)
    }
    for (variable in mentioned.toIntArray().sortedArray()) {
        if (variable >= numIntVars) continue
        if (intBounds.hasUpper(variable)) {
            edges.add(
                DifferenceEdge(zero, variable, intBounds.upper(variable), domainBound = true),
            )
        }
        if (intBounds.hasLower(variable)) {
            edges.add(DifferenceEdge(variable, zero, -intBounds.lower(variable), domainBound = true))
        }
    }
    return DifferenceFragment(edges)
}

internal fun hasCompleteDifferenceCoverage(factors: Array<Factor>): Boolean {
    val scratch = ArrayList<DifferenceEdge>(2)
    for (factor in factors) {
        if (factor.intVars.isEmpty()) continue
        scratch.clear()
        if (!appendFactorDifferenceEdges(factor, scratch) || scratch.isEmpty()) {
            return false
        }
    }
    return true
}

internal fun supportsCompleteDifferenceTheory(factors: Array<Factor>, numIntVars: Int, intBounds: IntBounds): Boolean =
    hasCompleteDifferenceCoverage(factors) &&
        (differenceFragmentOf(factors, numIntVars, intBounds)?.carriesAPotential() ?: true)

private fun appendFactorDifferenceEdges(factor: Factor, edges: MutableList<DifferenceEdge>): Boolean = when (factor) {
    is Linear -> factor.integerConstants?.let { row ->
        appendDifferenceEdges(
            factor.vars,
            row::coeff,
            factor.op,
            row.bound,
            DifferenceFragment.ZERO,
            DifferenceEdge.ALWAYS,
            edges,
        )
    } ?: false

    is ReifiedLinear -> factor.integerConstants?.let { row ->
        appendDifferenceEdges(
            factor.vars,
            row::coeff,
            factor.op,
            row.bound,
            DifferenceFragment.ZERO,
            Lit.make(factor.auxBoolVar, true),
            edges,
        ) && appendNegatedDifferenceEdges(
            factor.vars,
            row::coeff,
            factor.op,
            row.bound,
            DifferenceFragment.ZERO,
            Lit.make(factor.auxBoolVar, false),
            edges,
        )
    } ?: false

    else -> true
}
