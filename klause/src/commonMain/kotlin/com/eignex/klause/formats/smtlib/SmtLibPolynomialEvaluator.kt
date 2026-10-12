package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.and
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.constant
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.equivalent
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.negate
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.not
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.or
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.power
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.product
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.rational
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.relation
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.sum
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.signum
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLong

internal sealed interface PolynomialValue {
    data class Boolean(val formula: PolynomialRealFormula) : PolynomialValue {
        companion object {
            fun variable(index: Int): Boolean = Boolean(PolynomialRealFormula.BooleanVariable(SourceBoolId(index)))
        }
    }
    data class Real(val expression: PolynomialExpression, val integer: kotlin.Boolean = false) : PolynomialValue {
        companion object {
            fun variable(index: Int, integer: kotlin.Boolean = false): Real =
                Real(PolynomialExpression.Variable(SourceRealId(index)), integer)
        }
    }
}

internal fun PolynomialValue.boolean(): PolynomialRealFormula =
    (this as? PolynomialValue.Boolean)?.formula ?: smtUnsupported("polynomial Boolean operand expected")

private fun PolynomialValue.integral(): Boolean = (this as? PolynomialValue.Real)?.integer == true

private fun PolynomialValue.real(): PolynomialExpression =
    (this as? PolynomialValue.Real)?.expression ?: smtUnsupported("polynomial real operand expected")

internal class PolynomialBindings {
    private class Binding(val version: Int, val value: PolynomialValue?, val macro: PolynomialMacro?)

    // Append-only entries keep earlier visibility cutoffs valid without copying the table.
    private val bindings = HashMap<String, Binding>()

    fun contains(name: String): Boolean = name in bindings

    fun declare(name: String, value: PolynomialValue) {
        check(name !in bindings)
        bindings[name] = Binding(bindings.size, value, null)
    }

    fun define(name: String, macro: PolynomialMacro) {
        check(name !in bindings)
        bindings[name] = Binding(bindings.size, null, macro)
    }

    fun environment(): PolynomialEnvironment = PolynomialEnvironment(this, bindings.size)

    fun value(name: String, visibleBindings: Int): PolynomialValue? =
        bindings[name]?.takeIf { it.version < visibleBindings }?.value

    fun macro(name: String, visibleBindings: Int): PolynomialMacro? =
        bindings[name]?.takeIf { it.version < visibleBindings }?.macro
}

internal class PolynomialEnvironment(
    private val bindings: PolynomialBindings,
    private val visibleBindings: Int,
    private val locals: Map<String, PolynomialValue> = emptyMap(),
    private val parent: PolynomialEnvironment? = null,
) {
    fun value(name: String): PolynomialValue? {
        var scope: PolynomialEnvironment? = this
        while (scope != null) {
            scope.locals[name]?.let { return it }
            scope = scope.parent
        }
        return bindings.value(name, visibleBindings)
    }

    fun macro(name: String): PolynomialMacro? = bindings.macro(name, visibleBindings)

    fun withValues(values: Map<String, PolynomialValue>): PolynomialEnvironment =
        if (values.isEmpty()) this else PolynomialEnvironment(bindings, visibleBindings, values.toMap(), this)
}

internal data class PolynomialMacro(
    val parameters: List<String>,
    val parameterSorts: List<Sort>,
    val resultSort: Sort,
    val body: SExpr,
    val environment: PolynomialEnvironment,
)

private sealed interface PolynomialFrame {
    data class Evaluate(val expression: SExpr, val environment: PolynomialEnvironment) : PolynomialFrame
    data class Combine(val expression: SExpr.SList, val count: Int) : PolynomialFrame
    data class Let(
        val bindings: List<String>,
        val body: SExpr,
        val environment: PolynomialEnvironment,
    ) : PolynomialFrame
    data class Call(val macro: PolynomialMacro) : PolynomialFrame
    data class CheckSort(val sort: Sort) : PolynomialFrame
}

