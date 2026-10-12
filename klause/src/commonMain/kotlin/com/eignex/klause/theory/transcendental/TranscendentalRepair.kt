package com.eignex.klause.theory.transcendental

import com.eignex.klause.arithmetic.real.ExactRealArithmetic
import com.eignex.klause.arithmetic.real.ExactRealFunction
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.real.RealCertificationUnavailable
import com.eignex.klause.arithmetic.real.RealDomainInvalid
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.simplex.exact.BigFraction

internal fun repairTranscendentalCandidate(
    formula: TranscendentalFormula,
    coordinates: List<ExactRealValue>,
    bools: BooleanArray,
    arithmetic: ExactRealArithmetic,
): List<ExactRealValue> {
    val equalities = ArrayList<TranscendentalFormula.Atom>()
    val pending = ArrayDeque<TranscendentalFormula>()
    pending.addLast(formula)
    while (pending.isNotEmpty()) {
        arithmetic.transcendental.step()
        when (val node = pending.removeLast()) {
            is TranscendentalFormula.And -> pending.addAll(node.operands)
            is TranscendentalFormula.Atom -> if (node.relation == PolynomialRelation.EQ) equalities.add(node)
            else -> Unit
        }
    }
    val values = coordinates.toMutableList()
    repeat(coordinates.size + 1) {
        for (equality in equalities) {
            arithmetic.transcendental.step()
            for ((target, expression) in listOf(equality.left to equality.right, equality.right to equality.left)) {
                val variable = when (target) {
                    is TranscendentalExpression.Variable -> target
                    is TranscendentalExpression.Function -> target.argument as? TranscendentalExpression.Variable
                    else -> null
                } ?: continue
                if (references(expression, variable.id.index, arithmetic)) continue
                val proposal = try {
                    val desired = TranscendentalSourceCheck(arithmetic, values, bools).evaluate(expression)
                    if (target is TranscendentalExpression.Variable) desired
                    else inverse((target as TranscendentalExpression.Function).function, desired, arithmetic)
                } catch (_: RealDomainInvalid) {
                    null
                } catch (_: RealCertificationUnavailable) {
                    null
                }
                if (proposal != null) values[variable.id.index] = proposal
            }
        }
    }
    return values.toList()
}

private fun references(expression: TranscendentalExpression, id: Int, arithmetic: ExactRealArithmetic): Boolean {
    val pending = ArrayDeque<TranscendentalExpression>()
    pending.addLast(expression)
    while (pending.isNotEmpty()) {
        arithmetic.transcendental.step()
        when (val node = pending.removeLast()) {
            is TranscendentalExpression.Variable -> if (node.id.index == id) return true
            is TranscendentalExpression.Function -> pending.addLast(node.argument)
            is TranscendentalExpression.Sum -> pending.addAll(node.operands)
            is TranscendentalExpression.Product -> pending.addAll(node.operands)
            is TranscendentalExpression.Power -> pending.addLast(node.base)
            // Conditional dependencies can include their Boolean condition's arithmetic atoms.
            is TranscendentalExpression.Conditional -> return true
            else -> Unit
        }
    }
    return false
}

private fun inverse(
    function: TranscendentalFunction,
    desired: ExactRealValue,
    arithmetic: ExactRealArithmetic,
): ExactRealValue? = when (function) {
    TranscendentalFunction.EXP -> arithmetic.function(ExactRealFunction.LOG, desired)
    TranscendentalFunction.LOG -> arithmetic.function(ExactRealFunction.EXP, desired)
    TranscendentalFunction.SIN, TranscendentalFunction.COS -> {
        val rational = desired.rational
        val zero = BigFraction.ZERO
        val one = BigFraction.ONE
        val minusOne = BigFraction.MINUS_ONE
        val multiple = if (function == TranscendentalFunction.SIN) {
            when (rational) {
                zero -> zero
                one -> one * BigFraction.ofLong(2).reciprocal()
                minusOne -> minusOne * BigFraction.ofLong(2).reciprocal()
                else -> null
            }
        } else when (rational) {
            zero -> one * BigFraction.ofLong(2).reciprocal()
            one -> zero
            minusOne -> one
            else -> null
        }
        multiple?.let { arithmetic.multiply(listOf(arithmetic.rational(it), arithmetic.pi())) }
    }
}
