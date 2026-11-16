package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_TWO
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.remainderOf
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

internal fun reduceSmtArithmetic(input: SmtReduction, cancellation: ExecutionControl): SmtReduction =
    SmtArithmeticReducer(input, cancellation).reduce()

private class SmtArithmeticReducer(private val input: SmtReduction, private val cancellation: ExecutionControl) {
    private data class Division(val numerator: SExpr, val denominator: SExpr, val quotient: SExpr,
        val remainder: SExpr?, val activation: String?)
    private data class Key(val operator: String, val arguments: List<Int>, val activation: String? = null)
    private sealed interface Frame {
        data class Evaluate(val expression: SExpr, val activation: String?) : Frame
        data class Combine(val expression: SExpr.SList, val activation: String?) : Frame
        data class IteCondition(val expression: SExpr.SList, val activation: String?) : Frame
        data class IteCombine(val condition: SExpr, val conditionId: Int, val activation: String?) : Frame
    }
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
    private val integerChecks = ArrayList<SmtArithmeticApplication>()
    private val realChecks = ArrayList<SmtArithmeticApplication>()
    private val prelude = ArrayList<SExpr>()
    private var exponentialName: String? = null
    private var logarithmName: String? = null
    private var identity = 0
    private var active: String? = null
    private var guardPartials = false
    private val constants = SmtArithmeticConstants(cancellation)
    private val fixings = LinkedHashMap<String, SExpr>()
    private val scalarNames = LinkedHashMap<String, Pair<String, String>>()

