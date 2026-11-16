package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun reduceSmtPolymorphism(input: SmtReduction, cancellation: ExecutionControl): SmtReduction {
    val source = input.original ?: return input
    if (source.sortParameters.isEmpty()) return input
    return SmtPolymorphicReduction(input, source, cancellation).reduce()
}

private class SmtPolymorphicReduction(private val input: SmtReduction, private val source: SmtTypedSource,
    private val cancellation: ExecutionControl) {
    private data class Instance(val symbol: Int, val parameters: List<SmtSort>, val result: SmtSort)
    private data class Value(val expression: SExpr, val sort: SmtSort)
    private sealed interface Frame {
        data class Evaluate(val node: Int, val substitution: Map<String, SmtSort>,
            val locals: Map<Int, Value>) : Frame
        data class Combine(val node: Int, val substitution: Map<String, SmtSort>, val locals: Map<Int, Value>) : Frame
    }
    private val symbols = source.symbols.associateBy { it.identity }
    private val instances = LinkedHashMap<Instance, String>()
    private val declarations = ArrayList<SExpr>()
    private val bindings = ArrayList<SmtSourceBinding>()
    private val names = HashSet<String>()
    private var nextName = 0

    fun reduce(): SmtReduction {
        val output = ArrayList<SExpr>()
        for (command in source.commands) {
            step()
            val list = command as? SExpr.SList ?: smtUnsupported("expected SMT command")
            val head = list.polynomialHead()
            collectNames(command)
            if (head in setOf("declare-sort", "define-sort", "set-logic", "set-info", "set-option")) {
                output += command
            }
        }
        val basis = groundSorts()
        val substitutions = substitutions(basis)
        val obligations = ArrayList<SExpr>()
        var universallyCertified = source.terms.none { it.operator == "log" }
        for ((head, root) in source.obligations) {
            step()
            val parameters = termParameters(root)
            if (parameters.isNotEmpty() && (head != "assert" || !universalTautology(root))) {
                universallyCertified = false
            }
            val used = HashSet<SExpr>()
            for (substitution in if (parameters.isEmpty()) listOf(emptyMap()) else substitutions) {
                step()
                val value = expand(root, substitution)
                if (used.add(value.expression)) obligations += command(head, value.expression)
            }
        }
        for (symbol in source.symbols.filter { it.definition == null }) {
            if (symbol.declaration.parameters.any { smtSortParameters(it, source.sortParameters, cancellation)
                    .isNotEmpty() } || smtSortParameters(symbol.declaration.result, source.sortParameters,
                    cancellation).isNotEmpty()) continue
            instance(symbol, symbol.declaration.parameters, symbol.declaration.result)
        }
        val constructive = constructiveSchema()
        val model = model(constructive)
        output += declarations
        output += obligations
        for (raw in source.commands) {
            val list = raw as SExpr.SList
            when (list.polynomialHead()) {
                "check-sat", "check-sat-assuming" -> output += command("check-sat")
                "get-model", "get-objectives", "exit" -> output += raw
            }
        }
        val checks = if (constructive) input.checks + model
        else if (universallyCertified) input.checks else input.checks + SmtSourceCheck { values ->
            values.checkpoint()
            throw SmtSourceCheckingUnavailable()
        }
        return input.copy(commands = output, bindings = bindings, checks = checks, polymorphicModel = model)
    }

    private fun constructiveSchema(): Boolean {
        // A Boolean interpretation embeds in every available non-datatype sort. Ground terms stay in that image.
        if (source.terms.any { it.operator == "log" }) return false
        if (source.commands.any { (it as? SExpr.SList)?.polynomialHead() in
                setOf("declare-datatype", "declare-datatypes") }) return false
        val seen = HashSet<Int>()
        val work = ArrayDeque<Int>()
        for ((head, root) in source.obligations) {
            if (head != "assert") return false
            work.addLast(root)
        }
        fun supported(sort: SmtSort): Boolean = sort == SmtSort.Bool ||
            (sort is SmtSort.Uninterpreted && sort.arguments.isEmpty() &&
                polynomialSymbol(sort.name) in source.sortParameters)
        while (work.isNotEmpty()) {
            step()
            val index = work.removeLast()
            if (!seen.add(index)) continue
            val node = source.terms[index]
            if (!supported(node.sort)) return false
            val symbol = symbol(node)
            if (symbol != null) {
                if (!(symbol.declaration.parameters + symbol.declaration.result).all(::supported)) return false
                symbol.definition?.let { work.addLast(it.body) }
            } else if (!node.operator.startsWith("parameter:") && node.operator !in setOf(
                    "literal:true", "literal:false", "and", "or", "not", "xor", "=>", "=", "distinct", "ite")) {
                return false
            }
            for (argument in node.arguments) work.addLast(argument)
        }
        return true
    }

    private fun model(constructive: Boolean): SmtPolymorphicModel {
        val archetypes = HashMap<Int, String>()
        val transports = HashMap<String, String>()
        val substitution = source.sortParameters.associateWith { SmtSort.Bool }
        if (constructive) for (symbol in source.symbols.filter { it.definition == null }) {
            val parameters = symbol.declaration.parameters.map { substituteSmtSort(it, substitution, cancellation) }
            val result = substituteSmtSort(symbol.declaration.result, substitution, cancellation)
            if (parameters.all { it == SmtSort.Bool } && result == SmtSort.Bool) {
                instance(symbol, parameters, result)
            }
        }
        for ((key, name) in instances.toMap()) {
            step()
            val symbol = symbols.getValue(key.symbol)
            val parameters = symbol.declaration.parameters.map { substituteSmtSort(it, substitution, cancellation) }
            val result = substituteSmtSort(symbol.declaration.result, substitution, cancellation)
            if (parameters.any { it != SmtSort.Bool } || result != SmtSort.Bool) continue
            if (!constructive) continue
            val archetype = instance(symbol, parameters, result)
            archetypes[symbol.identity] = archetype
            transports[polynomialSymbol(name)] = archetype
        }
        val keys = instances.map { (key, value) ->
            symbols.getValue(key.symbol).declaration.copy(parameters = key.parameters, result = key.result) to value
        }.toMap()
        return SmtPolymorphicModel(source, archetypes, transports, keys, constructive)
    }

    private fun expand(root: Int, substitution: Map<String, SmtSort>): Value {
        val work = ArrayDeque<Frame>()
        val values = ArrayDeque<Value>()
        work.addLast(Frame.Evaluate(root, substitution, emptyMap()))
        while (work.isNotEmpty()) {
            step()
            when (val frame = work.removeLast()) {
                is Frame.Evaluate -> {
                    val local = frame.locals[frame.node]
                    if (local != null) { values.addLast(local); continue }
                    val node = source.terms[frame.node]
                    work.addLast(Frame.Combine(frame.node, frame.substitution, frame.locals))
                    for (argument in node.arguments.asReversed()) {
                        work.addLast(Frame.Evaluate(argument, frame.substitution, frame.locals))
                    }
                }
                is Frame.Combine -> {
                    val node = source.terms[frame.node]
                    val arguments = List(node.arguments.size) { values.removeLast() }.reversed()
                    val result = substituteSmtSort(node.sort, frame.substitution, cancellation)
                    val symbol = symbol(node)
                    if (symbol == null) {
                        val head = node.operator.removePrefix("literal:")
                        val expression = if (node.operator.startsWith("literal:")) SExpr.Atom(head)
                        else if (head.startsWith('(')) {
                            val identifier = SExprReader(head).readAll().single()
                            if (arguments.isEmpty()) identifier else SExpr.SList(listOf(identifier) +
                                arguments.map { it.expression })
                        } else if (arguments.isEmpty()) SExpr.Atom(head)
                        else command(head, *arguments.map { it.expression }.toTypedArray())
                        values.addLast(Value(expression, result))
                    } else if (symbol.definition != null) {
                        val rank = instantiateSmtRank(symbol.declaration.parameters, symbol.declaration.result,
                            source.sortParameters, arguments.map { it.sort }, result, cancellation)
                            ?: smtUnsupported("polymorphic helper rank cannot be instantiated")
                        val types = rankSubstitution(symbol.declaration, rank.first, rank.second)
                        val definition = symbol.definition
                        val locals = definition.parameters.zip(arguments).toMap()
                        work.addLast(Frame.Evaluate(definition.body, frame.substitution + types, locals))
                    } else {
                        val rank = instantiateSmtRank(symbol.declaration.parameters, symbol.declaration.result,
                            source.sortParameters, arguments.map { it.sort }, result, cancellation)
                            ?: smtUnsupported("polymorphic function rank cannot be instantiated")
                        val name = instance(symbol, rank.first, rank.second)
                        values.addLast(Value(if (arguments.isEmpty()) SExpr.Atom(name)
                            else command(name, *arguments.map { it.expression }.toTypedArray()), rank.second))
                    }
                }
            }
        }
        return values.single()
    }

    private fun instance(symbol: SmtTypedSymbol, parameters: List<SmtSort>, result: SmtSort): String {
        val key = Instance(symbol.identity, parameters, result)
        return instances.getOrPut(key) {
            step()
            val declaration = symbol.declaration.copy(parameters = parameters, result = result)
            val polymorphic = (symbol.declaration.parameters + symbol.declaration.result).any {
                smtSortParameters(it, source.sortParameters, cancellation).isNotEmpty()
            }
            val name = if (polymorphic) freshName() else declaration.name
            declarations += command("declare-fun", SExpr.Atom(name),
                SExpr.SList(parameters.map { sortExpression(it) }), sortExpression(result))
            bindings += SmtSourceBinding(declaration, SmtReconstruction.Scalar(name, result), key = name)
            name
        }
    }

    private fun rankSubstitution(declaration: SmtSourceDeclaration, parameters: List<SmtSort>,
        result: SmtSort): Map<String, SmtSort> {
        val found = HashMap<String, SmtSort>()
        val work = ArrayDeque<Pair<SmtSort, SmtSort>>()
        for (pair in (declaration.parameters + declaration.result).zip(parameters + result)) work.addLast(pair)
        while (work.isNotEmpty()) {
            step()
            val (template, actual) = work.removeLast()
            when (template) {
                is SmtSort.Uninterpreted -> if (template.arguments.isEmpty() &&
                    polynomialSymbol(template.name) in source.sortParameters) {
                    found[polynomialSymbol(template.name)] = actual
                } else if (actual is SmtSort.Uninterpreted) {
                    for (pair in template.arguments.zip(actual.arguments)) work.addLast(pair)
                }
                is SmtSort.Array -> if (actual is SmtSort.Array) {
                    work.addLast(template.index to actual.index)
                    work.addLast(template.element to actual.element)
                }
                else -> Unit
            }
        }
        return found
    }

    private fun groundSorts(): List<SmtSort> {
        val sorts = linkedSetOf<SmtSort>(SmtSort.Bool, SmtSort.Int, SmtSort.Real)
        val work = ArrayDeque<SmtSort>()
        for (term in source.terms) work.addLast(term.sort)
        for (symbol in source.symbols) {
            for (sort in symbol.declaration.parameters) work.addLast(sort)
            work.addLast(symbol.declaration.result)
        }
        while (work.isNotEmpty()) {
            step()
            val sort = work.removeLast()
            if (smtSortParameters(sort, source.sortParameters, cancellation).isEmpty()) sorts += sort
            when (sort) {
                is SmtSort.Array -> { work.addLast(sort.index); work.addLast(sort.element) }
                is SmtSort.Uninterpreted -> for (argument in sort.arguments) work.addLast(argument)
                else -> Unit
            }
        }
        return sorts.toList()
    }

    private fun substitutions(sorts: List<SmtSort>): List<Map<String, SmtSort>> {
        var result = listOf(emptyMap<String, SmtSort>())
        for (parameter in source.sortParameters) {
            val next = ArrayList<Map<String, SmtSort>>()
            for (previous in result) for (sort in sorts) {
                step()
                next += previous + (parameter to sort)
            }
            result = next
        }
        return result
    }

    private fun termParameters(root: Int): Set<String> {
        val found = HashSet<String>()
        val seen = HashSet<Int>()
        val work = ArrayDeque<Int>()
        work.addLast(root)
        while (work.isNotEmpty()) {
            step()
            val index = work.removeLast()
            if (!seen.add(index)) continue
            val node = source.terms[index]
            found += smtSortParameters(node.sort, source.sortParameters, cancellation)
            for (argument in node.arguments) work.addLast(argument)
            symbol(node)?.definition?.let { work.addLast(it.body) }
        }
        return found
    }

    private fun universalTautology(root: Int): Boolean = smtUniversalTautology(source, root, cancellation)

    private fun symbol(term: SmtTypedTerm): SmtTypedSymbol? = if (term.operator.startsWith("symbol:")) {
        term.operator.substringAfter(':').substringBefore(':').toIntOrNull()?.let { symbols[it] }
    } else null

    private fun collectNames(expression: SExpr) {
        val work = ArrayDeque<SExpr>()
        work.addLast(expression)
        while (work.isNotEmpty()) {
            step()
            when (val node = work.removeLast()) {
                is SExpr.Atom -> names += polynomialSymbol(node.text)
                is SExpr.SList -> for (item in node.items) work.addLast(item)
            }
        }
    }

    private fun freshName(): String {
        while (true) {
            step()
            val name = "@klause.poly.${nextName++}"
            if (names.add(name)) return name
        }
    }

    private fun sortExpression(sort: SmtSort): SExpr = SExprReader(sort.toSmtLib()).readAll().single()
    private fun command(head: String, vararg arguments: SExpr): SExpr =
        SExpr.SList(listOf(SExpr.Atom(head)) + arguments)
    private fun step() = cancellation.polynomialSourceCheckpoint(1)
}
