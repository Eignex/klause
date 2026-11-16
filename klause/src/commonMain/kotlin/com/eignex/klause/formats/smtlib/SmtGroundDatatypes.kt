package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal data class SmtDatatypeCoordinate(val name: String, val sort: SmtSort.Uninterpreted)
internal data class SmtDatatypeAlternative(val constructor: Int, val fields: List<String>)
internal data class SmtDatatypeShape(val coordinate: SmtDatatypeCoordinate, val tag: String,
    val alternatives: List<SmtDatatypeAlternative>)
internal data class SmtDatatypeSelector(val coordinate: SmtDatatypeCoordinate, val constructor: Int,
    val field: Int, val result: String)
internal data class SmtDatatypeReduction(val registry: SmtDatatypes,
    val shapes: List<SmtDatatypeShape>, val selectors: List<SmtDatatypeSelector>)

internal fun exposeSmtGroundDatatypes(input: SmtReduction, cancellation: ExecutionControl): SmtReduction =
    if (input.commands.any { (it as? SExpr.SList)?.polynomialHead() in
            setOf("declare-datatype", "declare-datatypes") }) SmtGroundDatatypeReducer(input, cancellation).reduce()
    else input

private class SmtGroundDatatypeReducer(private val input: SmtReduction, private val cancellation: ExecutionControl) {
    private data class Term(val sort: SmtSort, val expression: SExpr)
    private data class Function(val parameters: List<SmtSort>, val result: SmtSort)
    private data class Macro(val parameters: List<String>, val body: SExpr, val environment: Environment)
    private data class Environment(val symbols: Map<String, Term>, val functions: Map<String, Function>,
        val macros: Map<String, Macro>)
    private val scope = SmtLibScope(cancellation)
    private val registry get() = scope.datatypes
    private val symbols = LinkedHashMap<String, Term>()
    private val functions = LinkedHashMap<String, Function>()
    private val macros = LinkedHashMap<String, Macro>()
    private val output = ArrayList<SExpr>()
    private val axioms = ArrayList<SExpr>()
    private val generated = ArrayList<SExpr>()
    private val usedNames = HashSet<String>()
    private val privateBindings = input.privateBindings.toMutableMap()
    private val captures = HashMap<Pair<SExpr, SmtSort>, Term>()
    private val operationNames = HashMap<Pair<SmtSort, String>, String>()
    private val defined = LinkedHashMap<Pair<SExpr, SmtSort>, SmtDatatypeShape>()
    private val explicit = ArrayList<SmtDatatypeShape>()
    private val selectors = LinkedHashSet<SmtDatatypeSelector>()
    private var nextName = 0

    fun reduce(): SmtReduction {
        collectNames()
        for (expression in input.commands) {
            step()
            val command = expression as SExpr.SList
            when (command.polynomialHead()) {
                "declare-datatype", "declare-datatypes" -> {
                    scope.command(command)
                    for ((name, arity) in registry.headers(command)) {
                        output += form("declare-sort", SExpr.Atom(name), SExpr.Atom(arity.toString()))
                    }
                }
                "declare-sort", "define-sort" -> { scope.command(command); output += command }
                "declare-const", "declare-fun" -> {
                    val name = command.polynomialName(1)
                    val parameters = if (command.polynomialHead() == "declare-fun") {
                        (command.items[2] as SExpr.SList).items.map(scope::sourceSort)
                    } else emptyList()
                    val result = scope.sourceSort(command.items.last())
                    scope.command(command)
                    if (parameters.isEmpty()) symbols[name] = Term(result, command.items[1])
                    else functions[name] = Function(parameters, result)
                    output += command
                }
                "define-fun" -> {
                    scope.command(command)
                    val parameters = (command.items[2] as SExpr.SList).items.map {
                        (it as SExpr.SList).polynomialName(0)
                    }
                    macros[command.polynomialName(1)] = Macro(parameters, command.items[4], environment())
                }
                "assert", "minimize", "maximize" -> {
                    output += form(command.items[0], rewrite(command.items[1], environment()).expression)
                }
                "check-sat-assuming", "get-value" -> {
                    val terms = (command.items[1] as SExpr.SList).items.map { rewrite(it, environment()).expression }
                    output += form(command.items[0], SExpr.SList(terms))
                }
                else -> output += command
            }
        }
        val declarations = output.filter { (it as? SExpr.SList)?.polynomialHead() in
            setOf("declare-sort", "define-sort", "declare-const", "declare-fun") }
        val remaining = output.filter { it !in declarations }
        val query = remaining.indexOfFirst { (it as? SExpr.SList)?.polynomialHead() in
            setOf("check-sat", "check-sat-assuming", "get-model", "get-value", "exit") }
        val body = remaining.toMutableList()
        body.addAll(if (query < 0) body.size else query, axioms)
        return input.copy(commands = declarations + generated + body, privateBindings = privateBindings.toMap(),
            datatypes = SmtDatatypeReduction(registry.snapshot(ExecutionControl.Never), explicit.toList() + defined.values,
                selectors.toList()))
    }

