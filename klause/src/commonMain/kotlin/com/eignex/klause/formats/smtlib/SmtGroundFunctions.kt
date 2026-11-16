package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun reduceSmtGroundFunctions(input: SmtReduction, cancellation: ExecutionControl): SmtReduction {
    val pending = ArrayDeque<SExpr>()
    for (command in input.commands) pending.addLast(command)
    var needed = false
    while (pending.isNotEmpty() && !needed) {
        cancellation.polynomialSourceCheckpoint(1)
        val expression = pending.removeLast()
        if (expression is SExpr.SList) {
            val head = (expression.items.firstOrNull() as? SExpr.Atom)?.text
            needed = head in setOf("declare-sort", "define-sort", "as") ||
                (head == "define-fun" && expression.polynomialName(1) in setOf("let", "as", "_", "!")) ||
                (head == "declare-fun" &&
                (expression.items.getOrNull(2) as? SExpr.SList)?.items?.isNotEmpty() == true)
            for (item in expression.items) pending.addLast(item)
        }
    }
    return if (needed) SmtGroundFunctionReducer(input, cancellation, encodeUninterpreted = true).reduce() else input
}

internal fun exposeSmtGroundFunctions(input: SmtReduction, cancellation: ExecutionControl): SmtReduction =
    SmtGroundFunctionReducer(input, cancellation, encodeUninterpreted = false).reduce()

