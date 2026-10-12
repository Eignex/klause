package com.eignex.klause.arithmetic.real

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.transcendental.RationalEnclosure
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.simplex.exact.BigFraction

internal class RealCertificationUnavailable : RuntimeException()
internal class RealDomainInvalid : RuntimeException()

internal class ExactRealArithmetic(
    val transcendental: TranscendentalArithmetic,
    val algebraic: AlgebraicArithmetic,
) {
    fun rational(value: BigFraction): ExactRealValue = scalar(algebraic.rational(value))
    fun scalar(value: AlgebraicReal): ExactRealValue = ExactRealValue(ExactRealExpression.Algebraic(value))
    fun pi(): ExactRealValue = ExactRealValue(ExactRealExpression.Pi)

    fun function(function: ExactRealFunction, argument: ExactRealValue): ExactRealValue {
        transcendental.step()
        val node = argument.expression
        if (function == ExactRealFunction.LOG) {
            if (node is ExactRealExpression.Function && node.function == ExactRealFunction.EXP) {
                return ExactRealValue(node.argument)
            }
            val positive = compare(argument, rational(BigFraction.ZERO)) ?: throw RealCertificationUnavailable()
            if (positive <= 0) throw RealDomainInvalid()
            if (argument.rational == BigFraction.ONE) return rational(BigFraction.ZERO)
        }
        if (function == ExactRealFunction.EXP && node is ExactRealExpression.Function &&
            node.function == ExactRealFunction.LOG
        ) return ExactRealValue(node.argument)
        if (argument.rational?.isZero == true) {
            return rational(if (function == ExactRealFunction.SIN) BigFraction.ZERO else BigFraction.ONE)
        }
        if (function == ExactRealFunction.SIN || function == ExactRealFunction.COS) {
            piMultiple(node)?.let { multiplier ->
                val twice = transcendental.multiply(multiplier, BigFraction.ofLong(2))
                if (twice.den == BigFraction.ONE.den) {
                    val quadrant = twice.num.toString().let { integer ->
                        // The residue stays small even for arbitrary-width period counts.
                        var residue = 0
                        for (digit in integer.removePrefix("-")) {
                            transcendental.step()
                            residue = (10 * residue + (digit - '0')) % 4
                        }
                        if (integer.startsWith("-")) (4 - residue) % 4 else residue
                    }
                    val value = if (function == ExactRealFunction.SIN) {
                        when (quadrant) { 1 -> 1L; 3 -> -1L; else -> 0L }
                    } else when (quadrant) { 0 -> 1L; 2 -> -1L; else -> 0L }
                    return rational(BigFraction.ofLong(value))
                }
            }
        }
        return ExactRealValue(ExactRealExpression.Function(function, node))
    }

    fun add(values: List<ExactRealValue>): ExactRealValue {
        val operands = ArrayList<ExactRealExpression>()
        var constant = algebraic.rational(BigFraction.ZERO)
        for (value in values) {
            transcendental.step()
            val nodes = (value.expression as? ExactRealExpression.Sum)?.operands ?: listOf(value.expression)
            for (node in nodes) {
                if (node is ExactRealExpression.Algebraic) constant = algebraic.add(constant, node.value)
                else operands.add(node)
            }
        }
        var index = 0
        while (index < operands.size) {
            transcendental.step()
            val first = signed(operands[index])
            val opposite = (index + 1 until operands.size).firstOrNull { other ->
                val second = signed(operands[other])
                first.first + second.first == BigFraction.ZERO && same(first.second, second.second)
            }
            if (opposite != null) {
                operands.removeAt(opposite)
                operands.removeAt(index)
            } else index++
        }
        index = 0
        while (index < operands.size) {
            val first = trigSquare(operands[index])
            val mate = if (first == null) null else (index + 1 until operands.size).firstOrNull { other ->
                val second = trigSquare(operands[other])
                second != null && first.function != second.function && same(first.argument, second.argument)
            }
            if (mate != null) {
                operands.removeAt(mate)
                operands.removeAt(index)
                constant = algebraic.add(constant, algebraic.rational(BigFraction.ONE))
            } else index++
        }
        if (algebraic.sign(constant) != 0 || operands.isEmpty()) operands.add(ExactRealExpression.Algebraic(constant))
        return ExactRealValue(if (operands.size == 1) operands.single() else ExactRealExpression.Sum(operands))
    }

    fun multiply(values: List<ExactRealValue>): ExactRealValue {
        val operands = ArrayList<ExactRealExpression>()
        var constant = algebraic.rational(BigFraction.ONE)
        for (value in values) {
            transcendental.step()
            val nodes = (value.expression as? ExactRealExpression.Product)?.operands ?: listOf(value.expression)
            for (node in nodes) {
                if (node is ExactRealExpression.Algebraic) constant = algebraic.multiply(constant, node.value)
                else operands.add(node)
            }
        }
        if (algebraic.sign(constant) == 0) return scalar(constant)
        if (algebraic.compare(constant, algebraic.rational(BigFraction.ONE)) != 0 || operands.isEmpty()) {
            operands.add(0, ExactRealExpression.Algebraic(constant))
        }
        return ExactRealValue(if (operands.size == 1) operands.single() else ExactRealExpression.Product(operands))
    }

    fun power(value: ExactRealValue, exponent: Int): ExactRealValue {
        require(exponent >= 0)
        transcendental.step()
        if (exponent == 0) return rational(BigFraction.ONE)
        if (exponent == 1) return value
        value.algebraic?.let { return scalar(algebraic.power(it, exponent)) }
        return ExactRealValue(ExactRealExpression.Power(value.expression, exponent))
    }

    fun negate(value: ExactRealValue): ExactRealValue = multiply(listOf(rational(BigFraction.MINUS_ONE), value))

    fun compare(left: ExactRealValue, right: ExactRealValue, maximumTerms: Int = 32): Int? {
        transcendental.step()
        if (same(left.expression, right.expression)) return 0
        if (left.algebraic != null && right.algebraic != null) {
            return algebraic.compare(requireNotNull(left.algebraic), requireNotNull(right.algebraic))
        }
        val first = left.expression as? ExactRealExpression.Function
        val second = right.expression as? ExactRealExpression.Function
        if (first != null && second != null && first.function == second.function &&
            first.function in setOf(ExactRealFunction.EXP, ExactRealFunction.LOG)
        ) return compare(ExactRealValue(first.argument), ExactRealValue(second.argument), maximumTerms)
        if (first?.function == ExactRealFunction.EXP && right.rational?.signum()?.let { it <= 0 } == true) return 1
        if (second?.function == ExactRealFunction.EXP && left.rational?.signum()?.let { it <= 0 } == true) return -1
        val difference = add(listOf(left, negate(right)))
        difference.algebraic?.let { return algebraic.sign(it) }
        var terms = 4
        while (terms <= maximumTerms) {
            val interval = try {
                enclose(difference, terms)
            } catch (_: RealCertificationUnavailable) {
                null
            }
            if (interval != null) {
                if (interval.lower.signum() > 0) return 1
                if (interval.upper.signum() < 0) return -1
                if (interval.isPoint && interval.lower.isZero) return 0
            }
            if (terms > maximumTerms / 2) break
            terms *= 2
        }
        return null
    }

    fun enclose(value: ExactRealValue, terms: Int): RationalEnclosure {
        val computed = HashMap<ExactRealExpression, RationalEnclosure>()
        val work = ArrayDeque<Pair<ExactRealExpression, Boolean>>()
        work.addLast(value.expression to false)
        while (work.isNotEmpty()) {
            transcendental.step()
            val (node, combine) = work.removeLast()
            if (node in computed) continue
            val children = children(node)
            if (!combine && children.isNotEmpty()) {
                work.addLast(node to true)
                children.asReversed().forEach { work.addLast(it to false) }
                continue
            }
            computed[node] = when (node) {
                is ExactRealExpression.Algebraic -> {
                    var refined = node.value
                    repeat(terms) { if (refined.rational == null) refined = algebraic.refine(refined) }
                    RationalEnclosure(refined.lowerBound, refined.upperBound)
                }
                ExactRealExpression.Pi -> transcendental.pi(terms)
                is ExactRealExpression.Function -> {
                    val argument = computed.getValue(node.argument)
                    when (node.function) {
                        ExactRealFunction.EXP -> transcendental.exp(argument, terms)
                        ExactRealFunction.LOG -> {
                            if (argument.lower.signum() <= 0) throw RealCertificationUnavailable()
                            transcendental.log(argument, terms)
                        }
                        ExactRealFunction.SIN -> transcendental.sin(argument, terms)
                        ExactRealFunction.COS -> transcendental.cos(argument, terms)
                    }
                }
                is ExactRealExpression.Sum -> children.fold(RationalEnclosure.point(BigFraction.ZERO)) { sum, child ->
                    transcendental.add(sum, computed.getValue(child))
                }
                is ExactRealExpression.Product -> children.fold(
                    RationalEnclosure.point(BigFraction.ONE),
                ) { product, child ->
                    transcendental.multiply(product, computed.getValue(child))
                }
                is ExactRealExpression.Power -> transcendental.power(computed.getValue(node.base), node.exponent)
            }
        }
        return computed.getValue(value.expression)
    }

    private fun children(value: ExactRealExpression): List<ExactRealExpression> = when (value) {
        is ExactRealExpression.Function -> listOf(value.argument)
        is ExactRealExpression.Sum -> value.operands
        is ExactRealExpression.Product -> value.operands
        is ExactRealExpression.Power -> listOf(value.base)
        else -> emptyList()
    }

    private fun same(left: ExactRealExpression, right: ExactRealExpression): Boolean {
        val pending = ArrayDeque<Pair<ExactRealExpression, ExactRealExpression>>()
        pending.addLast(left to right)
        while (pending.isNotEmpty()) {
            transcendental.step()
            val (first, second) = pending.removeLast()
            if (first === second) continue
            when (first) {
                is ExactRealExpression.Algebraic -> {
                    if (second !is ExactRealExpression.Algebraic || algebraic.compare(first.value, second.value) != 0) {
                        return false
                    }
                }
                ExactRealExpression.Pi -> if (second !== ExactRealExpression.Pi) return false
                is ExactRealExpression.Function -> {
                    if (second !is ExactRealExpression.Function || first.function != second.function) return false
                }
                is ExactRealExpression.Sum -> if (second !is ExactRealExpression.Sum) return false
                is ExactRealExpression.Product -> if (second !is ExactRealExpression.Product) return false
                is ExactRealExpression.Power -> {
                    if (second !is ExactRealExpression.Power || first.exponent != second.exponent) return false
                }
            }
            val firstChildren = children(first)
            val secondChildren = children(second)
            if (firstChildren.size != secondChildren.size) return false
            firstChildren.indices.forEach { pending.addLast(firstChildren[it] to secondChildren[it]) }
        }
        return true
    }

    private fun signed(node: ExactRealExpression): Pair<BigFraction, ExactRealExpression> {
        if (node is ExactRealExpression.Product && node.operands.size == 2) {
            val coefficient = (node.operands[0] as? ExactRealExpression.Algebraic)?.value?.rational
            if (coefficient != null) return coefficient to node.operands[1]
        }
        return BigFraction.ONE to node
    }

    private fun trigSquare(node: ExactRealExpression): ExactRealExpression.Function? {
        if (node !is ExactRealExpression.Power || node.exponent != 2) return null
        val function = node.base as? ExactRealExpression.Function ?: return null
        return function.takeIf { it.function in setOf(ExactRealFunction.SIN, ExactRealFunction.COS) }
    }

    private fun piMultiple(node: ExactRealExpression): BigFraction? {
        if (node === ExactRealExpression.Pi) return BigFraction.ONE
        if (node is ExactRealExpression.Product && node.operands.size == 2 &&
            node.operands[1] === ExactRealExpression.Pi
        ) return (node.operands[0] as? ExactRealExpression.Algebraic)?.value?.rational
        return null
    }
}