    private fun environment() = Environment(symbols.toMap(), functions.toMap(), macros.toMap())

    private fun rewrite(expression: SExpr, environment: Environment): Term {
        step()
        if (expression is SExpr.Atom) {
            val name = polynomialSymbol(expression.text)
            environment.symbols[name]?.let { return it }
            environment.macros[name]?.takeIf { it.parameters.isEmpty() }?.let {
                return rewrite(it.body, it.environment)
            }
            registry.resolve(name, emptyList())?.let { return constructor(it, emptyList()) }
            return literal(expression)
        }
        expression as SExpr.SList
        val headExpression = expression.items.firstOrNull() ?: smtUnsupported("empty datatype expression")
        val identifier = headExpression as? SExpr.SList
        val tester = identifier?.let {
            it.polynomialHead() == "_" && it.items.size == 3 && it.polynomialAtom(1) == "is"
        } == true
        val name = when {
            tester -> identifier!!.polynomialName(2)
            identifier?.polynomialHead() == "as" -> identifier.polynomialName(1)
            headExpression is SExpr.Atom -> polynomialSymbol(headExpression.text)
            else -> null
        }
        if (name == "let") {
            val bindings = (expression.items[1] as SExpr.SList).items.map { it as SExpr.SList }
            val locals = bindings.associate { it.polynomialName(0) to rewrite(it.items[1], environment) }
            return rewrite(expression.items[2], environment.copy(symbols = environment.symbols + locals))
        }
        if (name == "!") return rewrite(expression.items[1], environment)
        if (name == "match") return match(expression, environment)
        if (name == "as") {
            val constant = expression.polynomialName(1)
            val sort = scope.sourceSort(expression.items[2])
            environment.symbols[constant]?.let { return it }
            val operator = registry.resolve(constant, emptyList(), sort)
                ?: smtUnsupported("ambiguous qualified datatype constant")
            return constructor(operator, emptyList())
        }
        if (name == "_") {
            val width = expression.polynomialAtom(2).toIntOrNull()
                ?: smtUnsupported("invalid indexed bitvector constant")
            return Term(SmtSort.BitVector(width), expression)
        }
        val arguments = expression.items.drop(1).map { rewrite(it, environment) }
        environment.macros[name]?.let { macro ->
            return rewrite(macro.body, macro.environment.copy(symbols = macro.environment.symbols +
                macro.parameters.zip(arguments)))
        }
        val qualification = identifier?.takeIf { it.polynomialHead() == "as" }?.let { scope.sourceSort(it.items[2]) }
        registry.resolve(name ?: "", arguments.map { it.sort }, qualification, tester)?.let { operator ->
            return when {
                operator.tester -> {
                    val value = capture(arguments.single())
                    val shape = define(value)
                    Term(SmtSort.Bool, form("=", SExpr.Atom(shape.tag), SExpr.Atom(operator.constructor.toString())))
                }
                operator.selector != null -> {
                    val value = capture(arguments.single())
                    define(value)
                    operation(operator.instance, "selector:${operator.constructor}:${operator.selector}",
                        listOf(value), operator.result)
                }
                else -> constructor(operator, arguments)
            }
        }
        val sort = environment.functions[name]?.result ?: resultSort(headExpression, arguments.map { it.sort })
        return Term(sort, form(headExpression, *arguments.map { it.expression }.toTypedArray()))
    }