internal class SmtLibPolynomialEvaluator(
    private val lowering: PolynomialRealLowering,
    private val validateOnly: Boolean = false,
    private val cancellation: Cancellation = Cancellation.Never,
) {
    var hasNonlinearTerms = false
        private set

    fun evaluate(root: SExpr, environment: PolynomialEnvironment): PolynomialValue {
        val work = ArrayDeque<PolynomialFrame>()
        val values = ArrayDeque<PolynomialValue>()
        work.addLast(PolynomialFrame.Evaluate(root, environment))
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint()
            when (val frame = work.removeLast()) {
                is PolynomialFrame.Evaluate -> schedule(frame, work, values)
                is PolynomialFrame.Combine -> values.addLast(combine(frame.expression, pop(values, frame.count)))
                is PolynomialFrame.Let -> {
                    val bindings = frame.bindings.zip(pop(values, frame.bindings.size)).toMap()
                    work.addLast(
                        PolynomialFrame.Evaluate(
                            frame.body, frame.environment.withValues(bindings),
                        ),
                    )
                }
                is PolynomialFrame.Call -> {
                    val args = pop(values, frame.macro.parameters.size)
                    for (index in args.indices) checkSort(args[index], frame.macro.parameterSorts[index])
                    val bindings = frame.macro.parameters.indices.associate { index ->
                        frame.macro.parameters[index] to coerceSort(args[index], frame.macro.parameterSorts[index])
                    }
                    work.addLast(PolynomialFrame.CheckSort(frame.macro.resultSort))
                    work.addLast(
                        PolynomialFrame.Evaluate(
                            frame.macro.body,
                            frame.macro.environment.withValues(bindings),
                        ),
                    )
                }
                is PolynomialFrame.CheckSort -> {
                    val value = values.removeLast()
                    checkSort(value, frame.sort)
                    values.addLast(coerceSort(value, frame.sort))
                }
            }
        }
        return values.single()
    }

    private fun schedule(
        frame: PolynomialFrame.Evaluate,
        work: ArrayDeque<PolynomialFrame>,
        values: ArrayDeque<PolynomialValue>,
    ) {
        when (val node = frame.expression) {
            is SExpr.Atom -> {
                val literal = polynomialLiteral(node.text, cancellation)
                val bound = frame.environment.value(polynomialSymbol(node.text))
                val macro = frame.environment.macro(polynomialSymbol(node.text))
                when {
                    literal != null -> values.addLast(
                        PolynomialValue.Real(constant(literal), integer = '.' !in node.text),
                    )
                    bound != null -> values.addLast(bound)
                    macro != null && macro.parameters.isEmpty() -> {
                        work.addLast(PolynomialFrame.CheckSort(macro.resultSort))
                        work.addLast(PolynomialFrame.Evaluate(macro.body, macro.environment))
                    }
                    node.text == "true" || node.text == "false" -> values.addLast(
                        PolynomialValue.Boolean(PolynomialRealFormula.Constant(node.text == "true")),
                    )
                    else -> smtUnsupported("unknown polynomial symbol '${node.text}'")
                }
            }
            is SExpr.SList -> {
                val head = node.polynomialHead()
                val macro = if (frame.environment.value(head) != null) null else frame.environment.macro(head)
                when {
                    head == "let" -> scheduleLet(node, frame.environment, work)
                    macro != null -> {
                        if (node.items.size != macro.parameters.size + 1) {
                            smtUnsupported("polynomial macro '$head' arity")
                        }
                        work.addLast(PolynomialFrame.Call(macro))
                        for (argument in node.items.drop(1).asReversed()) {
                            work.addLast(PolynomialFrame.Evaluate(argument, frame.environment))
                        }
                    }
                    else -> {
                        validateOperator(node)
                        val args = node.items.drop(1)
                        work.addLast(PolynomialFrame.Combine(node, args.size))
                        for (argument in args.asReversed()) {
                            work.addLast(PolynomialFrame.Evaluate(argument, frame.environment))
                        }
                    }
                }
            }
        }
    }

    private fun scheduleLet(
        node: SExpr.SList,
        environment: PolynomialEnvironment,
        work: ArrayDeque<PolynomialFrame>,
    ) {
        node.polynomialSize(3)
        val list = node.items[1] as? SExpr.SList ?: smtUnsupported("malformed polynomial let bindings")
        val names = ArrayList<String>()
        val arguments = ArrayList<SExpr>()
        for (binding in list.items) {
            val pair = binding as? SExpr.SList ?: smtUnsupported("malformed polynomial let binding")
            if (pair.items.size != 2) smtUnsupported("malformed polynomial let binding")
            val name = pair.polynomialName(0)
            if (name in names) smtUnsupported("duplicate polynomial let binding '$name'")
            names.add(name)
            arguments.add(pair.items[1])
        }
        work.addLast(PolynomialFrame.Let(names, node.items[2], environment))
        for (argument in arguments.asReversed()) work.addLast(PolynomialFrame.Evaluate(argument, environment))
    }

    private fun combine(node: SExpr.SList, args: List<PolynomialValue>): PolynomialValue {
        return when (node.polynomialHead()) {
            "+" -> PolynomialValue.Real(sum(args.map { it.real() }, cancellation), args.all { it.integral() })
            "-" -> PolynomialValue.Real(
                if (args.size == 1) negate(args.single().real(), cancellation)
                else sum(
                    listOf(args.first().real()) + args.drop(1).map { negate(it.real(), cancellation) }, cancellation,
                ),
                args.all { it.integral() },
            )
            "*" -> {
                val terms = args.map { it.real() }
                if (terms.count { rational(it) == null } >= 2) hasNonlinearTerms = true
                PolynomialValue.Real(product(terms, cancellation), args.all { it.integral() })
            }
            "/" -> {
                var value = args.first().real()
                for (argument in args.drop(1)) {
                    val divisor = rational(argument.real())
                    if (divisor == null) {
                        if (validateOnly) continue
                        smtUnsupported("nonconstant polynomial divisor")
                    }
                    if (divisor.isZero) smtUnsupported("zero polynomial divisor")
                    value = product(listOf(value, constant(divisor.reciprocal())), cancellation)
                }
                PolynomialValue.Real(value)
            }
            "^" -> {
                val base = args[0].real()
                val value = rational(args[1].real())
                if (value == null) {
                    if (validateOnly) return PolynomialValue.Real(base, args[0].integral())
                    smtUnsupported("polynomial power requires a constant exponent")
                }
                if (value.den != BIG_ONE || value.num.signum() < 0 || value.num > bigIntOf(Int.MAX_VALUE)) {
                    smtUnsupported("polynomial power requires a nonnegative Int exponent")
                }
                val exponent = value.num.toLong().toInt()
                if (exponent >= 2 && rational(base) == null) hasNonlinearTerms = true
                PolynomialValue.Real(power(base, exponent, cancellation), args[0].integral())
            }
            "to_real" -> {
                if (!args.single().integral()) smtUnsupported("to_real requires an integer operand")
                PolynomialValue.Real(args.single().real())
            }
            "and" -> PolynomialValue.Boolean(and(args.map { it.boolean() }))
            "or" -> PolynomialValue.Boolean(or(args.map { it.boolean() }))
            "not" -> PolynomialValue.Boolean(not(args.single().boolean()))
            "xor" -> PolynomialValue.Boolean(
                args.map { it.boolean() }.reduce { left, right -> not(equivalent(left, right)) },
            )
            "=>" -> PolynomialValue.Boolean(
                args.map { it.boolean() }.reduceRight { left, right -> or(listOf(not(left), right)) },
            )
            "ite" -> {
                val condition = args[0].boolean()
                if (args[1] is PolynomialValue.Boolean) {
                    PolynomialValue.Boolean(
                        PolynomialRealLowering.conditional(condition, args[1].boolean(), args[2].boolean()),
                    )
                } else {
                    PolynomialValue.Real(
                        lowering.conditional(condition, args[1].real(), args[2].real()),
                        args[1].integral() && args[2].integral(),
                    )
                }
            }
            "=", "distinct", "<", "<=", ">", ">=" -> PolynomialValue.Boolean(comparison(node.polynomialHead(), args))
            else -> smtUnsupported("unsupported polynomial operator '${node.polynomialHead()}'")
        }
    }

    private fun comparison(head: String, args: List<PolynomialValue>): PolynomialRealFormula {
        val pairs = if (head == "distinct") {
            args.indices.flatMap { left -> (left + 1 until args.size).map { right -> left to right } }
        } else (0 until args.size - 1).map { it to it + 1 }
        val op = when (head) {
            "=" -> PolynomialRelation.EQ
            "distinct" -> PolynomialRelation.NE
            "<" -> PolynomialRelation.LT
            "<=" -> PolynomialRelation.LE
            ">" -> PolynomialRelation.GT
            ">=" -> PolynomialRelation.GE
            else -> error("unreachable")
        }
        return and(pairs.map { (left, right) ->
            if (args[left] is PolynomialValue.Boolean) {
                val equality = equivalent(args[left].boolean(), args[right].boolean())
                when (op) {
                    PolynomialRelation.EQ -> equality
                    PolynomialRelation.NE -> not(equality)
                    else -> smtUnsupported("ordered polynomial comparison requires real operands")
                }
            } else relation(args[left].real(), op, args[right].real())
        })
    }

    private fun validateOperator(node: SExpr.SList) {
        when (val head = node.polynomialHead()) {
            "not", "to_real" -> node.polynomialSize(2)
            "ite" -> node.polynomialSize(4)
            "^" -> node.polynomialSize(3)
            "+", "*", "and", "or" -> Unit
            "-" -> if (node.items.size < 2) smtUnsupported("polynomial subtraction requires an operand")
            "/", "xor", "=>", "=", "distinct", "<", "<=", ">", ">=" -> {
                if (node.items.size < 3) smtUnsupported("polynomial operator '$head' requires at least two operands")
            }
            else -> smtUnsupported("unsupported polynomial operator '$head'")
        }
    }

    private fun pop(values: ArrayDeque<PolynomialValue>, count: Int): List<PolynomialValue> =
        List(count) { values.removeLast() }.reversed()

    private fun coerceSort(value: PolynomialValue, sort: Sort): PolynomialValue =
        if (sort == Sort.REAL) PolynomialValue.Real(value.real()) else value

    private fun checkSort(value: PolynomialValue, sort: Sort) {
        when (sort) {
            Sort.BOOL -> value.boolean()
            Sort.REAL -> value.real()
            Sort.INT -> if (!value.integral()) smtUnsupported("polynomial integer operand expected")
        }
    }
}

private fun polynomialLiteral(text: String, cancellation: Cancellation): BigFraction? {
    val parts = text.split('.')
    if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { digit -> digit !in '0'..'9' } }) return null
    cancellation.polynomialSourceCheckpoint()
    val numerator = parseBigInt(parts.joinToString(""))
    cancellation.polynomialSourceCheckpoint()
    val denominator = if (parts.size == 1) BIG_ONE else {
        val scale = power(constant(BigFraction.ofLong(10)), parts[1].length, cancellation)
        rational(scale)!!.num
    }
    return BigFraction.of(numerator, denominator).also { cancellation.polynomialSourceCheckpoint() }
}
