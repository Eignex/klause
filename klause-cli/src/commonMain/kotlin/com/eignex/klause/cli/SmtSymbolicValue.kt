package com.eignex.klause.cli

import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue

internal fun smtSymbolicValue(value: ExactRealValue): String {
    val expressions = HashMap<ExactRealExpression, String>()
    val pending = ArrayDeque<Pair<ExactRealExpression, Boolean>>()
    pending.addLast(value.expression to false)
    while (pending.isNotEmpty()) {
        val (node, combine) = pending.removeLast()
        if (node in expressions) continue
        val children = when (node) {
            is ExactRealExpression.Function -> listOf(node.argument)
            is ExactRealExpression.Sum -> node.operands
            is ExactRealExpression.Product -> node.operands
            is ExactRealExpression.Power -> listOf(node.base)
            else -> emptyList()
        }
        if (!combine && children.isNotEmpty()) {
            pending.addLast(node to true)
            children.asReversed().forEach { pending.addLast(it to false) }
            continue
        }
        expressions[node] = when (node) {
            is ExactRealExpression.Algebraic -> smtAlgebraicValue(node.value)
            ExactRealExpression.Pi -> "((_ klause-real pi))"
            is ExactRealExpression.Function ->
                "((_ klause-real ${node.function.name.lowercase()}) ${expressions.getValue(node.argument)})"
            is ExactRealExpression.Sum -> "(+ ${children.joinToString(" ") { expressions.getValue(it) }})"
            is ExactRealExpression.Product -> "(* ${children.joinToString(" ") { expressions.getValue(it) }})"
            is ExactRealExpression.Power -> "(^ ${expressions.getValue(node.base)} ${node.exponent})"
        }
    }
    return expressions.getValue(value.expression)
}