    private fun match(expression: SExpr.SList, environment: Environment): Term {
        val scrutinee = capture(rewrite(expression.items[1], environment))
        val instance = registry.instance(scrutinee.sort) ?: smtUnsupported("match expects datatype")
        val shape = define(scrutinee)
        val cases = registry.cases(scrutinee.sort, expression.items[2])
        val branches = cases.map { case ->
            val arguments = if (case.constructor == null) listOf(scrutinee) else {
                instance.constructors[case.constructor].fields.mapIndexed { index, field ->
                    operation(instance, "selector:${case.constructor}:$index", listOf(scrutinee), field.sort)
                }
            }
            rewrite(case.body, environment.copy(symbols = environment.symbols + case.variables.zip(arguments)))
        }
        var result = branches.last().expression
        for (index in cases.lastIndex - 1 downTo 0) {
            val constructor = cases[index].constructor ?: smtUnsupported("non-final catch-all pattern")
            result = form("ite", form("=", SExpr.Atom(shape.tag), SExpr.Atom(constructor.toString())),
                branches[index].expression, result)
        }
        val sort = if (branches.any { it.sort == SmtSort.Real }) SmtSort.Real else branches.first().sort
        return Term(sort, result)
    }

    private fun constructor(operator: SmtDatatypeOperator, arguments: List<Term>): Term {
        val values = arguments.mapIndexed { index, argument ->
            val expected = operator.arguments[index]
            capture(if (expected == SmtSort.Real && argument.sort == SmtSort.Int)
                Term(SmtSort.Real, form("to_real", argument.expression)) else argument)
        }
        val result = operation(operator.instance, "constructor:${operator.constructor}", values, operator.instance.sort)
        val tag = operation(operator.instance, "tag", listOf(result), SmtSort.Int)
        val conditions = constructorSpec(operator.instance, operator.constructor, result, values, tag)
        axioms += form("assert", form("and", *conditions.toTypedArray()))
        explicit += SmtDatatypeShape(SmtDatatypeCoordinate(result.name(), operator.instance.sort), tag.name(),
            listOf(SmtDatatypeAlternative(operator.constructor, values.map { it.name() })))
        return result
    }

    private fun define(value: Term): SmtDatatypeShape = defined.getOrPut(value.expression to value.sort) {
        val instance = registry.instance(value.sort) ?: smtUnsupported("datatype constructor expected")
        val tag = operation(instance, "tag", listOf(value), SmtSort.Int)
        val alternatives = ArrayList<SmtDatatypeAlternative>()
        val disjunction = instance.constructors.mapIndexed { index, constructor ->
            step()
            val fields = constructor.fields.map { fresh(it.sort) }
            alternatives += SmtDatatypeAlternative(index, fields.map { it.name() })
            form("and", *constructorSpec(instance, index, value, fields, tag).toTypedArray())
        }
        axioms += form("assert", form("or", *disjunction.toTypedArray()))
        SmtDatatypeShape(SmtDatatypeCoordinate(value.name(), instance.sort), tag.name(), alternatives)
    }

    private fun constructorSpec(instance: SmtDatatypeInstance, index: Int, value: Term,
        fields: List<Term>, tag: Term): List<SExpr> = buildList {
        add(form("=", tag.expression, SExpr.Atom(index.toString())))
        val reconstructed = operation(instance, "constructor:$index", fields, instance.sort)
        add(form("=", value.expression, reconstructed.expression))
        for ((fieldIndex, field) in fields.withIndex()) {
            step()
            val selector = operation(instance, "selector:$index:$fieldIndex", listOf(value), field.sort)
            add(form("=", selector.expression, field.expression))
            val child = registry.instance(field.sort)
            if (child != null) {
                val parentDepth = operation(instance, "depth", listOf(value), SmtSort.Int)
                val childDepth = operation(child, "depth", listOf(field), SmtSort.Int)
                add(form(">", parentDepth.expression, childDepth.expression))
            }
        }
    }

    private fun operation(instance: SmtDatatypeInstance, operation: String, args: List<Term>, result: SmtSort): Term {
        step()
        val name = operationNames.getOrPut(instance.sort to operation) {
            freshName().also { name ->
                generated += form("declare-fun", SExpr.Atom(name), SExpr.SList(args.map { sortSyntax(it.sort) }),
                    sortSyntax(result))
            }
        }
        val expression = if (args.isEmpty()) SExpr.Atom(name)
            else form(name, *args.map { it.expression }.toTypedArray())
        val captured = capture(Term(result, expression))
        if (operation.startsWith("selector:")) {
            val parts = operation.split(':')
            selectors += SmtDatatypeSelector(SmtDatatypeCoordinate(args.single().name(), instance.sort),
                parts[1].toInt(), parts[2].toInt(), captured.name())
        }
        return captured
    }

