package com.eignex.klause.theory.transcendental

import com.eignex.klause.arithmetic.real.ExactRealArithmetic
import com.eignex.klause.arithmetic.real.ExactRealFunction
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.real.RealCertificationUnavailable
import com.eignex.klause.arithmetic.real.RealDomainInvalid
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.simplex.exact.BigFraction

internal enum class SourceTruth { TRUE, FALSE, UNKNOWN, DOMAIN_INVALID, DOMAIN_UNKNOWN }

internal class TranscendentalSourceCheck(
    private val arithmetic: ExactRealArithmetic,
    private val coordinates: List<ExactRealValue>,
    private val bools: BooleanArray,
) {
    private val expressions = HashMap<TranscendentalExpression, ExactRealValue>()
    private val formulas = HashMap<TranscendentalFormula, SourceTruth>()

    fun evaluate(expression: TranscendentalExpression): ExactRealValue {
        val pending = ArrayDeque<Pair<TranscendentalExpression, Boolean>>()
        pending.addLast(expression to false)
        while (pending.isNotEmpty()) {
            arithmetic.transcendental.step()
            val (node, combine) = pending.removeLast()
            if (node in expressions) continue
            val children = when (node) {
                is TranscendentalExpression.Function -> listOf(node.argument)
                is TranscendentalExpression.Sum -> node.operands
                is TranscendentalExpression.Product -> node.operands
                is TranscendentalExpression.Power -> listOf(node.base)
                is TranscendentalExpression.Conditional -> when (check(node.condition)) {
                    SourceTruth.TRUE -> listOf(node.whenTrue)
                    SourceTruth.FALSE -> listOf(node.whenFalse)
                    SourceTruth.DOMAIN_INVALID -> throw RealDomainInvalid()
                    SourceTruth.UNKNOWN, SourceTruth.DOMAIN_UNKNOWN -> throw RealCertificationUnavailable()
                }
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                pending.addLast(node to true)
                children.asReversed().forEach { pending.addLast(it to false) }
                continue
            }
            expressions[node] = when (node) {
                is TranscendentalExpression.Constant -> arithmetic.rational(
                    BigFraction.of(node.value.numerator, node.value.denominator),
                )
                is TranscendentalExpression.Variable -> coordinates[node.id.index]
                TranscendentalExpression.Pi -> arithmetic.pi()
                is TranscendentalExpression.Function -> arithmetic.function(
                    when (node.function) {
                        com.eignex.klause.ir.TranscendentalFunction.EXP -> ExactRealFunction.EXP
                        com.eignex.klause.ir.TranscendentalFunction.LOG -> ExactRealFunction.LOG
                        com.eignex.klause.ir.TranscendentalFunction.SIN -> ExactRealFunction.SIN
                        com.eignex.klause.ir.TranscendentalFunction.COS -> ExactRealFunction.COS
                    }, expressions.getValue(node.argument),
                )
                is TranscendentalExpression.Sum -> arithmetic.add(children.map(expressions::getValue))
                is TranscendentalExpression.Product -> arithmetic.multiply(children.map(expressions::getValue))
                is TranscendentalExpression.Power -> arithmetic.power(expressions.getValue(node.base), node.exponent)
                is TranscendentalExpression.Conditional -> expressions.getValue(children.single())
            }
        }
        return expressions.getValue(expression)
    }

    fun check(formula: TranscendentalFormula): SourceTruth {
        val pending = ArrayDeque<Pair<TranscendentalFormula, Boolean>>()
        pending.addLast(formula to false)
        while (pending.isNotEmpty()) {
            arithmetic.transcendental.step()
            val (node, combine) = pending.removeLast()
            if (node in formulas) continue
            val children = when (node) {
                is TranscendentalFormula.Not -> listOf(node.operand)
                is TranscendentalFormula.And -> node.operands
                is TranscendentalFormula.Or -> node.operands
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                pending.addLast(node to true)
                children.asReversed().forEach { pending.addLast(it to false) }
                continue
            }
            formulas[node] = when (node) {
                is TranscendentalFormula.Constant -> if (node.value) SourceTruth.TRUE else SourceTruth.FALSE
                is TranscendentalFormula.BooleanVariable -> {
                    if (bools[node.id.index]) SourceTruth.TRUE else SourceTruth.FALSE
                }
                is TranscendentalFormula.Atom -> atom(node)
                is TranscendentalFormula.Not -> when (formulas.getValue(node.operand)) {
                    SourceTruth.TRUE -> SourceTruth.FALSE
                    SourceTruth.FALSE -> SourceTruth.TRUE
                    SourceTruth.UNKNOWN -> SourceTruth.UNKNOWN
                    SourceTruth.DOMAIN_INVALID -> SourceTruth.DOMAIN_INVALID
                    SourceTruth.DOMAIN_UNKNOWN -> SourceTruth.DOMAIN_UNKNOWN
                }
                is TranscendentalFormula.And, is TranscendentalFormula.Or -> {
                    val truth = children.map(formulas::getValue)
                    when {
                        SourceTruth.DOMAIN_INVALID in truth -> SourceTruth.DOMAIN_INVALID
                        SourceTruth.DOMAIN_UNKNOWN in truth -> SourceTruth.DOMAIN_UNKNOWN
                        node is TranscendentalFormula.And && SourceTruth.FALSE in truth -> SourceTruth.FALSE
                        node is TranscendentalFormula.Or && SourceTruth.TRUE in truth -> SourceTruth.TRUE
                        SourceTruth.UNKNOWN in truth -> SourceTruth.UNKNOWN
                        node is TranscendentalFormula.And -> SourceTruth.TRUE
                        else -> SourceTruth.FALSE
                    }
                }
            }
        }
        return formulas.getValue(formula)
    }

    private fun atom(atom: TranscendentalFormula.Atom): SourceTruth = try {
        val comparison = arithmetic.compare(evaluate(atom.left), evaluate(atom.right))
        if (comparison == null) SourceTruth.UNKNOWN else {
            val holds = when (atom.relation) {
                PolynomialRelation.EQ -> comparison == 0
                PolynomialRelation.NE -> comparison != 0
                PolynomialRelation.LT -> comparison < 0
                PolynomialRelation.LE -> comparison <= 0
                PolynomialRelation.GT -> comparison > 0
                PolynomialRelation.GE -> comparison >= 0
            }
            if (holds) SourceTruth.TRUE else SourceTruth.FALSE
        }
    } catch (_: RealDomainInvalid) {
        SourceTruth.DOMAIN_INVALID
    } catch (_: RealCertificationUnavailable) {
        SourceTruth.DOMAIN_UNKNOWN
    }
}
