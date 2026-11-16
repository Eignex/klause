package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.real.ExactRealFunction
import com.eignex.klause.arithmetic.real.ExactRealValue
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
import com.eignex.util.numbers.toInt
import com.eignex.util.numbers.unaryMinus

internal class SmtFixedModel(
    private val source: List<SExpr>,
    private val model: SmtSourceModel,
    private val retained: SmtModelValues,
    private val control: ExecutionControl,
) : SmtSessionModel {
    private data class Definition(val parameters: List<String>, val body: SExpr)
    private val definitions = LinkedHashMap<String, Definition>()
    private val constructors = HashSet<String>()
    private val selectors = HashMap<String, List<Pair<String, Int>>>()
    private val authorities = HashMap<SmtValue.Real, ExactRealValue>()
    private var queryWork = 0L
    private val arithmetic by lazy { smtExactArithmetic(control) }
    private val values = object : SmtModelValues by retained, SmtSortCardinalities {
        override val cancellation: ExecutionControl get() = control
        override fun checkpoint(units: Long) = this@SmtFixedModel.checkpoint(units)
        override fun exactReal(value: SmtValue.Real): ExactRealValue? =
            authorities[value] ?: retained.exactReal(value)
        override fun equal(left: SmtValue, right: SmtValue): Boolean? =
            if (numeric(left) && numeric(right)) arithmetic.compare(exact(left), exact(right))?.let { it == 0 }
            else smtValuesEqual(this, left, right)
        override fun cardinality(sort: SmtSort, cap: Int): Int? =
            (retained as? SmtSortCardinalities)?.cardinality(sort, cap)
    }

    init {
        for (expression in source) {
            val command = expression as? SExpr.SList ?: continue
            when (command.head()) {
                "define-fun", "define-fun-rec", "define-const" -> {
                    val constant = command.head() == "define-const"
                    val parameters = if (constant) emptyList() else
                        (command.items[2] as SExpr.SList).items.map {
                            symbol(((it as SExpr.SList).items[0] as SExpr.Atom).text)
                        }
                    definitions[symbol((command.items[1] as SExpr.Atom).text)] =
                        Definition(parameters, command.items[if (constant) 3 else 4])
                }
                "define-funs-rec" -> {
                    val signatures = (command.items[1] as SExpr.SList).items
                    val bodies = (command.items[2] as SExpr.SList).items
                    signatures.forEachIndexed { index, expression ->
                        val signature = expression as SExpr.SList
                        val parameters = (signature.items[1] as SExpr.SList).items.map {
                            symbol(((it as SExpr.SList).items[0] as SExpr.Atom).text)
                        }
                        definitions[symbol((signature.items[0] as SExpr.Atom).text)] =
                            Definition(parameters, bodies[index])
                    }
                }
                "declare-datatype" -> datatype(command.items[2])
                "declare-datatypes" -> (command.items[2] as SExpr.SList).items.forEach(::datatype)
            }
        }
    }

    override fun withRecursiveDefinitions(source: List<SExpr>): SmtSessionModel =
        SmtFixedModel(source, model, retained, control)

    override fun render(): String {
        val response = SExprReader(model.toSmtLib()).readAll().single() as SExpr.SList
        val parameters = source.mapNotNull { expression ->
            val command = expression as? SExpr.SList
            if (command?.head() == "declare-sort-parameter") command.items[1] as? SExpr.Atom else null
        }.map { symbol(it.text) }.toSet()
        val additions = source.mapNotNull { expression ->
            val command = expression as? SExpr.SList ?: return@mapNotNull null
            val definition = when (command.head()) {
                "define-fun", "define-fun-rec", "define-funs-rec" -> command
                "define-const" -> SExpr.SList(listOf(SExpr.Atom("define-fun"), command.items[1],
                    SExpr.SList(emptyList()), command.items[2], command.items[3]))
                else -> return@mapNotNull null
            }
            val pending = ArrayDeque<SExpr>()
            if (definition.head() == "define-funs-rec") pending += definition.items[1]
            else {
                pending += definition.items[2]
                pending += definition.items[3]
            }
            var polymorphic = false
            while (pending.isNotEmpty()) when (val term = pending.removeLast()) {
                is SExpr.Atom -> if (symbol(term.text) in parameters) polymorphic = true
                is SExpr.SList -> pending.addAll(term.items)
            }
            definition.takeUnless { polymorphic }
        }
        return SExpr.SList(response.items + additions).smtText()
    }

    override fun abstractValues(): Map<String, SmtSort> = buildMap {
        val work = ArrayDeque<SmtValue>()
        model.values.forEach { work += it.second }
        while (work.isNotEmpty()) when (val value = work.removeLast()) {
            is SmtValue.Uninterpreted -> put(symbol(value.identity), value.sort)
            is SmtValue.Application -> work.addAll(value.arguments)
            is SmtValue.Array -> {
                work += value.default
                value.entries.forEach { (index, element) -> work += index; work += element }
            }
            is SmtValue.Function -> {
                work += value.default
                value.entries.forEach { (arguments, result) -> work.addAll(arguments); work += result }
            }
            else -> Unit
        }
    }

    override fun evaluate(term: SExpr): String {
        queryWork = 0
        val query = prepareSmtSessionQueries(source, listOf(term), control, abstractValues())
        var expanded = query.terms.single()
        for (definition in query.definitions.asReversed()) {
            val binding = SExpr.SList(listOf(definition.items[1], definition.items[4]))
            expanded = list("let", SExpr.SList(listOf(binding)), expanded)
        }
        var value = evaluateValue(expanded)
        val type = smtSessionQuerySort(source, term, control, abstractValues())
        if (type == SmtSort.Real && value is SmtValue.Integer) value = real(exact(value))
        return value.toSmtLib()
    }

    private fun evaluateValue(term: SExpr): SmtValue {
        val work = ArrayDeque<FixedFrame>()
        val stack = ArrayDeque<SmtValue>()
        fun take(count: Int): List<SmtValue> = List(count) { stack.removeLast() }.reversed()
        fun apply(head: SExpr, args: List<SmtValue>) {
            val name = if (head is SExpr.Atom) symbol(head.text) else (head as SExpr.SList).head()
            val definition = definitions[name]
            if (definition != null) {
                if (args.size != definition.parameters.size) unavailable("function arity mismatch")
                work += FixedFrame.Evaluate(definition.body, definition.parameters.zip(args).toMap())
            } else stack += operation(head, args)
        }
        work += FixedFrame.Evaluate(term, emptyMap())
        while (work.isNotEmpty()) {
            checkpoint()
            when (val frame = work.removeLast()) {
                is FixedFrame.Evaluate -> when (val expression = frame.expression) {
                    is SExpr.Atom -> {
                        val name = symbol(expression.text)
                        val local = frame.locals[name]
                        when {
                            local != null -> stack += local
                            name in definitions -> apply(expression, emptyList())
                            expression.text == "true" -> stack += SmtValue.Boolean(true)
                            expression.text == "false" -> stack += SmtValue.Boolean(false)
                            expression.text.matches(INTEGER) -> stack += SmtValue.Integer(expression.text)
                            expression.text.matches(DECIMAL) -> stack += real(exactLiteral(expression.text))
                            expression.text.startsWith("#b") -> stack += SmtValue.BitVector(expression.text.drop(2))
                            expression.text.startsWith("#x") -> stack += hex(expression.text.drop(2))
                            name in constructors -> stack += SmtValue.Application(expression.text, emptyList())
                            name == "pi" -> stack += real(arithmetic.pi())
                            else -> stack += function(name, emptyList(), null)
                        }
                    }
                    is SExpr.SList -> when (expression.head()) {
                        "let" -> {
                            val bindings = (expression.items[1] as SExpr.SList).items.map { it as SExpr.SList }
                            work += FixedFrame.Let(bindings.map { symbol((it.items[0] as SExpr.Atom).text) },
                                expression.items[2], frame.locals)
                            bindings.asReversed().forEach { work += FixedFrame.Evaluate(it.items[1], frame.locals) }
                        }
                        "ite" -> {
                            work += FixedFrame.Choose(expression.items[2], expression.items[3], frame.locals)
                            work += FixedFrame.Evaluate(expression.items[1], frame.locals)
                        }
                        "match" -> {
                            work += FixedFrame.Match(expression.items[2] as SExpr.SList, frame.locals)
                            work += FixedFrame.Evaluate(expression.items[1], frame.locals)
                        }
                        "!" -> work += FixedFrame.Evaluate(expression.items[1], frame.locals)
                        "as" -> stack += qualifiedConstant(expression)
                        "_" -> stack += indexed(expression, emptyList())
                        else -> {
                            work += FixedFrame.Apply(expression.items[0], expression.items.size - 1)
                            expression.items.drop(1).asReversed().forEach {
                                work += FixedFrame.Evaluate(it, frame.locals)
                            }
                        }
                    }
                }
                is FixedFrame.Let -> work += FixedFrame.Evaluate(frame.body,
                    frame.locals + frame.names.zip(take(frame.names.size)))
                is FixedFrame.Choose -> work += FixedFrame.Evaluate(
                    if (boolean(stack.removeLast())) frame.yes else frame.no, frame.locals)
                is FixedFrame.Apply -> apply(frame.head, take(frame.count))
                is FixedFrame.Match -> {
                    val value = stack.removeLast() as SmtValue.Application
                    var selected = false
                    for (case in frame.cases.items) {
                        val pair = case as SExpr.SList
                        val pattern = pair.items[0]
                        val constructor = if (pattern is SExpr.Atom) symbol(pattern.text)
                            else (pattern as SExpr.SList).head()
                        val locals = when {
                            constructor == symbol(value.constructor) -> {
                                val names = (pattern as? SExpr.SList)?.items?.drop(1).orEmpty().map {
                                    symbol((it as SExpr.Atom).text)
                                }
                                frame.locals + names.zip(value.arguments)
                            }
                            pattern is SExpr.Atom && constructor !in constructors ->
                                if (constructor == "_") frame.locals else frame.locals + (constructor to value)
                            else -> continue
                        }
                        work += FixedFrame.Evaluate(pair.items[1], locals)
                        selected = true
                        break
                    }
                    if (!selected) unavailable("datatype match has no applicable case")
                }
            }
        }
        return stack.single()
    }

    private fun operation(head: SExpr, args: List<SmtValue>): SmtValue {
        if (head is SExpr.SList) {
            if (head.head() == "_") return indexed(head, args)
            if (head.head() == "as") {
                val selected = head.items[1]
                val scope = smtSessionScope(source, control)
                val qualification = scope.sourceSort(head.items[2])
                if (selected is SExpr.SList) return indexed(selected, args)
                val name = symbol((selected as SExpr.Atom).text)
                return if (model.values.any { symbol(it.first.name) == name }) function(name, args, qualification)
                    else operation(selected, args).let {
                        if (qualification == SmtSort.Real && it is SmtValue.Integer) real(exact(it)) else it
                    }
            }
            unavailable("invalid qualified operator")
        }
        val name = symbol((head as SExpr.Atom).text)
        fun bool(value: Boolean) = SmtValue.Boolean(value)
        fun equality(a: SmtValue, b: SmtValue): Boolean = values.equal(a, b)
            ?: unavailable("model equality is not certified")
        fun compare(a: SmtValue, b: SmtValue): Int = arithmetic.compare(exact(a), exact(b))
            ?: unavailable("model comparison is not certified")
        val result = when (name) {
            "and" -> bool(args.all(::boolean))
            "or" -> bool(args.any(::boolean))
            "not" -> bool(!boolean(args.single()))
            "xor" -> bool(args.count(::boolean) % 2 == 1)
            "=>" -> bool(args.dropLast(1).any { !boolean(it) } || boolean(args.last()))
            "=" -> bool(args.zipWithNext().all { equality(it.first, it.second) })
            "distinct" -> bool(args.indices.all { i -> (i + 1 until args.size).all { j ->
                !equality(args[i], args[j])
            } })
            "+" -> arithmetic(args, { xs -> xs.fold(BigFraction.ZERO) { a, b -> a + b } }) {
                arithmetic.add(it)
            }
            "*" -> arithmetic(args, { xs -> xs.fold(BigFraction.ONE) { a, b -> a * b } }) {
                arithmetic.multiply(it)
            }
            "-" -> arithmetic(args, { xs ->
                if (xs.size == 1) xs[0].negated() else xs.drop(1).fold(xs[0]) { a, b -> a - b }
            }) { xs -> if (xs.size == 1) arithmetic.negate(xs[0]) else
                arithmetic.add(listOf(xs[0]) + xs.drop(1).map(arithmetic::negate)) }
            "/" -> args.drop(1).fold(args[0]) { left, right ->
                if (compare(right, SmtValue.Integer("0")) == 0) underspecified("/", listOf(left, right))
                else real(arithmetic.multiply(listOf(exact(left), reciprocal(exact(right)))))
            }
            "div", "mod" -> {
                val a = integer(args[0])
                val b = integer(args[1])
                if (b.isZero()) underspecified(name, args) else {
                    var remainder = a.remainderOf(b)
                    if (remainder.signum() < 0) remainder += b.abs()
                    SmtValue.Integer((if (name == "mod") remainder else (a - remainder) / b).toString())
                }
            }
            "abs" -> if (compare(args.single(), SmtValue.Integer("0")) >= 0) args.single()
                else operation(SExpr.Atom("-"), args)
            "to_real" -> real(exact(args.single()))
            "to_int" -> SmtValue.Integer(floor(exact(args.single())).toString())
            "is_int" -> bool(compare(args.single(), SmtValue.Integer(floor(exact(args.single())).toString())) == 0)
            "^", "**" -> power(name, args)
            "<", "<=", ">", ">=" -> bool(args.zipWithNext().all { (a, b) ->
                val relation = compare(a, b)
                when (name) { "<" -> relation < 0; "<=" -> relation <= 0; ">" -> relation > 0; else -> relation >= 0 }
            })
            "exp", "log", "sin", "cos" -> real(arithmetic.function(
                ExactRealFunction.valueOf(name.uppercase()), exact(args.single())))
            "select" -> select(args[0] as SmtValue.Array, args[1])
            "store" -> (args[0] as SmtValue.Array).let { array ->
                array.copy(entries = array.entries + (args[1] to args[2]))
            }
            in constructors -> SmtValue.Application((head as SExpr.Atom).text, args)
            in selectors -> {
                val application = args.single() as? SmtValue.Application
                val selected = selectors.getValue(name).firstOrNull {
                    application != null && symbol(application.constructor) == it.first
                }
                if (selected != null) requireNotNull(application).arguments[selected.second]
                else underspecified("selector:$name", args)
            }
            else -> null
        }
        if (result != null) return result
        if (name.startsWith("bv") || name in setOf("concat", "ubv_to_int", "sbv_to_int", "bv2nat", "bv2int")) {
            return bitVector(name, emptyList(), args)
        }
        return function(name, args, null)
    }

    private fun function(name: String, args: List<SmtValue>, result: SmtSort?): SmtValue {
        val candidates = model.values.filter { (declaration, _) ->
            symbol(declaration.name) == name && declaration.parameters.size == args.size &&
                (result == null || declaration.result == result)
        }
        for ((declaration, value) in candidates) {
            if (!declaration.parameters.indices.all { accepts(declaration.parameters[it], args[it]) }) continue
            if (value !is SmtValue.Function) return value
            return table(value, args)
        }
        unavailable("the retained model has no interpretation for '$name'")
    }

    private fun table(table: SmtValue.Function, args: List<SmtValue>): SmtValue {
        for ((arguments, result) in table.entries) {
            checkpoint()
            var matches = true
            for (index in arguments.indices) {
                val same = values.equal(arguments[index], args[index])
                    ?: unavailable("function argument equality is not certified")
                if (!same) { matches = false; break }
            }
            if (matches) return result
        }
        return table.default
    }

    private fun underspecified(name: String, args: List<SmtValue>): SmtValue {
        val key = if (name.startsWith("selector:")) name else "operation:$name"
        val interpretation = runCatching { retained.value(key) }.getOrNull() as? SmtValue.Function
            ?: unavailable("the retained model has no total '$name' interpretation")
        return table(interpretation, args)
    }

    private fun select(array: SmtValue.Array, index: SmtValue): SmtValue {
        for ((key, value) in array.entries.asReversed()) {
            val same = values.equal(key, index) ?: unavailable("array index equality is not certified")
            if (same) return value
        }
        return array.default
    }

    private fun qualifiedConstant(expression: SExpr.SList): SmtValue {
        val selected = expression.items[1]
        val scope = smtSessionScope(source, control)
        val sort = scope.sourceSort(expression.items[2])
        if (selected is SExpr.SList) return indexed(selected, emptyList())
        val name = symbol((selected as SExpr.Atom).text)
        if (name.startsWith('@')) return SmtValue.Uninterpreted(sort, selected.text)
        if (name in constructors) return SmtValue.Application(selected.text, emptyList())
        return function(name, emptyList(), sort)
    }

    private fun indexed(identifier: SExpr.SList, args: List<SmtValue>): SmtValue {
        val name = (identifier.items[1] as SExpr.Atom).text
        if (name == "is") {
            val constructor = symbol((identifier.items[2] as SExpr.Atom).text)
            return SmtValue.Boolean((args.single() as SmtValue.Application).constructor.let(::symbol) == constructor)
        }
        if (name.startsWith("bv") && args.isEmpty()) {
            val width = (identifier.items[2] as SExpr.Atom).text.toInt()
            var value = parseBigInt(name.drop(2))
            val bits = CharArray(width)
            val two = BIG_ONE + BIG_ONE
            for (index in 0 until width) {
                checkpoint()
                bits[width - index - 1] = if (value.remainderOf(two).isZero()) '0' else '1'
                value /= two
            }
            return SmtValue.BitVector(bits.concatToString())
        }
        if (name == "divisible") {
            val divisor = parseBigInt((identifier.items[2] as SExpr.Atom).text)
            return SmtValue.Boolean(integer(args.single()).remainderOf(divisor).isZero())
        }
        val indices = identifier.items.drop(2).map { item ->
            val number = parseBigInt((item as SExpr.Atom).text)
            if (name in setOf("rotate_left", "rotate_right")) {
                number.remainderOf(parseBigInt((args.single() as SmtValue.BitVector).bits.length.toString())).toInt()
            } else number.toString().toInt()
        }
        return bitVector(name, indices, args)
    }

    private fun bitVector(name: String, indices: List<Int>, args: List<SmtValue>): SmtValue {
        val converted = args.map { value -> when (value) {
            is SmtValue.BitVector -> SmtBitVectorValue.Bits(value.bits.reversed().map { it == '1' })
            is SmtValue.Boolean -> SmtBitVectorValue.Boolean(value.value)
            is SmtValue.Integer -> SmtBitVectorValue.Integer(parseBigInt(value.value))
            else -> unavailable("bitvector operand has the wrong sort")
        } }
        return when (val value = evaluateSmtBitVector(name, indices, converted, ::checkpoint)) {
            is SmtBitVectorValue.Bits -> SmtValue.BitVector(value.bits.asReversed().joinToString("") {
                if (it) "1" else "0"
            })
            is SmtBitVectorValue.Boolean -> SmtValue.Boolean(value.value)
            is SmtBitVectorValue.Integer -> SmtValue.Integer(value.value.toString())
        }
    }

    private fun arithmetic(
        args: List<SmtValue>, rational: (List<BigFraction>) -> BigFraction,
        exact: (List<ExactRealValue>) -> ExactRealValue,
    ): SmtValue = if (args.all { it is SmtValue.Integer }) {
        SmtValue.Integer(rational(args.map { BigFraction.of(integer(it), BIG_ONE) }).num.toString())
    } else real(exact(args.map(::exact)))

    private fun exact(value: SmtValue): ExactRealValue = when (value) {
        is SmtValue.Integer -> arithmetic.rational(BigFraction.of(parseBigInt(value.value), BIG_ONE))
        is SmtValue.Real -> values.exactReal(value) ?: unavailable("real model value has no retained exact authority")
        else -> unavailable("expected an arithmetic model value")
    }
    private fun exactLiteral(text: String): ExactRealValue = smtRationalValue(text, control)
        ?: unavailable("invalid exact real literal")
    private fun real(value: ExactRealValue): SmtValue.Real = SmtValue.Real(smtSymbolicTerm(value, control)).also {
        authorities[it] = value
    }
    private fun reciprocal(value: ExactRealValue): ExactRealValue {
        value.algebraic?.let { return arithmetic.scalar(arithmetic.algebraic.reciprocal(it)) }
        val sign = arithmetic.compare(value, arithmetic.rational(BigFraction.ZERO))
            ?: unavailable("divisor sign is not certified")
        if (sign == 0) unavailable("zero divisor requires a retained interpretation")
        val magnitude = if (sign < 0) arithmetic.negate(value) else value
        val inverse = arithmetic.function(ExactRealFunction.EXP,
            arithmetic.negate(arithmetic.function(ExactRealFunction.LOG, magnitude)))
        return if (sign < 0) arithmetic.negate(inverse) else inverse
    }
    private fun floor(value: ExactRealValue): com.eignex.util.numbers.BigInt {
        fun rationalFloor(number: BigFraction) = (number.num / number.den).let {
            if (number.signum() < 0 && !number.num.remainderOf(number.den).isZero()) it - BIG_ONE else it
        }
        value.rational?.let { return rationalFloor(it) }
        var terms = 4
        while (true) {
            checkpoint()
            val interval = arithmetic.enclose(value, terms)
            val low = rationalFloor(interval.lower)
            val high = rationalFloor(interval.upper)
            if (low == high) return low
            val endpoint = arithmetic.rational(BigFraction.of(high, BIG_ONE))
            if (arithmetic.compare(value, endpoint) == 0) return high
            if (terms > 16_384) unavailable("floor certification resource limit")
            terms *= 2
        }
    }
    private fun power(name: String, args: List<SmtValue>): SmtValue {
        var exponent = integer(args[1])
        if (name == "**" && exponent.signum() < 0) {
            val base = integer(args[0])
            val result = when {
                base == BIG_ONE -> BIG_ONE
                base == -BIG_ONE -> if (exponent.remainderOf(BIG_ONE + BIG_ONE).isZero()) BIG_ONE else -BIG_ONE
                else -> BIG_ZERO
            }
            return SmtValue.Integer(result.toString())
        }
        if (exponent.signum() < 0) unavailable("negative real power is outside this extension")
        if (args[0] is SmtValue.Integer) {
            var base = integer(args[0])
            var result = BIG_ONE
            val two = BIG_ONE + BIG_ONE
            while (exponent > BIG_ZERO) {
                checkpoint()
                if (!exponent.remainderOf(two).isZero()) result *= base
                exponent /= two
                if (exponent > BIG_ZERO) base *= base
            }
            return SmtValue.Integer(result.toString())
        }
        return real(arithmetic.power(exact(args[0]), exponent.toString().toInt()))
    }
    private fun datatype(expression: SExpr) {
        val body = expression as SExpr.SList
        val declarations = if (body.head() == "par") (body.items[2] as SExpr.SList).items else body.items
        for (item in declarations) {
            val constructor = item as SExpr.SList
            val name = symbol((constructor.items[0] as SExpr.Atom).text)
            constructors += name
            constructor.items.drop(1).forEachIndexed { index, field ->
                val selector = symbol(((field as SExpr.SList).items[0] as SExpr.Atom).text)
                selectors[selector] = selectors[selector].orEmpty() + (name to index)
            }
        }
    }
    private fun accepts(sort: SmtSort, value: SmtValue): Boolean = when (sort) {
        SmtSort.Bool -> value is SmtValue.Boolean
        SmtSort.Int -> value is SmtValue.Integer
        SmtSort.Real -> numeric(value)
        is SmtSort.BitVector -> value is SmtValue.BitVector && value.bits.length == sort.width
        is SmtSort.Array -> value is SmtValue.Array && value.sort == sort
        is SmtSort.Uninterpreted -> value is SmtValue.Uninterpreted && value.sort == sort ||
            value is SmtValue.Application
    }
    private fun hex(text: String): SmtValue.BitVector = SmtValue.BitVector(buildString {
        for (digit in text) append(digit.digitToInt(16).toString(2).padStart(4, '0'))
    })
    private fun boolean(value: SmtValue): Boolean = (value as? SmtValue.Boolean)?.value
        ?: unavailable("expected a Boolean model value")
    private fun integer(value: SmtValue) = parseBigInt((value as? SmtValue.Integer)?.value
        ?: unavailable("expected an integer model value"))
    private fun numeric(value: SmtValue): Boolean = value is SmtValue.Integer || value is SmtValue.Real
    private fun symbol(text: String): String = smtSessionSymbol(text)
    private fun SExpr.SList.head(): String = (items.firstOrNull() as? SExpr.Atom)?.text?.let(::symbol).orEmpty()
    private fun list(head: String, vararg args: SExpr): SExpr = SExpr.SList(listOf(SExpr.Atom(head)) + args)
    private fun unavailable(message: String): Nothing = throw UnsupportedSmtException(message)
    private fun checkpoint(units: Long = 1) {
        queryWork += units
        if (queryWork > 100_000) unavailable("model evaluation resource limit")
        retained.checkpoint(units)
    }
    private companion object {
        val INTEGER = Regex("0|[1-9][0-9]*")
        val DECIMAL = Regex("(0|[1-9][0-9]*)\\.[0-9]+")
    }
}

private sealed interface FixedFrame {
    data class Evaluate(val expression: SExpr, val locals: Map<String, SmtValue>) : FixedFrame
    data class Apply(val head: SExpr, val count: Int) : FixedFrame
    data class Choose(val yes: SExpr, val no: SExpr, val locals: Map<String, SmtValue>) : FixedFrame
    data class Let(val names: List<String>, val body: SExpr, val locals: Map<String, SmtValue>) : FixedFrame
    data class Match(val cases: SExpr.SList, val locals: Map<String, SmtValue>) : FixedFrame
}
