package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

internal fun reduceSmtArithmetic(input: SmtReduction, cancellation: Cancellation): SmtReduction =
    SmtArithmeticReducer(input, cancellation).reduce()

private class SmtArithmeticReducer(private val input: SmtReduction, private val cancellation: Cancellation) {
    private data class Division(val numerator: SExpr, val denominator: SExpr, val quotient: SExpr,
        val remainder: SExpr?)
    private data class Key(val operator: String, val arguments: List<Int>)
    private val used = HashSet<String>()
    private val declarations = ArrayList<SExpr>()
    private val obligations = ArrayList<SExpr>()
    private val cache = HashMap<Key, SExpr>()
    private val nodeIds = HashMap<Key, Int>()
    private val atomIds = HashMap<String, Int>()
    private var nextNode = 0
    private val integerDivisions = ArrayList<Division>()
    private val realDivisions = ArrayList<Division>()
    private val sourceChecks = ArrayList<SmtSourceCheck>()
    private val integerChecks = ArrayList<List<String>>()
    private val realChecks = ArrayList<List<String>>()
    private val prelude = ArrayList<SExpr>()
    private var exponentialName: String? = null
    private var logarithmName: String? = null
    private var identity = 0

    fun reduce(): SmtReduction {
        val work = ArrayDeque<SExpr>()
        input.commands.forEach(work::addLast)
        while (work.isNotEmpty()) {
            step()
            when (val node = work.removeLast()) {
                is SExpr.Atom -> used += polynomialSymbol(node.text)
                is SExpr.SList -> node.items.forEach(work::addLast)
            }
        }
        val commands = input.commands.map { command ->
            step()
            val list = command as? SExpr.SList ?: smtUnsupported("expected SMT command")
            when (list.polynomialHead()) {
                "assert", "minimize", "maximize" -> form(list.polynomialHead(), rewrite(list.items[1]))
                "check-sat-assuming", "get-value" -> form(list.polynomialHead(),
                    SExpr.SList((list.items[1] as SExpr.SList).items.map(::rewrite)))
                "define-fun" -> smtUnsupported("arithmetic reduction requires expanded definitions")
                else -> command
            }
        }.toMutableList()
        if (declarations.isEmpty() && obligations.isEmpty()) return input.copy(commands = commands)
        // Definitions can depend on later private coordinates, so all scalar declarations precede formulas.
        val insertion = commands.indexOfFirst { it is SExpr.SList && it.polynomialHead() in setOf(
            "assert", "minimize", "maximize", "check-sat", "check-sat-assuming", "get-model", "get-value", "exit") }
            .let { if (it < 0) commands.size else it }
        commands.addAll(insertion, declarations)
        if (prelude.isNotEmpty()) {
            val start = commands.indexOfFirst { it is SExpr.SList &&
                it.polynomialHead() !in setOf("set-logic", "set-info", "set-option") }
                .let { if (it < 0) commands.size else it }
            commands.addAll(start, prelude)
        }
        val query = commands.indexOfFirst { it is SExpr.SList && it.polynomialHead() in setOf(
            "check-sat", "check-sat-assuming", "get-model", "get-value", "exit") }
            .let { if (it < 0) commands.size else it }
        commands.addAll(query, obligations.map { form("assert", it) })
        val checks = sourceChecks + listOf(SmtArithmeticCongruenceCheck(integerChecks.toList(), false),
            SmtArithmeticCongruenceCheck(realChecks.toList(), true))
        return input.copy(commands = commands, checks = input.checks + checks)
    }

    private fun rewrite(root: SExpr): SExpr {
        val work = ArrayDeque<Pair<SExpr, Boolean>>()
        val values = ArrayDeque<SExpr>()
        val ids = ArrayDeque<Int>()
        work.addLast(root to false)
        while (work.isNotEmpty()) {
            step()
            val (node, combine) = work.removeLast()
            if (node is SExpr.Atom) {
                values.addLast(node)
                ids.addLast(atomIds.getOrPut(node.text) { nextNode++ })
            }
            else {
                node as SExpr.SList
                if (!combine) {
                    work.addLast(node to true)
                    node.items.drop(1).asReversed().forEach { work.addLast(it to false) }
                } else {
                    val args = List(node.items.size - 1) { values.removeLast() }.reversed()
                    val arguments = List(node.items.size - 1) { ids.removeLast() }.reversed()
                    val head = node.items.first()
                    val name = (head as? SExpr.Atom)?.text
                    val key = Key(name ?: head.toString(), arguments)
                    val value = cache.getOrPut(key) {
                        when (name) {
                            "div" -> args.reduce { a, b -> divide(a, b, remainder = false) }
                            "mod" -> divide(args[0], args[1], remainder = true)
                            "/" -> args.reduce(::divideReal)
                            "to_int" -> floor(args.single())
                            "is_int" -> form("=", args.single(), form("to_real", floor(args.single())))
                            "abs" -> absolute(args.single())
                            "**" -> power(args[0], args[1])
                            else -> if (head is SExpr.SList && head.items.size == 3 &&
                                (head.items[0] as? SExpr.Atom)?.text == "_" &&
                                (head.items[1] as? SExpr.Atom)?.text == "divisible") {
                                form("=", divide(args.single(), head.items[2], remainder = true), zero)
                            } else SExpr.SList(listOf(head) + args)
                        }
                    }
                    values.addLast(value)
                    ids.addLast(nodeIds.getOrPut(key) { nextNode++ })
                }
            }
        }
        return values.single()
    }

