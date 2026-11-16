package com.eignex.klause.formats.smtlib

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.remainderOf
import com.eignex.util.numbers.signum
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

/** A fixed source interpretation with exact ground-term evaluation for command responses. */
class SmtGroundModel(
    source: String,
    private val model: String,
    private val cancellation: ExecutionControl = ExecutionControl.Never,
    private val workLimit: Int = 100_000,
) : SmtSessionModel {
    private data class Function(val parameters: List<String>, val body: SExpr)
    private val functions = LinkedHashMap<String, Function>()
    private val constructors = HashSet<String>()
    private val selectors = HashMap<String, Pair<String, Int>>()

    init {
        for (command in SExprReader(source).readAll()) {
            val list = command as? SExpr.SList ?: continue
            when (list.head()) {
                "define-fun", "define-fun-rec" -> define(list)
                "define-funs-rec" -> {
                    val signatures = list.items[1] as SExpr.SList
                    val bodies = list.items[2] as SExpr.SList
                    for ((index, signature) in signatures.items.withIndex()) {
                        define(SExpr.SList(listOf(SExpr.Atom("define-fun")) +
                            (signature as SExpr.SList).items + bodies.items[index]))
                    }
                }
                "declare-datatype" -> datatype(list.items[2])
                "declare-datatypes" -> for (body in (list.items[2] as SExpr.SList).items) datatype(body)
            }
        }
        val response = SExprReader(model).readAll().singleOrNull() as? SExpr.SList
            ?: throw UnsupportedSmtException("invalid model response")
        for (definition in response.items) define(definition as? SExpr.SList
            ?: throw UnsupportedSmtException("invalid model definition"))
    }

    override fun render(): String = model

    override fun evaluate(term: SExpr): String = try {
        evaluateTerm(term).smtText()
    } catch (error: IllegalArgumentException) {
        throw UnsupportedSmtException(error.message ?: "invalid model term")
    }

    internal fun evaluateTerm(term: SExpr, environment: Map<String, SExpr> = emptyMap()): SExpr {
        val work = ArrayDeque<GroundFrame>()
        val values = ArrayDeque<SExpr>()
        var spent = 0
        fun checkpoint() {
            cancellation.charge(1)
            if (++spent > workLimit || cancellation()) throw UnsupportedSmtException("ground evaluation resource limit")
        }
        fun take(count: Int): List<SExpr> = List(count) { values.removeLast() }.reversed()
        fun call(name: String, args: List<SExpr>) {
            val definition = functions[name]
            if (definition != null) {
                if (definition.parameters.size != args.size) throw UnsupportedSmtException("wrong function arity")
                work += GroundFrame.Evaluate(definition.body, definition.parameters.zip(args).toMap())
            } else values += operation(name, args)
        }
        work += GroundFrame.Evaluate(term, environment)
        while (work.isNotEmpty()) {
            checkpoint()
            when (val frame = work.removeLast()) {
                is GroundFrame.Evaluate -> when (val expression = frame.expression) {
                    is SExpr.Atom -> {
                        val name = smtSessionSymbol(expression.text)
                        val local = frame.environment[name]
                        when {
                            local != null -> values += local
                            name in functions -> call(name, emptyList())
                            name in constructors || expression.text in setOf("true", "false") ||
                                expression.text.firstOrNull()?.let { it.isDigit() || it == '#' } == true -> values += expression
                            else -> throw UnsupportedSmtException("model has no value for '$name'")
                        }
                    }
                    is SExpr.SList -> {
                        val head = expression.head()
                        when (head) {
                            "ite" -> {
                                work += GroundFrame.Choose(expression.items[2], expression.items[3], frame.environment)
                                work += GroundFrame.Evaluate(expression.items[1], frame.environment)
                            }
                            "let" -> {
                                val bindings = (expression.items[1] as SExpr.SList).items.map { it as SExpr.SList }
                                work += GroundFrame.Let(bindings.map { smtSessionSymbol((it.items[0] as SExpr.Atom).text) },
                                    expression.items[2], frame.environment)
                                for (binding in bindings.asReversed()) work += GroundFrame.Evaluate(binding.items[1], frame.environment)
                            }
                            "!" -> work += GroundFrame.Evaluate(expression.items[1], frame.environment)
                            "as" -> values += expression
                            "_" -> values += expression
                            else -> {
                                work += GroundFrame.Apply(expression.items[0], expression.items.size - 1, frame.environment)
                                for (argument in expression.items.drop(1).asReversed()) {
                                    work += GroundFrame.Evaluate(argument, frame.environment)
                                }
                            }
                        }
                    }
                }
                is GroundFrame.Choose -> work += GroundFrame.Evaluate(
                    if (values.removeLast().boolean()) frame.yes else frame.no, frame.environment,
                )
                is GroundFrame.Let -> work += GroundFrame.Evaluate(frame.body,
                    frame.environment + frame.names.zip(take(frame.names.size)))
                is GroundFrame.Apply -> {
                    val arguments = take(frame.count)
                    if (frame.head is SExpr.Atom) call(smtSessionSymbol(frame.head.text), arguments)
                    else values += indexedOperation(frame.head as SExpr.SList, arguments)
                }
            }
        }
        return values.single()
    }

    private fun define(command: SExpr.SList) {
        if (command.head() !in setOf("define-fun", "define-fun-rec") || command.items.size != 5) {
            throw UnsupportedSmtException("invalid model definition")
        }
        val name = smtSessionSymbol((command.items[1] as SExpr.Atom).text)
        val parameters = (command.items[2] as SExpr.SList).items.map {
            smtSessionSymbol(((it as SExpr.SList).items[0] as SExpr.Atom).text)
        }
        functions[name] = Function(parameters, command.items[4])
    }

    private fun datatype(expression: SExpr) {
        val list = expression as SExpr.SList
        val declarations = if (list.head() == "par") (list.items[2] as SExpr.SList).items else list.items
        for (declaration in declarations) {
            val constructor = declaration as SExpr.SList
            val name = smtSessionSymbol((constructor.items[0] as SExpr.Atom).text)
            constructors += name
            for ((index, field) in constructor.items.drop(1).withIndex()) {
                selectors[smtSessionSymbol(((field as SExpr.SList).items[0] as SExpr.Atom).text)] = name to index
            }
        }
    }

    private fun operation(name: String, args: List<SExpr>): SExpr {
        fun bool(value: Boolean): SExpr = SExpr.Atom(value.toString())
        fun numbers(): List<BigFraction> = args.map { it.number() }
        fun number(value: BigFraction): SExpr = value.expression()
        val numeric = name in setOf("+", "-", "*", "/", "div", "mod", "abs", "to_real", "to_int", "is_int", "^", "<", "<=", ">", ">=")
        val nums = if (numeric) numbers() else emptyList()
        return when (name) {
            "and" -> bool(args.all { it.boolean() })
            "or" -> bool(args.any { it.boolean() })
            "not" -> bool(!args.single().boolean())
            "xor" -> bool(args.count { it.boolean() } % 2 == 1)
            "=>" -> bool(args.dropLast(1).any { !it.boolean() } || args.last().boolean())
            "=" -> bool(args.zipWithNext().all { (a, b) -> equal(a, b) })
            "distinct" -> bool(args.indices.all { i -> (i + 1 until args.size).all { j -> !equal(args[i], args[j]) } })
            "+" -> number(nums.fold(BigFraction.ZERO) { a, b -> a + b })
            "*" -> number(nums.fold(BigFraction.ONE) { a, b -> a * b })
            "-" -> number(if (nums.size == 1) nums[0].negated() else nums.drop(1).fold(nums[0]) { a, b -> a - b })
            "/" -> number(nums.drop(1).fold(nums[0]) { a, b -> if (b.isZero) BigFraction.ZERO else a * b.reciprocal() })
            "abs" -> number(if (nums.single().signum() < 0) nums.single().negated() else nums.single())
            "to_real" -> number(nums.single())
            "to_int" -> number(BigFraction.of(floor(nums.single()), BIG_ONE))
            "is_int" -> bool(nums.single().den == BIG_ONE)
            "div", "mod" -> {
                val a = nums[0].num
                val b = nums[1].num
                if (b.isZero()) number(BigFraction.ZERO) else {
                    var remainder = a.remainderOf(b)
                    if (remainder.signum() < 0) remainder += b.abs()
                    number(BigFraction.of(if (name == "mod") remainder else (a - remainder) / b, BIG_ONE))
                }
            }
            "<", "<=", ">", ">=" -> bool(nums.zipWithNext().all { (a, b) ->
                when (name) { "<" -> a < b; "<=" -> a <= b; ">" -> a > b; else -> a >= b }
            })
            "^" -> {
                var exponent = nums[1].num
                if (nums[1].den != BIG_ONE || exponent.signum() < 0) throw UnsupportedSmtException("invalid integer power")
                var base = nums[0]
                var value = BigFraction.ONE
                val two = BIG_ONE + BIG_ONE
                while (exponent > BIG_ZERO) {
                    if (!exponent.remainderOf(two).isZero()) value *= base
                    exponent /= two
                    if (exponent > BIG_ZERO) base *= base
                }
                number(value)
            }
            "store" -> SExpr.SList(listOf(SExpr.Atom(name)) + args)
            "select" -> select(args[0], args[1])
            in constructors -> SExpr.SList(listOf(SExpr.Atom(name)) + args)
            in selectors -> {
                val (constructor, index) = selectors.getValue(name)
                val value = args.single() as? SExpr.SList
                if (value?.head() == constructor) value.items[index + 1]
                else throw UnsupportedSmtException("selector outside its constructor has no retained interpretation")
            }
            else -> throw UnsupportedSmtException("cannot evaluate '$name' in the retained model")
        }
    }

    private fun indexedOperation(head: SExpr.SList, args: List<SExpr>): SExpr {
        if (head.head() == "as" && (head.items.getOrNull(1) as? SExpr.Atom)?.text == "const") {
            return SExpr.SList(listOf(head) + args)
        }
        if (head.head() == "_" && (head.items.getOrNull(1) as? SExpr.Atom)?.text == "is") {
            val expected = smtSessionSymbol((head.items[2] as SExpr.Atom).text)
            val actual = args.single().let { if (it is SExpr.Atom) smtSessionSymbol(it.text) else (it as SExpr.SList).head() }
            return SExpr.Atom((actual == expected).toString())
        }
        throw UnsupportedSmtException("cannot evaluate indexed operator ${head.smtText()}")
    }

    private fun select(array: SExpr, index: SExpr): SExpr {
        var current = array
        while (current is SExpr.SList) {
            if (current.head() == "store") {
                if (equal(current.items[2], index)) return current.items[3]
                current = current.items[1]
            } else {
                val head = current.items.firstOrNull() as? SExpr.SList
                if (head?.head() == "as" && (head.items.getOrNull(1) as? SExpr.Atom)?.text == "const") return current.items[1]
                break
            }
        }
        throw UnsupportedSmtException("array has no retained default value")
    }

    private fun equal(a: SExpr, b: SExpr): Boolean {
        if (a == b) return true
        val left = runCatching { a.number() }.getOrNull()
        val right = runCatching { b.number() }.getOrNull()
        if (left != null && right != null) return left == right
        if (a is SExpr.Atom && b is SExpr.Atom && a.text in setOf("true", "false") && b.text in setOf("true", "false")) return false
        fun constructor(term: SExpr): String = if (term is SExpr.Atom) smtSessionSymbol(term.text) else (term as SExpr.SList).head()
        val lhs = constructor(a)
        val rhs = constructor(b)
        if (lhs in constructors && rhs in constructors) {
            if (lhs != rhs) return false
            val xs = (a as? SExpr.SList)?.items?.drop(1).orEmpty()
            val ys = (b as? SExpr.SList)?.items?.drop(1).orEmpty()
            return xs.size == ys.size && xs.indices.all { equal(xs[it], ys[it]) }
        }
        if (lhs == "as" && rhs == "as") return (a as SExpr.SList).items[1] == (b as SExpr.SList).items[1]
        throw UnsupportedSmtException("equality of these certified model values cannot be decided by ground evaluation")
    }
    private fun floor(value: BigFraction) = (value.num / value.den).let {
        if (value.signum() < 0 && !value.num.remainderOf(value.den).isZero()) it - BIG_ONE else it
    }
    private fun SExpr.boolean(): Boolean = when ((this as? SExpr.Atom)?.text) {
        "true" -> true
        "false" -> false
        else -> throw UnsupportedSmtException("expected a Boolean model value")
    }
    private fun SExpr.number(): BigFraction = when (this) {
        is SExpr.Atom -> {
            val parts = text.split('.')
            if (parts.size == 1) BigFraction.of(parseBigInt(text), BIG_ONE) else {
                var denominator = BIG_ONE
                repeat(parts[1].length) { denominator *= parseBigInt("10") }
                BigFraction.of(parseBigInt(parts.joinToString("")), denominator)
            }
        }
        is SExpr.SList -> when (head()) {
            "-" -> items[1].number().negated()
            "/" -> items[1].number() * items[2].number().reciprocal()
            else -> throw UnsupportedSmtException("model value is not rational")
        }
    }
    private fun BigFraction.expression(): SExpr {
        fun integer(value: String): SExpr = if (value.startsWith('-')) SExpr.SList(listOf(SExpr.Atom("-"), SExpr.Atom(value.drop(1))))
            else SExpr.Atom(value)
        return if (den == BIG_ONE) integer(num.toString()) else SExpr.SList(listOf(SExpr.Atom("/"), integer(num.toString()), SExpr.Atom(den.toString())))
    }
    private fun SExpr.SList.head(): String = (items.firstOrNull() as? SExpr.Atom)?.text?.let(::smtSessionSymbol).orEmpty()
}

private sealed interface GroundFrame {
    data class Evaluate(val expression: SExpr, val environment: Map<String, SExpr>) : GroundFrame
    data class Apply(val head: SExpr, val count: Int, val environment: Map<String, SExpr>) : GroundFrame
    data class Choose(val yes: SExpr, val no: SExpr, val environment: Map<String, SExpr>) : GroundFrame
    data class Let(val names: List<String>, val body: SExpr, val environment: Map<String, SExpr>) : GroundFrame
}
