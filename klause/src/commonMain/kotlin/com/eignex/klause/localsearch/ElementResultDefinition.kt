package com.eignex.klause.localsearch

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.Cancellation
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

    fun reads(variable: Int, assignment: Assignment): Boolean {
        // Index writes can select a cell that was unselected before the move.
        if (variable == factor.idx) return true
        val index = assignment.intValue(factor.idx)
        if (index < factor.indexOffset || index >= factor.indexOffset.toLong() + factor.arr.size) return false
        return factor.arrIsVars && factor.arr[(index - factor.indexOffset).toInt()].toInt() == variable
    }

    fun compute(intOf: (Int) -> Long): Long {
        val index = intOf(factor.idx)
        if (index < factor.indexOffset || index >= factor.indexOffset.toLong() + factor.arr.size) {
            return SweepNode.NO_WRITE
        }
        val entry = factor.arr[(index - factor.indexOffset).toInt()]
        return if (factor.arrIsVars) intOf(entry.toInt()) else entry
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

internal fun elementResultDefinitions(
    problem: Problem,
    hints: IntArray,
    cancellation: Cancellation = Cancellation.Never,
): List<SweepNode> {
    if (cancellation()) return emptyList()
    val hinted = BooleanArray(problem.numIntVars)
    for (v in hints) {
        if (cancellation.definitionStopped()) return emptyList()
        if (v in hinted.indices) hinted[v] = true
    }
    val definitions = ArrayList<SweepNode>()
    for (factor in problem.factors) {
        if (cancellation.definitionStopped()) return emptyList()
        val element = factor as? Element ?: continue
        val output = element.result
        val bounds = problem.intBounds
        // NO_WRITE reserves Long.MIN_VALUE, so that output remains searched.
        if (!hinted[output] || !bounds.hasLower(output) || bounds.lower(output) == Long.MIN_VALUE) continue
        if (element.arrIsVars) {
            for (i in element.arr.indices) if (cancellation.definitionStopped()) return emptyList()
        }
        definitions += ElementResultDefinition(element)
    }
    return if (cancellation()) emptyList() else definitions
}
