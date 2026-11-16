package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.ScanMeter

internal fun unhintedDefinitionSweep(problem: Problem, cancellation: ExecutionControl): DefinitionalSweep? {
    return ScanMeter(cancellation).use { scan ->
        if (!scan.check()) return@use null
    val nodes = ArrayList<SweepNode>()
    var hasDivision = false
    var hasWideDivision = false
    for (factor in problem.factors) {
        if (!scan.visit(1L)) return@use null
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
            } else
                    null
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
    if (!scan.check()) return@use null
        if (hasDivision) nodes += divModDefinitions(problem, cancellation)
    if (!scan.check()) return@use null
    if (hasWideDivision) nodes += wideDivModDefinitions(problem, cancellation)
    for (node in nodes) {
        if (!scan.visit(5L + 3L * (node.intInputs.size + node.boolInputs.size))) return@use null
        }
        if (!scan.check()) return@use null
    val ordered = orderedDefinitions(problem, nodes, cancellation)
        if (!scan.check() || ordered.isEmpty()) null else DefinitionalSweep(ordered)
}
}