    private fun capture(term: Term): Term {
        if (term.expression is SExpr.Atom && polynomialSymbol(term.expression.text) in symbols) return term
        return captures.getOrPut(term.expression to term.sort) {
            fresh(term.sort).also { axioms += form("assert", form("=", it.expression, term.expression)) }
        }
    }

    private fun fresh(sort: SmtSort): Term {
        step()
        val name = freshName()
        generated += form("declare-const", SExpr.Atom(name), sortSyntax(sort))
        privateBindings[name] = SmtReconstruction.Scalar(name, sort)
        val term = Term(sort, SExpr.Atom(name))
        symbols[name] = term
        return term
    }

    private fun freshName(): String {
        var name: String
        do { name = "@klause.adt.${nextName++}" } while (!usedNames.add(name))
        return name
    }

    private fun literal(expression: SExpr.Atom): Term {
        val text = expression.text
        val sort = when {
            text in setOf("true", "false") -> SmtSort.Bool
            text.startsWith("#b") -> SmtSort.BitVector(text.length - 2)
            text.startsWith("#x") -> SmtSort.BitVector((text.length - 2) * 4)
            text == "pi" || '.' in text -> SmtSort.Real
            text.isNotEmpty() && text.all { it in '0'..'9' } -> SmtSort.Int
            else -> smtUnsupported("unbound datatype-lowering symbol '$text'")
        }
        return Term(sort, expression)
    }

    private fun collectNames() {
        val work = ArrayDeque<SExpr>()
        input.commands.forEach(work::addLast)
        while (work.isNotEmpty()) {
            step()
            when (val expression = work.removeLast()) {
                is SExpr.Atom -> usedNames += polynomialSymbol(expression.text)
                is SExpr.SList -> expression.items.forEach(work::addLast)
            }
        }
    }

    private fun Term.name() = (expression as SExpr.Atom).text
    private fun step() = cancellation.polynomialSourceCheckpoint(1)
}

private fun resultSort(head: SExpr, args: List<SmtSort>): SmtSort {
    val name = (head as? SExpr.Atom)?.text
    if (head is SExpr.SList) {
        val operator = head.polynomialAtom(1)
        val base = args.firstOrNull() as? SmtSort.BitVector
        return when (operator) {
            "extract" -> SmtSort.BitVector(head.polynomialAtom(2).toInt() - head.polynomialAtom(3).toInt() + 1)
            "zero_extend", "sign_extend" -> SmtSort.BitVector(checkNotNull(base).width + head.polynomialAtom(2).toInt())
            "repeat" -> SmtSort.BitVector(checkNotNull(base).width * head.polynomialAtom(2).toInt())
            "int_to_bv", "int2bv" -> SmtSort.BitVector(head.polynomialAtom(2).toInt())
            else -> checkNotNull(base)
        }
    }
    return when (name) {
        "=", "distinct", "and", "or", "xor", "not", "=>", "<", "<=", ">", ">=", "is_int",
        "bvult", "bvule", "bvugt", "bvuge", "bvslt", "bvsle", "bvsgt", "bvsge",
        "bvuaddo", "bvsaddo", "bvumulo", "bvsmulo", "bvnego", "bvusubo", "bvssubo", "bvsdivo" -> SmtSort.Bool
        "ite" -> args[1]
        "select" -> (args[0] as SmtSort.Array).element
        "store" -> args[0]
        "to_int", "div", "mod", "ubv_to_int", "sbv_to_int", "bv2nat", "bv2int" -> SmtSort.Int
        "to_real", "/", "exp", "log", "sin", "cos", "pi" -> SmtSort.Real
        "concat" -> SmtSort.BitVector(args.sumOf { (it as SmtSort.BitVector).width })
        "bvcomp" -> SmtSort.BitVector(1)
        else -> if (SmtSort.Real in args) SmtSort.Real else args.firstOrNull() ?: SmtSort.Int
    }
}
private fun form(head: String, vararg args: SExpr): SExpr.SList = form(SExpr.Atom(head), *args)
private fun form(head: SExpr, vararg args: SExpr): SExpr.SList = SExpr.SList(listOf(head) + args)
private fun sortSyntax(sort: SmtSort): SExpr = SExprReader(sort.toSmtLib()).readAll().single()
