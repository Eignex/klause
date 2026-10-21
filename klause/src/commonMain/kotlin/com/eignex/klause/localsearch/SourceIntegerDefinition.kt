package com.eignex.klause.localsearch

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.objective.CapturedIntegerDefinition
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun sourceIntegerDefinition(problem: Problem, hint: DefinitionHint.Integer): SweepNode? {
    val inputs = when (hint) {
        is DefinitionHint.Abs -> arrayOf(hint.input)
        is DefinitionHint.Extreme -> hint.inputs.takeIf { it.isNotEmpty() } ?: return null
        is DefinitionHint.Product -> arrayOf(hint.left, hint.right)
        is DefinitionHint.Power -> if (hint.exponent < 0) return null else arrayOf(hint.base)
        is DefinitionHint.Plus -> arrayOf(hint.left, hint.right)
        is DefinitionHint.Linear -> hint.inputs
        else -> return null
    }
    val variables = inputs.mapNotNull { it.variableOrNull() }
    if (variables.any { it !in 0 until problem.numIntVars }) return null
    val outputSlot = if (hint is DefinitionHint.Linear) {
        if (hint.coefficients.size != inputs.size) return null
        inputs.indices.singleOrNull { inputs[it].variableOrNull() == hint.output } ?: return null
    } else -1
    if (hint is DefinitionHint.Linear && hint.coefficients[outputSlot] == 0L) return null
    val operands = inputs.map { it.nativeOperand() }.toTypedArray()
    val node = if (wordArithmeticPermitted(problem, hint, inputs, outputSlot)) {
        when (hint) {
            is DefinitionHint.Abs -> FunctionalObjective.Abs(hint.output, operands[0])
            is DefinitionHint.Extreme -> FunctionalObjective.Extreme(hint.output, operands, hint.maximum)
            is DefinitionHint.Product -> FunctionalObjective.Times(hint.output, operands[0], operands[1])
            is DefinitionHint.Power -> FunctionalObjective.Power(hint.output, operands[0], hint.exponent)
            is DefinitionHint.Plus -> FunctionalObjective.Plus(hint.output, operands[0], operands[1])
            is DefinitionHint.Linear -> {
                val selected = inputs.indices.filter { it != outputSlot }
                FunctionalObjective.Lin(
                    hint.output, hint.coefficients[outputSlot],
                    LongArray(selected.size) { hint.coefficients[selected[it]] },
                    Array(selected.size) { operands[selected[it]] }, hint.bound,
                )
            }
        }
    } else CapturedIntegerDefinition(hint, operands, outputSlot)
    val reads = inputs.indices.filter { it != outputSlot }.mapNotNull { inputs[it].variableOrNull() }
    return SweepNode.IntDef(node, reads.distinct().toIntArray())
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun wordArithmeticPermitted(
    problem: Problem,
    hint: DefinitionHint.Integer,
    inputs: Array<DefinitionHint.Operand>,
    outputSlot: Int,
): Boolean {
    fun bounds(input: DefinitionHint.Operand): Pair<Long, Long>? = when (input) {
        is DefinitionHint.Operand.Constant -> input.value to input.value
        is DefinitionHint.Operand.Variable -> problem.intBounds.let {
            if (it.hasLower(input.id) && it.hasUpper(input.id)) it.lower(input.id) to it.upper(input.id) else null
        }
    }
    return try {
        when (hint) {
            is DefinitionHint.Abs -> bounds(inputs[0])?.first?.let { it != Long.MIN_VALUE } ?: false
            is DefinitionHint.Extreme, is DefinitionHint.Power -> true
            is DefinitionHint.Product -> {
                val a = bounds(inputs[0]) ?: return false
                val b = bounds(inputs[1]) ?: return false
                mulExact(a.first, b.first)
                mulExact(a.first, b.second)
                mulExact(a.second, b.first)
                mulExact(a.second, b.second)
                true
            }
            is DefinitionHint.Plus -> {
                val a = bounds(inputs[0]) ?: return false
                val b = bounds(inputs[1]) ?: return false
                addExact(a.first, b.first)
                addExact(a.second, b.second)
                true
            }
            is DefinitionHint.Linear -> {
                var lo = 0L
                var hi = 0L
                for (k in inputs.indices) {
                    if (k == outputSlot) continue
                    val range = bounds(inputs[k]) ?: return false
                    val a = mulExact(hint.coefficients[k], range.first)
                    val b = mulExact(hint.coefficients[k], range.second)
                    lo = addExact(lo, minOf(a, b))
                    hi = addExact(hi, maxOf(a, b))
                }
                val lower = subExact(hint.bound, hi)
                subExact(hint.bound, lo)
                hint.coefficients[outputSlot] != -1L || lower != Long.MIN_VALUE
            }
            else -> false
        }
    } catch (_: CheckedLongOverflowException) {
        false
    }
}

