package com.eignex.klause.localsearch

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.EmptyIntArray

internal class ElementResultDefinition(val factor: Element) : SweepNode {
    override val out: Int get() = factor.result
    override val outIsBool: Boolean get() = false
    override val boolInputs: IntArray get() = EmptyIntArray
    override val intInputs: IntArray = if (factor.arrIsVars) {
        intArrayOf(factor.idx) + IntArray(factor.arr.size) { factor.arr[it].toInt() }
    } else {
        intArrayOf(factor.idx)
    }

    override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
        val index = assignment.intValue(factor.idx)
        if (index < factor.indexOffset || index >= factor.indexOffset.toLong() + factor.arr.size) {
            return SweepNode.NO_WRITE
        }
        val entry = factor.arr[(index - factor.indexOffset).toInt()]
        val value = if (factor.arrIsVars) assignment.intValue(entry.toInt()) else entry
        return domains[out].clamp(value)
    }
}

internal fun elementResultDefinitions(problem: Problem, hints: IntArray): List<SweepNode> {
    val hinted = BooleanArray(problem.numIntVars)
    for (v in hints) if (v in hinted.indices) hinted[v] = true
    return problem.factors.mapNotNull { factor ->
        val element = factor as? Element ?: return@mapNotNull null
        val output = element.result
        val bounds = problem.intBounds
        // NO_WRITE reserves Long.MIN_VALUE, so that output remains searched.
        if (!hinted[output] || !bounds.hasLower(output) || bounds.lower(output) == Long.MIN_VALUE) null else {
            ElementResultDefinition(element)
        }
    }
}
