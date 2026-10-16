package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.Cancellation

internal fun unhintedDefinitionSweep(problem: Problem, cancellation: Cancellation): DefinitionalSweep? {
    val nodes = ArrayList<SweepNode>()
    var hasDivision = false
    var hasWideDivision = false
    for (factor in problem.factors) {
        cancellation.charge(1)
        if (cancellation()) return null
        val node = when (factor) {
            is Abs -> SweepNode.IntDef(FunctionalObjective.Abs(factor.result,
                FunctionalObjective.Operand.v(factor.operand)), intArrayOf(factor.operand))
            is Product -> SweepNode.IntDef(FunctionalObjective.Times(factor.result,
                FunctionalObjective.Operand.v(factor.a), FunctionalObjective.Operand.v(factor.b)),
                intArrayOf(factor.a, factor.b))
            is Power -> if (factor.base is Power.Coordinate.Integer) {
                val input = factor.base.id.index
                val output = (factor.result as Power.Coordinate.Integer).id.index
                SweepNode.IntDef(FunctionalObjective.Power(output, FunctionalObjective.Operand.v(input),
                    factor.exponent), intArrayOf(input))
            } else null
            is DivMod -> {
                hasDivision = true
                null
            }
            is WideDivMod -> {
                hasWideDivision = true
                null
            }
            else -> null
        }
        if (node != null) nodes += node
    }
    if (hasDivision) nodes += divModDefinitions(problem, cancellation)
    if (cancellation()) return null
    if (hasWideDivision) nodes += wideDivModDefinitions(problem, cancellation)
    for (node in nodes) {
        cancellation.charge(5L + 3L * (node.intInputs.size + node.boolInputs.size))
        if (cancellation()) return null
    }
    val ordered = orderedDefinitions(problem, nodes, cancellation)
    return if (cancellation() || ordered.isEmpty()) null else DefinitionalSweep(ordered)
}