    private fun divide(a: SExpr, b: SExpr, remainder: Boolean): SExpr {
        val q = fresh("Int")
        val r = fresh("Int")
        val nonzero = form("distinct", b, zero)
        obligations += form("=>", nonzero, form("and", form("=", a, form("+", form("*", b, q), r)),
            form("<=", zero, r), form("<", r, absolute(b))))
        val operands = listOf(retain(a, "Int"), retain(b, "Int"), q, r)
        val names = operands.map(::name)
        sourceChecks += SmtArithmeticCheck(SmtArithmeticOperation.DIV_MOD, names)
        integerChecks += names
        val division = Division(a, b, q, r)
        for (other in integerDivisions) {
            step()
            obligations += form("=>", form("and", form("=", a, other.numerator),
                form("=", b, other.denominator)), form("and", form("=", q, other.quotient),
                form("=", r, requireNotNull(other.remainder))))
        }
        integerDivisions += division
        return if (remainder) r else q
    }

    private fun divideReal(a: SExpr, b: SExpr): SExpr {
        if (literalNonzero(b)) return form("/", a, b)
        val q = fresh("Real")
        val names = listOf(retain(a, "Real"), retain(b, "Real"), q).map(::name)
        sourceChecks += SmtArithmeticCheck(SmtArithmeticOperation.REAL_DIV, names)
        realChecks += names
        obligations += form("=>", form("distinct", b, zero), form("=", form("*", b, q), a))
        for (other in realDivisions) {
            step()
            obligations += form("=>", form("and", form("=", a, other.numerator),
                form("=", b, other.denominator)), form("=", q, other.quotient))
        }
        realDivisions += Division(a, b, q, null)
        return q
    }

    private fun floor(argument: SExpr): SExpr {
        val q = fresh("Int")
        sourceChecks += SmtArithmeticCheck(SmtArithmeticOperation.FLOOR,
            listOf(retain(argument, "Real"), q).map(::name))
        obligations += form("and", form("<=", form("to_real", q), argument),
            form("<", argument, form("+", form("to_real", q), one)))
        return q
    }

    private fun power(base: SExpr, exponent: SExpr): SExpr {
        val result = fresh("Int")
        sourceChecks += SmtArithmeticCheck(SmtArithmeticOperation.POWER,
            listOf(retain(base, "Int"), retain(exponent, "Int"), result).map(::name))
        val fixed = integerLiteral(exponent)
        if (fixed != null && fixed >= BIG_ZERO && fixed <= bigIntOf(Int.MAX_VALUE)) {
            obligations += form("=", result, form("^", base, SExpr.Atom(fixed.toLongExact().toString())))
            return result
        }
        val magnitude = absolute(base)
        val odd = form("=", divide(exponent, two, remainder = true), one)
        val sign = form("ite", form("and", form("<", base, zero), odd), minusOne, one)
        // The logarithm receives a positive argument on every branch, including inactive branches.
        val positiveBase = form("ite", form(">", magnitude, one), magnitude, one)
        val exponential = form(analyticFunction("exp"), form("*", form("to_real", exponent),
            form(analyticFunction("log"), form("to_real", positiveBase))))
        val general = form("*", sign, exponential)
        val value = form("ite", form("=", exponent, zero), one,
            form("ite", form("=", magnitude, one), sign,
                form("ite", form("or", form("<", exponent, zero), form("=", base, zero)), zero, general)))
        obligations += form("=", form("to_real", result), value)
        return result
    }

    private fun fresh(sort: String): SExpr {
        val name = freshName()
        val result = SExpr.Atom(name)
        declarations += form("declare-const", result, SExpr.Atom(sort))
        return result
    }

    private fun freshName(): String {
        var name: String
        do { step(); name = "klause!arithmetic${identity++}" } while (!used.add(name))
        return name
    }

    private fun analyticFunction(function: String): String {
        val existing = if (function == "exp") exponentialName else logarithmName
        if (existing != null) return existing
        val name = freshName()
        val parameter = SExpr.Atom(freshName())
        prelude += form("define-fun", SExpr.Atom(name),
            SExpr.SList(listOf(SExpr.SList(listOf(parameter, SExpr.Atom("Real"))))), SExpr.Atom("Real"),
            form(function, parameter))
        if (function == "exp") exponentialName = name else logarithmName = name
        return name
    }

    private fun retain(expression: SExpr, sort: String): SExpr {
        val coordinate = fresh(sort)
        obligations += form("=", coordinate, expression)
        return coordinate
    }

    private fun name(expression: SExpr): String = (expression as SExpr.Atom).text

    private fun integerLiteral(expression: SExpr): com.eignex.util.numbers.BigInt? {
        var term = expression
        var negative = false
        while (term is SExpr.SList && term.items.size == 2 && term.polynomialHead() == "-") {
            step()
            negative = !negative
            term = term.items[1]
        }
        val text = (term as? SExpr.Atom)?.text ?: return null
        if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
        val value = parseBigInt(text)
        return if (negative) -value else value
    }

    private fun literalNonzero(expression: SExpr): Boolean {
        var term = expression
        while (term is SExpr.SList && term.items.size == 2 && term.polynomialHead() == "-") {
            step()
            term = term.items[1]
        }
        val text = (term as? SExpr.Atom)?.text ?: return false
        return text.isNotEmpty() && text.all { it in '0'..'9' || it == '.' } && text.any { it in '1'..'9' }
    }

    private fun absolute(value: SExpr): SExpr = form("ite", form("<", value, zero), form("-", value), value)
    private fun step() = cancellation.polynomialSourceCheckpoint(1)
    private fun form(operator: String, vararg arguments: SExpr): SExpr =
        SExpr.SList(listOf(SExpr.Atom(operator)) + arguments)
    private val zero = SExpr.Atom("0")
    private val one = SExpr.Atom("1")
    private val two = SExpr.Atom("2")
    private val minusOne = form("-", one)
}