private class SmtGroundFunctionReducer(private val input: SmtReduction, private val cancellation: ExecutionControl,
    private val encodeUninterpreted: Boolean) {
    private data class Term(val id: Int, val expression: SExpr, val partial: Boolean = false)
    private data class NodeKey(val head: SExpr, val arguments: List<Int>)
    private data class ApplicationKey(val function: Int, val arguments: List<Int>)
    private data class Application(val arguments: List<String>, val result: String)
    private data class Binding(val version: Int, val term: Term? = null, val function: Function? = null,
        val macro: Macro? = null)
    private class Function(val id: Int, val declaration: SmtSourceDeclaration, val default: String) {
        val applications = ArrayList<Application>()
    }
    private data class Macro(val parameters: List<String>, val body: SExpr, val environment: Environment)
    private class Environment(val version: Int, val locals: Map<String, Term> = emptyMap(),
        val parent: Environment? = null) {
        fun local(name: String): Term? {
            var scope: Environment? = this
            while (scope != null) {
                scope.locals[name]?.let { return it }
                scope = scope.parent
            }
            return null
        }
    }
    private sealed interface Frame {
        data class Evaluate(val expression: SExpr, val environment: Environment) : Frame
        data class Combine(val head: SExpr, val count: Int, val function: Function?) : Frame
        data class Let(val names: List<String>, val body: SExpr, val environment: Environment) : Frame
        data class Call(val macro: Macro) : Frame
        data class DefinedArguments(val arguments: List<Term>) : Frame
    }

    private val sortScope = SmtLibScope(cancellation, allowSolverSymbols = true)
    private val bindings = HashMap<String, Binding>()
    private val functions = LinkedHashMap<String, Function>()
    private val sourceBindings = input.bindings.associateByTo(LinkedHashMap()) { polynomialSymbol(it.key) }
    private val privateBindings = input.privateBindings.toMutableMap()
    private val output = ArrayList<SExpr>()
    private val atoms = HashMap<String, Term>()
    private val nodes = HashMap<NodeKey, Term>()
    private val applications = HashMap<ApplicationKey, Term>()
    private val identities = LinkedHashMap<SmtSort.Uninterpreted, MutableList<String>>()
    private val usedNames = HashSet<String>()
    private var nextTerm = 0
    private var nextName = 0

    fun reduce(): SmtReduction {
        collectNames()
        for (expression in input.commands) {
            step()
            val command = expression as? SExpr.SList ?: smtUnsupported("expected SMT command")
            when (command.polynomialHead()) {
                "declare-sort", "define-sort" -> {
                    sortScope.command(command)
                    if (!encodeUninterpreted) output += expression
                }
                "declare-const", "declare-fun" -> declare(command)
                "define-fun" -> define(command)
                "assert", "minimize", "maximize" -> {
                    val value = evaluate(command.items[1], Environment(bindings.size))
                    output += SExpr.SList(listOf(command.items[0], value.expression))
                }
                "check-sat-assuming" -> {
                    val assumptions = command.items[1] as SExpr.SList
                    val environment = Environment(bindings.size)
                    val values = assumptions.items.map { evaluate(it, environment).expression }
                    output += SExpr.SList(listOf(command.items[0], SExpr.SList(values)))
                }
                "get-value" -> {
                    val terms = command.items[1] as SExpr.SList
                    val environment = Environment(bindings.size)
                    output += SExpr.SList(listOf(command.items[0],
                        SExpr.SList(terms.items.map { evaluate(it, environment).expression })))
                }
                else -> output += expression
            }
        }
        val bounds = ArrayList<SExpr>()
        for ((_, names) in identities) {
            val upper = atom((names.size - 1).toString()).expression
            for (name in names) {
                step()
                bounds += groundCommand("assert", expression("and", expression("<=", SExpr.Atom("0"), SExpr.Atom(name)),
                    expression("<=", SExpr.Atom(name), upper)))
            }
        }
        val query = output.indexOfFirst { it is SExpr.SList &&
            it.polynomialHead() in setOf("check-sat", "check-sat-assuming", "get-model", "exit") }
        output.addAll(if (query < 0) output.size else query, bounds)
        for ((name, function) in functions) {
            step()
            val declaration = function.declaration
            val entries = function.applications.map { it.arguments.toList() to it.result }
            val original = sourceBindings[name]
            val recipe = GroundFunctionReconstruction((original?.declaration ?: declaration).parameters.toList(),
                entries, function.default)
            if (original == null) privateBindings[declaration.name] = recipe
            else sourceBindings[name] = original.copy(reconstruction = recipe)
        }
        return input.copy(commands = output.toList(), bindings = sourceBindings.values.toList(),
            privateBindings = privateBindings.toMap(), checks = input.checks + functions.values.map { function ->
                GroundFunctionCheck(function.applications.map { it.arguments.toList() to it.result })
            })
    }

    private fun collectNames() {
        val work = ArrayDeque<SExpr>()
        for (command in input.commands) work.addLast(command)
        while (work.isNotEmpty()) {
            step()
            when (val expression = work.removeLast()) {
                is SExpr.Atom -> usedNames += polynomialSymbol(expression.text)
                is SExpr.SList -> for (item in expression.items) work.addLast(item)
            }
        }
    }

    private fun declare(command: SExpr.SList) {
        val name = command.polynomialName(1)
        val parameters = if (command.polynomialHead() == "declare-fun") {
            (command.items[2] as SExpr.SList).items.map(sortScope::sourceSort)
        } else emptyList()
        val declaration = SmtSourceDeclaration((command.items[1] as SExpr.Atom).text, parameters,
            sortScope.sourceSort(command.items.last()))
        if (declaration.parameters.isNotEmpty()) {
            val default = (freshValue(declaration.result).expression as SExpr.Atom).text
            val function = Function(functions.size, declaration, default)
            functions[name] = function
            bindings[name] = Binding(bindings.size, function = function)
            return
        }
        val term = if (encodeUninterpreted && declaration.result is SmtSort.Uninterpreted) {
            val sort = declaration.result
            output += groundCommand("declare-const", SExpr.Atom(declaration.name), SExpr.Atom("Int"))
            identities.getOrPut(sort) { ArrayList() } += declaration.name
            val recipe = FreeSortReconstruction(sort, declaration.name)
            privateBindings[declaration.name] = recipe
            sourceBindings[name]?.let { sourceBindings[name] = it.copy(reconstruction = recipe) }
            atom(declaration.name)
        } else {
            output += groundCommand("declare-const", SExpr.Atom(declaration.name), sortExpression(declaration.result))
            atom(declaration.name)
        }
        bindings[name] = Binding(bindings.size, term = term)
    }

    private fun define(command: SExpr.SList) {
        val parameters = command.items[2] as SExpr.SList
        val names = parameters.items.map { (it as SExpr.SList).polynomialName(0) }
        val macro = Macro(names, command.items[4], Environment(bindings.size))
        bindings[command.polynomialName(1)] = Binding(bindings.size, macro = macro)
    }

    private fun evaluate(root: SExpr, environment: Environment): Term {
        val work = ArrayDeque<Frame>()
        val values = ArrayDeque<Term>()
        work.addLast(Frame.Evaluate(root, environment))
        fun pop(count: Int): List<Term> = List(count) { values.removeLast() }.reversed()
        while (work.isNotEmpty()) {
            step()
            when (val frame = work.removeLast()) {
                is Frame.Evaluate -> when (val node = frame.expression) {
                    is SExpr.Atom -> {
                        val name = polynomialSymbol(node.text)
                        val local = frame.environment.local(name)
                        val binding = binding(name, frame.environment)
                        when {
                            lexicalLiteral(node.text) -> values.addLast(atom(node.text))
                            local != null -> values.addLast(local)
                            binding?.term != null -> values.addLast(binding.term)
                            binding?.macro != null -> {
                                work.addLast(Frame.Evaluate(binding.macro.body, binding.macro.environment))
                            }
                            else -> values.addLast(atom(node.text))
                        }
                    }
                    is SExpr.SList -> {
                        val head = node.items.first()
                        val name = identifierName(head)
                        val syntax = (head as? SExpr.Atom)?.text
                        if (syntax == "let") {
                            val pairs = (node.items[1] as SExpr.SList).items.map { it as SExpr.SList }
                            work.addLast(Frame.Let(pairs.map { it.polynomialName(0) }, node.items[2],
                                frame.environment))
                            for (pair in pairs.asReversed()) {
                                work.addLast(Frame.Evaluate(pair.items[1], frame.environment))
                            }
                        } else if (syntax == "!") {
                            work.addLast(Frame.Evaluate(node.items[1], frame.environment))
                        } else if (syntax == "as") {
                            val selected = node.items[1]
                            val binding = identifierName(selected)?.let { binding(it, frame.environment) }
                            when {
                                binding?.term != null -> values.addLast(binding.term)
                                binding?.macro != null -> work.addLast(Frame.Evaluate(binding.macro.body,
                                    binding.macro.environment))
                                selected is SExpr.Atom -> values.addLast(atom(polynomialSymbol(selected.text)))
                                else -> values.addLast(literalTerm(selected))
                            }
                        } else if (syntax == "_") {
                            values.addLast(intern(head, node.items.drop(1).map { literalTerm(it) }))
                        } else {
                            val binding = name?.let { binding(it, frame.environment) }
                            if (binding?.macro != null) work.addLast(Frame.Call(binding.macro))
                            else work.addLast(Frame.Combine(unqualifiedIdentifier(head), node.items.size - 1,
                                binding?.function))
                            for (argument in node.items.drop(1).asReversed()) {
                                work.addLast(Frame.Evaluate(argument, frame.environment))
                            }
                        }
                    }
                }
                is Frame.Let -> {
                    val args = pop(frame.names.size)
                    val locals = frame.names.zip(args).toMap()
                    work.addLast(Frame.DefinedArguments(args.filter { it.partial }))
                    work.addLast(Frame.Evaluate(frame.body, Environment(frame.environment.version, locals,
                        frame.environment)))
                }
                is Frame.Call -> {
                    val args = pop(frame.macro.parameters.size)
                    val locals = frame.macro.parameters.zip(args).toMap()
                    work.addLast(Frame.DefinedArguments(args.filter { it.partial }))
                    work.addLast(Frame.Evaluate(frame.macro.body, Environment(frame.macro.environment.version,
                        locals, frame.macro.environment)))
                }
                is Frame.Combine -> {
                    val args = pop(frame.count)
                    values.addLast(if (frame.function == null) intern(frame.head, args)
                        else apply(frame.function, args))
                }
                is Frame.DefinedArguments -> if (frame.arguments.isNotEmpty()) {
                    val value = values.removeLast()
                    val domains = frame.arguments.map { intern(SExpr.Atom("="), listOf(it, it)) }
                    val condition = intern(SExpr.Atom("and"), domains)
                    values.addLast(intern(SExpr.Atom("ite"), listOf(condition, value, value)))
                }
            }
        }
        return values.single()
    }

    private fun literalTerm(root: SExpr): Term {
        val work = ArrayDeque<Pair<SExpr, Boolean>>()
        val values = ArrayDeque<Term>()
        work.addLast(root to false)
        while (work.isNotEmpty()) {
            step()
            val (node, combine) = work.removeLast()
            when (node) {
                is SExpr.Atom -> values.addLast(atom(node.text))
                is SExpr.SList -> if (combine) {
                    val args = List(node.items.size - 1) { values.removeLast() }.reversed()
                    values.addLast(intern(node.items[0], args))
                } else {
                    work.addLast(node to true)
                    for (item in node.items.drop(1).asReversed()) work.addLast(item to false)
                }
            }
        }
        return values.single()
    }

    private fun binding(name: String, environment: Environment): Binding? =
        bindings[name]?.takeIf { it.version < environment.version }

    private fun apply(function: Function, args: List<Term>): Term {
        if (args.any { it.partial }) {
            smtUnsupported("uninterpreted applications require total arguments outside the partial real extension")
        }
        val key = ApplicationKey(function.id, args.map { it.id })
        applications[key]?.let { return it }
        val argumentNames = args.mapIndexed { index, argument ->
            step()
            val captured = freshValue(function.declaration.parameters[index])
            output += groundCommand("assert", expression("=", captured.expression, argument.expression))
            (captured.expression as SExpr.Atom).text
        }
        val result = freshValue(function.declaration.result)
        val resultName = (result.expression as SExpr.Atom).text
        for (previous in function.applications) {
            step()
            val equalArguments = argumentNames.indices.map { index ->
                step()
                expression("=", SExpr.Atom(previous.arguments[index]), SExpr.Atom(argumentNames[index]))
            }
            output += groundCommand("assert", expression("=>", expression("and", *equalArguments.toTypedArray()),
                expression("=", SExpr.Atom(previous.result), result.expression)))
        }
        function.applications += Application(argumentNames, resultName)
        applications[key] = result
        return result
    }

    private fun freshValue(sort: SmtSort): Term {
        step()
        var name: String
        do {
            name = "@klause.uf.${nextName++}"
        } while (!usedNames.add(name))
        val encodedSort = if (encodeUninterpreted && sort is SmtSort.Uninterpreted) SmtSort.Int else sort
        output += groundCommand("declare-const", SExpr.Atom(name), sortExpression(encodedSort))
        privateBindings[name] = if (encodeUninterpreted && sort is SmtSort.Uninterpreted) {
            val names = identities.getOrPut(sort) { ArrayList() }
            names += name
            FreeSortReconstruction(sort, name)
        } else SmtReconstruction.Scalar(name, sort)
        return atom(name)
    }

    private fun atom(text: String): Term {
        val literal = lexicalLiteral(text)
        val key = if (literal) "literal:$text" else "symbol:${polynomialSymbol(text)}"
        return atoms.getOrPut(key) { Term(nextTerm++, SExpr.Atom(text)) }
    }

    private fun intern(head: SExpr, args: List<Term>): Term = nodes.getOrPut(NodeKey(head, args.map { it.id })) {
        Term(nextTerm++, SExpr.SList(listOf(head) + args.map { it.expression }),
            identifierName(head) == "log" || args.any { it.partial })
    }

    private fun step() = cancellation.polynomialSourceCheckpoint(1)
}

