package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.symbolicRealValue
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.div
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.times
import kotlin.coroutines.cancellation.CancellationException

private fun ExecutionControl.smtValueCheckpoint() {
    charge(1)
    if (this()) throw CancellationException("SMT exact value rendering interrupted")
}

private fun smtSignedInteger(value: String): String = if (value.startsWith('-')) "(- ${value.drop(1)})" else value

/** Render an exact decimal or rational real coordinate as an SMT term. */
fun smtRealTerm(value: String): String {
    val negative = value.startsWith('-')
    val magnitude = if (negative) value.drop(1) else value
    val parts = magnitude.split('/')
    require(parts.size in 1..2 && parts.all { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }) {
        "invalid exact real value: $value"
    }
    val realParts = parts.map { if ('.' in it) it else "$it.0" }
    val term = if (realParts.size == 1) realParts[0] else "(/ ${realParts[0]} ${realParts[1]})"
    return if (negative) "(- $term)" else term
}

/** Render a certified algebraic real in exact SMT syntax. */
fun smtAlgebraicTerm(value: AlgebraicReal, cancellation: ExecutionControl = ExecutionControl.Never): String =
    value.rational?.let { smtRealTerm(it.toString()) }
        ?: smtRootTerm(value.definingCoefficients, value.rootIndex, cancellation)

/** Render a real root of a polynomial whose coefficients are in ascending exponent order. */
fun smtRootTerm(
    coefficients: List<BigFraction>,
    rootIndex: Int,
    cancellation: ExecutionControl = ExecutionControl.Never,
): String {
    require(rootIndex > 0)
    var denominator = BIG_ONE
    for (coefficient in coefficients) {
        cancellation.smtValueCheckpoint()
        denominator = denominator / denominator.gcd(coefficient.den) * coefficient.den
    }
    val terms = coefficients.mapIndexedNotNull { exponent, coefficient ->
        cancellation.smtValueCheckpoint()
        if (coefficient.isZero) return@mapIndexedNotNull null
        val integer = coefficient.num * (denominator / coefficient.den)
        val number = smtSignedInteger(integer.toString())
        when (exponent) {
            0 -> number
            1 -> "(* $number x)"
            else -> "(* $number (^ x $exponent))"
        }
    }
    require(terms.isNotEmpty())
    val polynomial = if (terms.size == 1) terms.single() else "(+ ${terms.joinToString(" ")})"
    return "(root-obj $polynomial $rootIndex)"
}

/** Render an exact expression without replacing symbolic authority with a decimal approximation. */
fun smtSymbolicTerm(value: ExactRealValue, cancellation: ExecutionControl = ExecutionControl.Never): String {
    val expressions = HashMap<ExactRealExpression, String>()
    val pending = ArrayDeque<Pair<ExactRealExpression, Boolean>>()
    pending.addLast(value.expression to false)
    while (pending.isNotEmpty()) {
        cancellation.smtValueCheckpoint()
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
            for (child in children.asReversed()) pending.addLast(child to false)
            continue
        }
        expressions[node] = when (node) {
            is ExactRealExpression.Algebraic -> smtAlgebraicTerm(node.value, cancellation)
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

/** Render one certified source real from any supported sample representation. */
fun Sample.smtRealValue(id: Int, cancellation: ExecutionControl = ExecutionControl.Never): String {
    cancellation.smtValueCheckpoint()
    return symbolicReals?.get(id)?.let { smtSymbolicTerm(it, cancellation) }
        ?: algebraicReals?.get(id)?.let { smtAlgebraicTerm(it, cancellation) }
        ?: smtRealTerm(requireNotNull(exactReals) { "SMT model has no certified real values" }[id].toString())
}

/** Render one source real through its rational, algebraic or symbolic authority. */
fun OpenTheoryAssignment.smtRealValue(id: Int, cancellation: ExecutionControl = ExecutionControl.Never): String {
    cancellation.smtValueCheckpoint()
    return when (this) {
        is OpenTheoryAssignment.Sampled -> sample.smtRealValue(id, cancellation)
        is OpenTheoryAssignment.Difference -> sample.smtRealValue(id, cancellation)
        is OpenTheoryAssignment.Rebuilt -> base.smtRealValue(id, cancellation)
        else -> symbolicRealValue(id)?.let { smtSymbolicTerm(it, cancellation) }
            ?: algebraicRealValue(id)?.let { smtAlgebraicTerm(it, cancellation) } ?: smtRealTerm(realValue(id))
    }
}

internal fun Sample.smtExactRealValue(id: Int, cancellation: ExecutionControl): ExactRealValue? {
    cancellation.smtValueCheckpoint()
    symbolicReals?.get(id)?.let { return it }
    algebraicReals?.get(id)?.let { return ExactRealValue(ExactRealExpression.Algebraic(it)) }
    return exactReals?.get(id)?.let { smtExactArithmetic(cancellation).rational(it) }
}

internal fun OpenTheoryAssignment.smtExactRealValue(id: Int, cancellation: ExecutionControl): ExactRealValue? {
    cancellation.smtValueCheckpoint()
    return when (this) {
        is OpenTheoryAssignment.Sampled -> sample.smtExactRealValue(id, cancellation)
        is OpenTheoryAssignment.Difference -> sample.smtExactRealValue(id, cancellation)
        is OpenTheoryAssignment.Rebuilt -> base.smtExactRealValue(id, cancellation)
        else -> symbolicRealValue(id)
            ?: algebraicRealValue(id)?.let { ExactRealValue(ExactRealExpression.Algebraic(it)) }
            ?: smtRationalValue(realValue(id), cancellation)
    }
}
