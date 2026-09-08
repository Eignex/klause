package com.eignex.klause.solver

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.appendDifferenceEdges
import com.eignex.klause.arithmetic.difference.appendNegatedDifferenceEdges
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows

/** Gather the immutable difference fragment represented by this core model data. */
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
            edges.add(DifferenceEdge(zero, variable, intBounds.upper(variable), domainBound = true))
        }
        if (intBounds.hasLower(variable) && intBounds.lower(variable) != Long.MIN_VALUE) {
            edges.add(DifferenceEdge(variable, zero, -intBounds.lower(variable), domainBound = true))
        }
    }
    return DifferenceFragment(edges)
}

/** Whether every integer row can be decided by the complete difference-theory route. */
internal fun hasCompleteDifferenceCoverage(factors: Array<Factor>): Boolean {
    val scratch = ArrayList<DifferenceEdge>(2)
    for (factor in factors) {
        if (factor.intVars.isEmpty()) continue
        scratch.clear()
        if (!appendFactorDifferenceEdges(factor, scratch) || scratch.isEmpty()) return false
    }
    return true
}

/** Whether the model is complete and numerically safe for the difference-theory route. */
internal fun supportsCompleteDifferenceTheory(factors: Array<Factor>, numIntVars: Int, intBounds: IntBounds): Boolean =
    hasCompleteDifferenceCoverage(factors) &&
        (differenceFragmentOf(factors, numIntVars, intBounds)?.carriesAPotential() ?: true)

private fun appendFactorDifferenceEdges(factor: Factor, edges: MutableList<DifferenceEdge>): Boolean {
    var complete = factor.linearForm is LinearForm.Conjunction
    for (row in factor.impliedLinearRows) {
        if (!row.isIntegerOnly || row.constants !is IntegerConstants || row.strict) {
            complete = false
            continue
        }
        val vars = IntArray(row.size) { Term.intVar(row.ref(it)) }
        val guard = if (row.activator == LinearRow.ALWAYS) DifferenceEdge.ALWAYS else Lit.make(row.activator, true)
        val forward = appendDifferenceEdges(
            vars,
            row::coeff,
            row.relation,
            row.bound,
            DifferenceFragment.ZERO,
            guard,
            edges,
        )
        val reverse = row.activator == LinearRow.ALWAYS || appendNegatedDifferenceEdges(
            vars,
            row::coeff,
            row.relation,
            row.bound,
            DifferenceFragment.ZERO,
            Lit.make(row.activator, false),
            edges,
        )
        complete = complete && forward && reverse
    }
    return complete
}