    fun reduce(): SmtReduction {
        collectFixings()
        for (declaration in input.declarations) {
            step()
            used += polynomialSymbol(declaration.name)
        }
        for (name in input.privateBindings.keys) {
            step()
            used += polynomialSymbol(name)
        }
        val work = ArrayDeque<SExpr>()
        input.commands.forEach(work::addLast)
        while (work.isNotEmpty()) {
            step()
            when (val node = work.removeLast()) {
                is SExpr.Atom -> used += polynomialSymbol(node.text)
                is SExpr.SList -> {
                    if ((node.items.firstOrNull() as? SExpr.Atom)?.text?.let(::polynomialSymbol) == "log") {
                        guardPartials = true
                    }
                    node.items.forEach(work::addLast)
                }
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
        val interpretations = input.privateBindings + mapOf(
            "@operation:div" to SmtArithmeticInterpretation(integerChecks.toList(), 2, false),
            "@operation:mod" to SmtArithmeticInterpretation(integerChecks.toList(), 3, false),
            "@operation:/" to SmtArithmeticInterpretation(realChecks.toList(), 2, true))
        if (declarations.isEmpty() && obligations.isEmpty()) {
            return input.copy(commands = commands, privateBindings = interpretations)
        }
        // Definitions can depend on later private coordinates, so all scalar declarations precede formulas.
        val insertion = commands.indexOfFirst {
            it is SExpr.SList && it.polynomialHead() in setOf(
                "assert", "minimize", "maximize", "check-sat", "check-sat-assuming", "get-model", "get-value", "exit")
        }
            .let { if (it < 0) commands.size else it }
        commands.addAll(insertion, declarations)
        if (prelude.isNotEmpty()) {
            val start = commands.indexOfFirst {
                it is SExpr.SList &&
                    it.polynomialHead() !in setOf("set-logic", "set-info", "set-option")
            }
                .let { if (it < 0) commands.size else it }
            commands.addAll(start, prelude)
        }
        val query = commands.indexOfFirst {
            it is SExpr.SList && it.polynomialHead() in setOf(
                "check-sat", "check-sat-assuming", "get-model", "get-value", "exit")
        }
            .let { if (it < 0) commands.size else it }
        commands.addAll(query, obligations.map { form("assert", it) })
        val checks = sourceChecks + listOf(SmtArithmeticCongruenceCheck(integerChecks.toList(), false),
            SmtArithmeticCongruenceCheck(realChecks.toList(), true))
        return input.copy(commands = commands, checks = input.checks + checks, privateBindings = interpretations)
    }

    private fun rewrite(root: SExpr): SExpr {
        val work = ArrayDeque<Frame>()
        val values = ArrayDeque<SExpr>()
        val ids = ArrayDeque<Int>()
        work.addLast(Frame.Evaluate(root, null))
        while (work.isNotEmpty()) {
            step()
            when (val frame = work.removeLast()) {
                is Frame.Evaluate -> when (val node = frame.expression) {
                    is SExpr.Atom -> {
                        val value = fixingSymbol(node)?.let(fixings::get) ?: node
                        values.addLast(value)
                        ids.addLast(atomIds.getOrPut(node.text) { nextNode++ })
                    }
                    is SExpr.SList -> {
                        if ((unqualified(node.items.first()) as? SExpr.Atom)?.text == "ite") {
                            work.addLast(Frame.IteCondition(node, frame.activation))
                            work.addLast(Frame.Evaluate(node.items[1], frame.activation))
                        } else {
                            work.addLast(Frame.Combine(node, frame.activation))
                            node.items.drop(1).asReversed().forEach {
                                work.addLast(Frame.Evaluate(it, frame.activation))
                            }
                        }
                    }
                }
                is Frame.IteCondition -> {
                    val condition = values.removeLast()
                    val conditionId = ids.removeLast()
                    val whenTrue = activation(frame.activation, condition)
                    val whenFalse = activation(frame.activation, form("not", condition))
                    work.addLast(Frame.IteCombine(condition, conditionId, frame.activation))
                    work.addLast(Frame.Evaluate(frame.expression.items[3], whenFalse))
                    work.addLast(Frame.Evaluate(frame.expression.items[2], whenTrue))
                }
                is Frame.IteCombine -> {
                    val whenFalse = values.removeLast()
                    val whenTrue = values.removeLast()
                    val falseId = ids.removeLast()
                    val trueId = ids.removeLast()
                    values.addLast(form("ite", frame.condition, whenTrue, whenFalse))
                    ids.addLast(nodeIds.getOrPut(Key("ite", listOf(frame.conditionId, trueId, falseId),
                        frame.activation)) { nextNode++ })
                }
                is Frame.Combine -> {
                    val node = frame.expression
                    val args = List(node.items.size - 1) { values.removeLast() }.reversed()
                    val arguments = List(node.items.size - 1) { ids.removeLast() }.reversed()
                    val identifier = unqualified(node.items.first())
                    val head = if (identifier is SExpr.Atom) SExpr.Atom(polynomialSymbol(identifier.text))
                    else identifier
                    val name = (head as? SExpr.Atom)?.text?.let(::polynomialSymbol)
                    val key = Key(name ?: head.toString(), arguments, frame.activation)
                    active = frame.activation
                    val value = cache.getOrPut(key) {
                        constants.fold(name ?: "", args) ?: when (name) {
                            "div" -> args.reduce { a, b -> divide(a, b, remainder = false) }
                            "mod" -> divide(args[0], args[1], remainder = true)
                            "/" -> args.reduce(::divideReal)
                            "to_int" -> floor(args.single())
                            "is_int" -> form("=", args.single(), form("to_real", floor(args.single())))
                            "abs" -> absolute(args.single())
                            "**" -> power(args[0], args[1])
                            else -> if (head is SExpr.SList && head.items.size == 3 &&
                                (head.items[0] as? SExpr.Atom)?.text == "_" &&
                                (head.items[1] as? SExpr.Atom)?.text?.let(::polynomialSymbol) == "divisible") {
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

    private fun collectFixings() {
        val premises = ArrayList<SExpr>()
        for (expression in input.commands) {
            step()
            val command = expression as? SExpr.SList ?: continue
            when (command.polynomialHead()) {
                "declare-const", "declare-fun" -> {
                    val sort = (command.items.lastOrNull() as? SExpr.Atom)?.text ?: continue
                    val spelling = (command.items[1] as SExpr.Atom).text
                    if (sort in setOf("Int", "Real", "Bool")) scalarNames[polynomialSymbol(spelling)] = spelling to sort
                }
                "assert" -> premises += command.items[1]
            }
        }
        var changed: Boolean
        do {
            val previous = fixings.size
            val work = ArrayDeque<SExpr>()
            premises.forEach(work::addLast)
            while (work.isNotEmpty()) {
                step()
                val premise = work.removeLast()
                if (premise is SExpr.Atom) bind(premise, SExpr.Atom("true"))
                else {
                    premise as SExpr.SList
                    when ((premise.items.firstOrNull() as? SExpr.Atom)?.text) {
                        "and" -> premise.items.drop(1).forEach(work::addLast)
                        "not" -> if (premise.items.size == 2) bind(premise.items[1], SExpr.Atom("false"))
                        "=" -> for (index in 1 until premise.items.lastIndex) {
                            bind(premise.items[index], premise.items[index + 1])
                            bind(premise.items[index + 1], premise.items[index])
                        }
                    }
                }
            }
            changed = previous != fixings.size
        } while (changed)
        for ((name, value) in fixings) {
            obligations += form("=", SExpr.Atom(scalarNames.getValue(name).first), value)
        }
    }

    private fun bind(expression: SExpr, candidate: SExpr) {
        val symbol = fixingSymbol(expression) ?: return
        if (symbol in fixings) return
        val declaration = scalarNames[symbol] ?: return
        val value = fixingSymbol(candidate)?.let(fixings::get) ?: candidate
        if (declaration.second == "Bool") {
            if ((value as? SExpr.Atom)?.text in setOf("true", "false")) fixings[symbol] = value
        } else {
            val number = constants.knownRational(value) ?: return
            if (declaration.second == "Int" && number.den != com.eignex.util.numbers.BIG_ONE) return
            fixings[symbol] = constants.literal(number)
        }
    }

    private fun fixingSymbol(expression: SExpr): String? {
        val spelling = (expression as? SExpr.Atom)?.text ?: return null
        if (spelling.startsWith('|')) return polynomialSymbol(spelling)
        if (spelling in setOf("true", "false") || spelling.startsWith('#')) return null
        val parts = spelling.split('.')
        if (parts.size in 1..2 && parts.all { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }) return null
        return polynomialSymbol(spelling)
    }

    private fun activation(parent: String?, condition: SExpr): String? {
        if (!guardPartials) return null
        val guard = fresh("Bool")
        val value = if (parent == null) condition else form("ite", SExpr.Atom(parent), condition, SExpr.Atom("false"))
        obligations += form("=", guard, value)
        return name(guard)
    }

    private fun oblige(expression: SExpr) {
        obligations += active?.let { form("ite", SExpr.Atom(it), expression, SExpr.Atom("true")) } ?: expression
    }

    private fun check(source: SmtSourceCheck) {
        val guard = active
        sourceChecks += if (guard == null) source else SmtSourceCheck { values ->
            !values.boolean(guard) || source.check(values)
        }
    }

    private fun divide(a: SExpr, b: SExpr, remainder: Boolean): SExpr {
        val q = fresh("Int")
        val r = fresh("Int")
        val nonzero = form("distinct", b, zero)
        oblige(form("=>", nonzero, form("and", form("=", a, form("+", form("*", b, q), r)),
            form("<=", zero, r), form("<", r, absolute(b)))))
        val operands = listOf(retain(a, "Int"), retain(b, "Int"), q, r)
        val names = operands.map(::name)
        check(SmtArithmeticCheck(SmtArithmeticOperation.DIV_MOD, names))
        integerChecks += SmtArithmeticApplication(names, active)
        val division = Division(a, b, q, r, active)
        for (other in integerDivisions) {
            step()
            congruence(other, form("=>", form("and", form("=", a, other.numerator),
                form("=", b, other.denominator)), form("and", form("=", q, other.quotient),
                form("=", r, requireNotNull(other.remainder)))))
        }
        integerDivisions += division
        return if (remainder) r else q
    }

    private fun divideReal(a: SExpr, b: SExpr): SExpr {
        if (literalNonzero(b)) return form("/", a, b)
        val q = fresh("Real")
        val names = listOf(retain(a, "Real"), retain(b, "Real"), q).map(::name)
        check(SmtArithmeticCheck(SmtArithmeticOperation.REAL_DIV, names))
        realChecks += SmtArithmeticApplication(names, active)
        oblige(form("=>", form("distinct", b, zero), form("=", form("*", b, q), a)))
        for (other in realDivisions) {
            step()
            congruence(other, form("=>", form("and", form("=", a, other.numerator),
                form("=", b, other.denominator)), form("=", q, other.quotient)))
        }
        realDivisions += Division(a, b, q, null, active)
        return q
    }

    private fun floor(argument: SExpr): SExpr {
        val q = fresh("Int")
        check(SmtArithmeticCheck(SmtArithmeticOperation.FLOOR,
            listOf(retain(argument, "Real"), q).map(::name)))
        oblige(form("and", form("<=", form("to_real", q), argument),
            form("<", argument, form("+", form("to_real", q), one))))
        return q
    }

    private fun power(base: SExpr, exponent: SExpr): SExpr {
        val result = fresh("Int")
        check(SmtArithmeticCheck(SmtArithmeticOperation.POWER,
            listOf(retain(base, "Int"), retain(exponent, "Int"), result).map(::name)))
        val fixed = integerLiteral(exponent)
        if (fixed != null && fixed < BIG_ZERO) {
            step()
            val negativeUnit = if (fixed.remainderOf(BIG_TWO) == BIG_ZERO) one else minusOne
            oblige(form("=", result, form("ite", form("=", base, one), one,
                form("ite", form("=", base, minusOne), negativeUnit, zero))))
            return result
        }
        if (fixed != null && fixed >= BIG_ZERO && fixed <= bigIntOf(Int.MAX_VALUE)) {
            oblige(form("=", result, form("^", base, SExpr.Atom(fixed.toLongExact().toString()))))
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
        oblige(form("=", form("to_real", result), value))
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
        do {
            step()
            name = "klause!arithmetic${identity++}"
        } while (!used.add(name))
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
        oblige(form("=", coordinate, expression))
        return coordinate
    }

    private fun congruence(other: Division, expression: SExpr) {
        oblige(other.activation?.let { form("ite", SExpr.Atom(it), expression, SExpr.Atom("true")) } ?: expression)
    }

    private fun name(expression: SExpr): String = (expression as SExpr.Atom).text

    private fun unqualified(identifier: SExpr): SExpr = if (identifier is SExpr.SList &&
        identifier.items.size == 3 && (identifier.items[0] as? SExpr.Atom)?.text == "as") {
        identifier.items[1]
    } else identifier

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
