package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.real.ExactRealFunction
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigFraction
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

internal class SmtFixedModel(
    private val source: List<SExpr>,
    private val model: SmtSourceModel,
    private val retained: SmtModelValues,
    private val control: ExecutionControl,
    private val originalSource: SmtSourceSnapshot? = null,
) : SmtSessionModel {
    private data class Definition(
        val parameters: List<String>, val declaration: SmtSourceDeclaration, val body: SExpr,
    )
    private val definitions = LinkedHashMap<String, Definition>()
    private val constructors = HashSet<String>()
    private val selectors = HashMap<String, List<Pair<String, Int>>>()
    private val authorities = HashMap<SmtValue.Real, ExactRealValue>()
    private val queryValues = ArrayList<SmtValue>()
    private var queryWork = 0L
    private val arithmetic by lazy { smtExactArithmetic(control) }
    private val sourceScope by lazy { smtSessionScope(source, control) }
    private val declaredNames by lazy { sourceScope.declarations.map { symbol(it.name) }.toSet() }
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
                        Definition(parameters, SmtSourceDeclaration((command.items[1] as SExpr.Atom).text,
                            if (constant) emptyList() else (command.items[2] as SExpr.SList).items.map {
                                sourceScope.sourceSort((it as SExpr.SList).items[1])
                            }, sourceScope.sourceSort(command.items[if (constant) 2 else 3])),
                            command.items[if (constant) 3 else 4])
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
                            Definition(parameters, SmtSourceDeclaration((signature.items[0] as SExpr.Atom).text,
                                (signature.items[1] as SExpr.SList).items.map {
                                    sourceScope.sourceSort((it as SExpr.SList).items[1])
                                }, sourceScope.sourceSort(signature.items[2])), bodies[index])
                    }
                }
                "declare-datatype" -> datatype(command.items[2])
                "declare-datatypes" -> (command.items[2] as SExpr.SList).items.forEach(::datatype)
            }
        }
    }

    override fun withRecursiveDefinitions(source: List<SExpr>): SmtSessionModel {
        val declared = source.mapNotNull { expression ->
            val command = expression as? SExpr.SList
            if (command?.head() in setOf("declare-const", "declare-fun")) {
                symbol((command!!.items[1] as SExpr.Atom).text)
            } else null
        }.toSet()
        val names = source.flatMap { expression ->
            val command = expression as? SExpr.SList
            when (command?.head()) {
                "define-fun-rec" -> listOf(symbol((command.items[1] as SExpr.Atom).text))
                "define-funs-rec" -> (command.items[1] as SExpr.SList).items.map {
                    symbol(((it as SExpr.SList).items[0] as SExpr.Atom).text)
                }
                else -> emptyList()
            }
        }.toSet()
        val ordinary = SmtSourceModel(model.values.filter {
            symbol(it.first.name) in declared && symbol(it.first.name) !in names
        })
        return SmtFixedModel(source, ordinary, retained, control, originalSource)
    }

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
            while (pending.isNotEmpty()) {
                when (val term = pending.removeLast()) {
                    is SExpr.Atom -> if (symbol(term.text) in parameters) polymorphic = true
                    is SExpr.SList -> pending.addAll(term.items)
                }
            }
            definition.takeUnless { polymorphic }
        }
        return SExpr.SList(response.items + additions).smtText()
    }

    override fun abstractValues(): Map<String, SmtSort> = buildMap {
        val work = ArrayDeque<SmtValue>()
        model.values.forEach { work += it.second }
        work.addAll(queryValues)
        while (work.isNotEmpty()) {
            when (val value = work.removeLast()) {
                is SmtValue.Uninterpreted -> put(symbol(value.identity), value.sort)
                is SmtValue.Application -> work.addAll(value.arguments)
                is SmtValue.Array -> {
                    work += value.default
                    value.entries.forEach { (index, element) ->
                        work += index
                        work += element
                    }
                }
                is SmtValue.Function -> {
                    work += value.default
                    value.entries.forEach { (arguments, result) ->
                        work.addAll(arguments)
                        work += result
                    }
                }
                else -> Unit
            }
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
        queryValues += value
        return value.toSmtLib()
    }

    private fun evaluateValue(term: SExpr): SmtValue {
        val work = ArrayDeque<FixedFrame>()
        val stack = ArrayDeque<SmtValue>()
        fun take(count: Int): List<SmtValue> = List(count) { stack.removeLast() }.reversed()
        fun apply(head: SExpr, args: List<SmtValue>) {
            val qualified = (head as? SExpr.SList)?.takeIf { it.syntaxHead() == "as" }
            val selected = qualified?.items?.get(1) ?: head
            val name = (selected as? SExpr.Atom)?.text?.let(::symbol)
            val definition = definitions[name]
            if (definition != null) {
                if (args.size != definition.parameters.size) unavailable("function arity mismatch")
                val qualification = qualified?.let { sourceScope.sourceSort(it.items[2]) }
                val declaration = definition.declaration
                val rank = instantiateSmtRank(declaration.parameters, declaration.result,
                    sourceScope.sortParameters, args.map(::valueSort), qualification, control)
                    ?: unavailable("helper rank cannot be instantiated")
                val substitution = sourceScope.sortParameters.associateWith { SmtSort.Bool } +
                    smtRankSubstitution(declaration, rank.first, rank.second, sourceScope.sortParameters, control)
                val body = specializeBody(definition.body, substitution)
                work += FixedFrame.Evaluate(body, definition.parameters.zip(args).toMap())
            } else stack += operation(head, args)
        }
        work += FixedFrame.Evaluate(term, emptyMap())
        while (work.isNotEmpty()) {
            checkpoint()
            when (val frame = work.removeLast()) {
                is FixedFrame.Evaluate -> when (val expression = frame.expression) {
                    is SExpr.Atom -> {
                        val literal = literalValue(expression.text)
                        if (literal != null) {
                            stack += literal
                            continue
                        }
                        val name = symbol(expression.text)
                        val local = frame.locals[name]
                        when {
                            local != null -> stack += local
                            name in definitions -> apply(expression, emptyList())
                            name in constructors -> stack += constructorValue(name, emptyList(), null)
                            name in declaredNames -> stack += function(name, emptyList(), null)
                            name == "pi" -> stack += real(arithmetic.pi())
                            else -> stack += function(name, emptyList(), null)
                        }
                    }
                    is SExpr.SList -> when (expression.syntaxHead().let {
                        if (symbol(it) == "ite") "ite" else it
                    }) {
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
                        "as" -> {
                            val selected = expression.items[1] as? SExpr.Atom
                            if (selected != null && symbol(selected.text) in definitions) apply(expression, emptyList())
                            else stack += qualifiedConstant(expression)
                        }
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
                            constructor == constructorSymbol(value.constructor) -> {
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
            if (head.syntaxHead() == "_") return indexed(head, args)
            if (head.syntaxHead() == "as") {
                val selected = head.items[1]
                val qualification = sourceScope.sourceSort(head.items[2])
                if (selected is SExpr.SList) return indexed(selected, args)
                val name = symbol((selected as SExpr.Atom).text)
                if (name in constructors) return constructorValue(name, args, qualification)
                if (name in selectors) return selectorValue(name, args.single(), qualification)
                return if (model.values.any { symbol(it.first.name) == name }) function(name, args, qualification)
                else operation(selected, args).let {
                    if (qualification == SmtSort.Real && it is SmtValue.Integer) real(exact(it)) else it
                }
            }
            unavailable("invalid qualified operator")
        }
        val name = symbol((head as SExpr.Atom).text)
        if (name in declaredNames) return function(name, args, null)
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
            "distinct" -> bool(args.indices.all { i ->
                (i + 1 until args.size).all { j ->
                    !equality(args[i], args[j])
                }
            })
            "+" -> arithmetic(args, { xs -> xs.fold(BigFraction.ZERO) { a, b -> a + b } }) {
                arithmetic.add(it)
            }
            "*" -> arithmetic(args, { xs -> xs.fold(BigFraction.ONE) { a, b -> a * b } }) {
                arithmetic.multiply(it)
            }
            "-" -> arithmetic(args, { xs ->
                if (xs.size == 1) xs[0].negated() else xs.drop(1).fold(xs[0]) { a, b -> a - b }
            }) { xs ->
                if (xs.size == 1) arithmetic.negate(xs[0])
                else arithmetic.add(listOf(xs[0]) + xs.drop(1).map(arithmetic::negate))
            }
            "/" -> args.drop(1).fold(args[0]) { left, right ->
                if (compare(right, SmtValue.Integer("0")) == 0) underspecified("/", listOf(left, right))
                else real(arithmetic.multiply(listOf(exact(left), reciprocal(exact(right)))))
            }
            "div" -> args.drop(1).fold(args[0]) { a, b -> integerDivision(name, a, b) }
            "mod" -> integerDivision(name, args[0], args[1])
            "abs" -> if (compare(args.single(), SmtValue.Integer("0")) >= 0) args.single()
            else operation(SExpr.Atom("-"), args)
            "to_real" -> real(exact(args.single()))
            "to_int" -> SmtValue.Integer(floor(exact(args.single())).toString())
            "is_int" -> bool(compare(args.single(), SmtValue.Integer(floor(exact(args.single())).toString())) == 0)
            "^", "**" -> power(name, args)
            "<", "<=", ">", ">=" -> bool(args.zipWithNext().all { (a, b) ->
                val relation = compare(a, b)
                when (name) {
                    "<" -> relation < 0
                    "<=" -> relation <= 0
                    ">" -> relation > 0
                    else -> relation >= 0
                }
            })
            "exp", "log", "sin", "cos" -> real(arithmetic.function(
                ExactRealFunction.valueOf(name.uppercase()), exact(args.single())))
            "select" -> select(args[0] as SmtValue.Array, args[1])
            "store" -> (args[0] as SmtValue.Array).let { array ->
                array.copy(entries = array.entries + (args[1] to args[2]))
            }
            in constructors -> constructorValue(name, args, null)
            in selectors -> selectorValue(name, args.single(), null)
            else -> null
        }
        if (result != null) return result
        if (name.startsWith("bv") || name in setOf("concat", "ubv_to_int", "sbv_to_int", "bv2nat", "bv2int")) {
            return bitVector(name, emptyList(), args)
        }
        return function(name, args, null)
    }

    private fun integerDivision(name: String, left: SmtValue, right: SmtValue): SmtValue {
        val a = integer(left)
        val b = integer(right)
        return if (b.isZero()) underspecified(name, listOf(left, right)) else {
            var remainder = a.remainderOf(b)
            if (remainder.signum() < 0) remainder += b.abs()
            SmtValue.Integer((if (name == "mod") remainder else (a - remainder) / b).toString())
        }
    }

    private fun constructorValue(name: String, args: List<SmtValue>, qualification: SmtSort?): SmtValue {
        val registry = sourceScope.datatypes
        val selected = registry.resolve(name, args.map(::valueSort), qualification)
            ?: unavailable("constructor rank is not determined")
        return SmtValue.Application(registry.constructorName(selected.instance, selected.constructor), args)
    }

    private fun constructorSymbol(text: String): String = when (val identifier = SExprReader(text).readAll().single()) {
        is SExpr.Atom -> symbol(identifier.text)
        is SExpr.SList -> symbol((identifier.items[1] as SExpr.Atom).text)
    }

    private fun selectorValue(name: String, argument: SmtValue, qualification: SmtSort?): SmtValue {
        val registry = sourceScope.datatypes
        val argumentSort = valueSort(argument)
        val operator = registry.resolve(name, listOf(argumentSort), qualification)
            ?.takeIf { it.selector != null } ?: unavailable("selector rank is not determined")
        val constructor = operator.instance.constructors[operator.constructor]
        if (argument is SmtValue.Application && constructorSymbol(argument.constructor) == symbol(constructor.name)) {
            return argument.arguments[requireNotNull(operator.selector)]
        }
        val key = smtSelectorInterpretationKey(name, argumentSort, operator.result)
        val retainedTable = retainedFunction(key)
        val interpretation = retainedTable ?: smtUnobservedSelectorInterpretation(
            registry, name, argumentSort, operator.result, values)
            ?: unavailable("the retained model has no total '$name' interpretation at this rank")
        return table(interpretation, listOf(argument))
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
        val snapshot = originalSource
        val schema = snapshot?.typed?.symbols?.firstOrNull {
            symbol(it.declaration.name) == name && it.definition == null
        }?.declaration
        if (schema != null) {
            val parameters = args.map(::valueSort)
            val rank = instantiateSmtRank(schema.parameters, schema.result,
                snapshot.typed.sortParameters, parameters, result, control)
            if (rank != null) {
                val value = snapshot.polymorphicModel?.instantiate(name, rank.first, rank.second, values,
                    ::familyPoint)
                if (value != null) return if (value is SmtValue.Function) table(value, args) else value
            }
        }
        unavailable("the retained model has no interpretation for '$name'")
    }

    private fun valueSort(value: SmtValue): SmtSort = when (value) {
        is SmtValue.Boolean -> SmtSort.Bool
        is SmtValue.Integer -> SmtSort.Int
        is SmtValue.Real -> SmtSort.Real
        is SmtValue.BitVector -> SmtSort.BitVector(value.bits.length)
        is SmtValue.Array -> value.sort
        is SmtValue.Uninterpreted -> value.sort
        is SmtValue.Application -> {
            val identifier = SExprReader(value.constructor).readAll().single()
            if (identifier is SExpr.SList && identifier.syntaxHead() == "as")
                sourceScope.sourceSort(identifier.items[2])
            else sourceScope.datatypes.resolve(symbol((identifier as SExpr.Atom).text),
                value.arguments.map(::valueSort))
                ?.result ?: unavailable("constructor result sort is not determined")
        }
        is SmtValue.Function -> unavailable("functions cannot be first-order arguments")
    }

    private fun familyPoint(sort: SmtSort, second: Boolean): SmtValue? {
        checkpoint()
        return when (sort) {
            is SmtSort.Array -> familyPoint(sort.element, second)?.let { SmtValue.Array(sort, it, emptyList()) }
            is SmtSort.Uninterpreted -> {
                val registry = originalSource?.typed?.datatypes
                if (registry?.instance(sort) != null) registry.modelPoint(sort, second, values)
                else smtPolymorphicPoint(sort, second, values)
            }
            else -> smtPolymorphicPoint(sort, second, values)
        }
    }

    private fun specializeBody(root: SExpr, substitution: Map<String, SmtSort>): SExpr {
        if (substitution.isEmpty()) return root
        val work = ArrayDeque<Pair<SExpr, Boolean>>()
        val expressions = ArrayDeque<SExpr>()
        work += root to false
        while (work.isNotEmpty()) {
            checkpoint()
            val (expression, combine) = work.removeLast()
            when (expression) {
                is SExpr.Atom -> expressions += expression
                is SExpr.SList -> if (combine) {
                    val items = List(expression.items.size) { expressions.removeLast() }.reversed().toMutableList()
                    if (expression.syntaxHead() == "as") {
                        val sort = substituteSmtSort(sourceScope.sourceSort(items[2]), substitution, control)
                        items[2] = SExprReader(sort.toSmtLib()).readAll().single()
                    }
                    expressions += SExpr.SList(items)
                } else {
                    work += expression to true
                    expression.items.asReversed().forEach { work += it to false }
                }
            }
        }
        return expressions.single()
    }

    private fun table(table: SmtValue.Function, args: List<SmtValue>): SmtValue {
        for ((arguments, result) in table.entries) {
            checkpoint()
            var matches = true
            for (index in arguments.indices) {
                val same = values.equal(arguments[index], args[index])
                    ?: unavailable("function argument equality is not certified")
                if (!same) {
                    matches = false
                    break
                }
            }
            if (matches) return result
        }
        return table.default
    }

    private fun underspecified(name: String, args: List<SmtValue>): SmtValue {
        val interpretation = retainedFunction("@operation:$name")
            ?: unavailable("the retained model has no total '$name' interpretation")
        return table(interpretation, args)
    }

    private fun retainedFunction(key: String): SmtValue.Function? = try {
        retained.value(key) as? SmtValue.Function
    } catch (_: IllegalStateException) {
        null
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
        val sort = sourceScope.sourceSort(expression.items[2])
        if (selected is SExpr.SList) return indexed(selected, emptyList())
        literalValue((selected as SExpr.Atom).text)?.let { return it }
        val name = symbol(selected.text)
        if (name.startsWith('@')) return SmtValue.Uninterpreted(sort, selected.text)
        if (name in constructors) return constructorValue(name, emptyList(), sort)
        return function(name, emptyList(), sort)
    }

    private fun indexed(identifier: SExpr.SList, args: List<SmtValue>): SmtValue {
        val name = symbol((identifier.items[1] as SExpr.Atom).text)
        if (name == "is") {
            val constructor = symbol((identifier.items[2] as SExpr.Atom).text)
            val value = args.single() as SmtValue.Application
            return SmtValue.Boolean(constructorSymbol(value.constructor) == constructor)
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
                val width = (args.single() as SmtValue.BitVector).bits.length
                number.remainderOf(parseBigInt(width.toString())).toString().toInt()
            } else number.toString().toInt()
        }
        return bitVector(name, indices, args)
    }

    private fun bitVector(name: String, indices: List<Int>, args: List<SmtValue>): SmtValue {
        val converted = args.map { value ->
            when (value) {
                is SmtValue.BitVector -> SmtBitVectorValue.Bits(value.bits.reversed().map { it == '1' })
                is SmtValue.Boolean -> SmtBitVectorValue.Boolean(value.value)
                is SmtValue.Integer -> SmtBitVectorValue.Integer(parseBigInt(value.value))
                else -> unavailable("bitvector operand has the wrong sort")
            }
        }
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
    private fun literalValue(text: String): SmtValue? = when (sourceScope.literalSort(text)) {
        SmtSort.Bool -> SmtValue.Boolean(symbol(text) == "true")
        SmtSort.Int -> SmtValue.Integer(text)
        SmtSort.Real -> real(exactLiteral(text))
        is SmtSort.BitVector -> if (text.startsWith("#b")) SmtValue.BitVector(text.drop(2)) else hex(text.drop(2))
        else -> null
    }
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
            if (base != BIG_ZERO && base != BIG_ONE && base != -BIG_ONE &&
                exponent > parseBigInt("100000")) unavailable("integer power representation limit")
            var result = BIG_ONE
            val two = BIG_ONE + BIG_ONE
            while (exponent > BIG_ZERO) {
                checkpoint()
                if (!exponent.remainderOf(two).isZero()) {
                    checkpoint(base.toString().length.toLong() + result.toString().length)
                    result *= base
                }
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
        is SmtSort.Uninterpreted -> (value is SmtValue.Uninterpreted && value.sort == sort) ||
            (value is SmtValue.Application && valueSort(value) == sort)
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
    private fun SExpr.SList.syntaxHead(): String = (items.firstOrNull() as? SExpr.Atom)?.text.orEmpty()
    private fun list(head: String, vararg args: SExpr): SExpr = SExpr.SList(listOf(SExpr.Atom(head)) + args)
    private fun unavailable(message: String): Nothing = throw UnsupportedSmtException(message)
    private fun checkpoint(units: Long = 1) {
        queryWork += units
        if (queryWork > 100_000) unavailable("model evaluation resource limit")
        retained.checkpoint(units)
    }
}

private sealed interface FixedFrame {
    data class Evaluate(val expression: SExpr, val locals: Map<String, SmtValue>) : FixedFrame
    data class Apply(val head: SExpr, val count: Int) : FixedFrame
    data class Choose(val yes: SExpr, val no: SExpr, val locals: Map<String, SmtValue>) : FixedFrame
    data class Let(val names: List<String>, val body: SExpr, val locals: Map<String, SmtValue>) : FixedFrame
    data class Match(val cases: SExpr.SList, val locals: Map<String, SmtValue>) : FixedFrame
}