private fun lexicalLiteral(text: String): Boolean = text in setOf("true", "false") ||
    (text.isNotEmpty() && text[0] in '0'..'9') || text.startsWith('#')

private data class FreeSortReconstruction(val sort: SmtSort.Uninterpreted, val name: String) : SmtReconstruction {
    override fun reconstruct(values: SmtModelValues): SmtValue =
        SmtValue.Uninterpreted(sort, freeIdentity(sort, values.integer(name)))
}

private fun freeIdentity(sort: SmtSort.Uninterpreted, identity: String): String =
    "@klause.u." + sort.toSmtLib().encodeToByteArray().joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    } + "!$identity"

private data class GroundFunctionReconstruction(val parameters: List<SmtSort>,
    val entries: List<Pair<List<String>, String>>, val default: String) : SmtReconstruction {
    override fun reconstruct(values: SmtModelValues): SmtValue = SmtValue.Function(parameters,
        entries.map { (args, result) -> args.map { values.value(it) } to values.value(result) }, values.value(default))
}

private class GroundFunctionCheck(private val applications: List<Pair<List<String>, String>>) : SmtSourceCheck {
    override fun check(values: SmtModelValues): Boolean {
        for (first in applications.indices) for (second in first + 1 until applications.size) {
            values.checkpoint()
            val a = applications[first]
            val b = applications[second]
            var different = false
            var unavailable = false
            for (index in a.first.indices) {
                values.checkpoint()
                when (values.equal(a.first[index], b.first[index])) {
                    true -> Unit
                    false -> {
                        different = true
                        break
                    }
                    null -> unavailable = true
                }
            }
            if (different) continue
            when (values.equal(a.second, b.second)) {
                true -> Unit
                false -> if (!unavailable) return false else throw SmtSourceCheckingUnavailable()
                null -> throw SmtSourceCheckingUnavailable()
            }
        }
        return true
    }
}

private fun expression(head: String, vararg args: SExpr): SExpr.SList =
    SExpr.SList(listOf(SExpr.Atom(head)) + args)

private fun groundCommand(head: String, vararg args: SExpr): SExpr.SList = expression(head, *args)

private fun sortExpression(sort: SmtSort): SExpr = SExprReader(sort.toSmtLib()).readAll().single()

private fun identifierName(identifier: SExpr): String? = when (identifier) {
    is SExpr.Atom -> polynomialSymbol(identifier.text)
    is SExpr.SList -> if ((identifier.items.firstOrNull() as? SExpr.Atom)?.text == "as") {
        (identifier.items.getOrNull(1) as? SExpr.Atom)?.text?.let(::polynomialSymbol)
    } else null
}

private fun unqualifiedIdentifier(identifier: SExpr): SExpr = when (identifier) {
    is SExpr.Atom -> SExpr.Atom(polynomialSymbol(identifier.text))
    is SExpr.SList -> if ((identifier.items.firstOrNull() as? SExpr.Atom)?.text == "as") {
        unqualifiedIdentifier(identifier.items[1])
    } else identifier
}
