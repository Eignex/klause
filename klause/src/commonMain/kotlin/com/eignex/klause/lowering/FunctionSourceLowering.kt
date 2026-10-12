package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.util.Cancellation

internal class FunctionSourceLowering(
    private val numeric: PolynomialRealLowering,
    private val originalNumericCount: Int,
    private val originalBoolCount: Int,
    private val cancellation: Cancellation = Cancellation.Never,
) {
    private sealed interface Definition {
        data class Function(val function: TranscendentalFunction, val argument: PolynomialExpression) : Definition
        data class Conditional(val value: PolynomialConditional) : Definition
        data object Pi : Definition
    }
    private class BooleanConditional(
        val condition: PolynomialRealFormula,
        val whenTrue: PolynomialRealFormula,
        val whenFalse: PolynomialRealFormula,
    )
    private val definitions = HashMap<Int, Definition>()
    private val booleanDefinitions = HashMap<Int, BooleanConditional>()
    val boolCount: Int get() = originalBoolCount + booleanDefinitions.size
    val hasFunctions: Boolean get() = definitions.values.any { it is Definition.Function || it === Definition.Pi }

    fun function(function: TranscendentalFunction, argument: PolynomialExpression): PolynomialExpression {
        cancellation.polynomialSourceCheckpoint(1)
        val variable = numeric.opaqueReal()
        definitions[variable.id.index] = Definition.Function(function, argument)
        return variable
    }

    fun pi(): PolynomialExpression {
        cancellation.polynomialSourceCheckpoint(1)
        val variable = numeric.opaqueReal()
        definitions[variable.id.index] = Definition.Pi
        return variable
    }

    fun capture(conditional: PolynomialConditional) {
        definitions[conditional.coordinate] = Definition.Conditional(conditional)
    }

    fun conditional(
        condition: PolynomialRealFormula,
        whenTrue: PolynomialRealFormula,
        whenFalse: PolynomialRealFormula,
    ): PolynomialRealFormula {
        if (condition is PolynomialRealFormula.Constant) return if (condition.value) whenTrue else whenFalse
        cancellation.polynomialSourceCheckpoint(1)
        val id = boolCount
        if (id == Int.MAX_VALUE) throw PolynomialSourceInterrupted()
        booleanDefinitions[id] = BooleanConditional(condition, whenTrue, whenFalse)
        return PolynomialRealFormula.BooleanVariable(SourceBoolId(id))
    }

    fun formula(source: PolynomialRealFormula): TranscendentalFormula {
        val expressions = HashMap<PolynomialExpression, TranscendentalExpression>()
        val formulas = HashMap<PolynomialRealFormula, TranscendentalFormula>()
        val pending = ArrayDeque<Any>()
        pending.addLast(source)
        while (pending.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val node = pending.last()
            if (node is PolynomialExpression && node in expressions ||
                node is PolynomialRealFormula && node in formulas) {
                pending.removeLast()
                continue
            }
            val children = children(node)
            val missing = children.filter { child ->
                when (child) {
                    is PolynomialExpression -> child !in expressions
                    is PolynomialRealFormula -> child !in formulas
                    else -> error("invalid source node")
                }
            }
            if (missing.isNotEmpty()) {
                missing.asReversed().forEach(pending::addLast)
                continue
            }
            pending.removeLast()
            when (node) {
                is PolynomialExpression -> expressions[node] = expression(node, expressions, formulas)
                is PolynomialRealFormula -> formulas[node] = boolean(node, expressions, formulas)
            }
        }
        return formulas.getValue(source)
    }

    fun expression(source: PolynomialExpression): TranscendentalExpression {
        val result = formula(PolynomialRealFormula.Atom(source, com.eignex.klause.ir.PolynomialRelation.EQ,
            PolynomialExpression.Constant.of(com.eignex.klause.util.BIG_ZERO)))
        return (result as TranscendentalFormula.Atom).left
    }

    private fun children(node: Any): List<Any> = when (node) {
        is PolynomialExpression.Variable -> when (val definition = definitions[node.id.index]) {
            is Definition.Function -> listOf(definition.argument)
            is Definition.Conditional -> listOf(
                definition.value.condition, definition.value.whenTrue, definition.value.whenFalse,
            )
            else -> emptyList()
        }
        is PolynomialExpression.Sum -> node.operands
        is PolynomialExpression.Product -> node.operands
        is PolynomialExpression.Power -> listOf(node.base)
        is PolynomialRealFormula.BooleanVariable -> booleanDefinitions[node.id.index]?.let {
            listOf(it.condition, it.whenTrue, it.whenFalse)
        } ?: emptyList()
        is PolynomialRealFormula.Atom -> listOf(node.left, node.right)
        is PolynomialRealFormula.Not -> listOf(node.operand)
        is PolynomialRealFormula.And -> node.operands
        is PolynomialRealFormula.Or -> node.operands
        else -> emptyList()
    }

    private fun expression(
        node: PolynomialExpression,
        expressions: Map<PolynomialExpression, TranscendentalExpression>,
        formulas: Map<PolynomialRealFormula, TranscendentalFormula>,
    ): TranscendentalExpression = when (node) {
        is PolynomialExpression.Constant -> TranscendentalExpression.Constant(node)
        is PolynomialExpression.Variable -> when (val definition = definitions[node.id.index]) {
            is Definition.Function -> TranscendentalExpression.Function(
                definition.function, expressions.getValue(definition.argument),
            )
            is Definition.Conditional -> TranscendentalExpression.Conditional(
                formulas.getValue(definition.value.condition),
                expressions.getValue(definition.value.whenTrue), expressions.getValue(definition.value.whenFalse),
            )
            Definition.Pi -> TranscendentalExpression.Pi
            null -> {
                require(node.id.index < originalNumericCount) { "missing original function source coordinate" }
                TranscendentalExpression.Variable(node.id)
            }
        }
        is PolynomialExpression.Sum -> TranscendentalExpression.Sum(node.operands.map(expressions::getValue))
        is PolynomialExpression.Product -> TranscendentalExpression.Product(node.operands.map(expressions::getValue))
        is PolynomialExpression.Power -> TranscendentalExpression.Power(expressions.getValue(node.base), node.exponent)
    }

    private fun boolean(
        node: PolynomialRealFormula,
        expressions: Map<PolynomialExpression, TranscendentalExpression>,
        formulas: Map<PolynomialRealFormula, TranscendentalFormula>,
    ): TranscendentalFormula = when (node) {
        is PolynomialRealFormula.Constant -> TranscendentalFormula.Constant(node.value)
        is PolynomialRealFormula.BooleanVariable -> booleanDefinitions[node.id.index]?.let {
            TranscendentalFormula.Conditional(
                formulas.getValue(it.condition), formulas.getValue(it.whenTrue), formulas.getValue(it.whenFalse),
            )
        } ?: run {
            require(node.id.index < originalBoolCount) { "missing original function Boolean coordinate" }
            TranscendentalFormula.BooleanVariable(node.id)
        }
        is PolynomialRealFormula.Atom -> TranscendentalFormula.Atom(
            expressions.getValue(node.left), node.relation, expressions.getValue(node.right),
        )
        is PolynomialRealFormula.Not -> TranscendentalFormula.Not(formulas.getValue(node.operand))
        is PolynomialRealFormula.And -> TranscendentalFormula.And(node.operands.map(formulas::getValue))
        is PolynomialRealFormula.Or -> TranscendentalFormula.Or(node.operands.map(formulas::getValue))
    }
}
