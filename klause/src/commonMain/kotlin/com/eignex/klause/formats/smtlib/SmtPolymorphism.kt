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
    private val hasPartialLog = source.terms.any { it.datatype == null && it.operator == "log" }
    private var nextName = 0

    fun reduce(): SmtReduction {
        val output = ArrayList<SExpr>()
        for (command in source.commands) {
            step()
            val list = command as? SExpr.SList ?: smtUnsupported("expected SMT command")
            val head = list.polynomialHead()
            collectNames(command)
            if (head in setOf("declare-sort", "define-sort", "declare-datatype", "declare-datatypes",
                    "set-logic", "set-info", "set-option")) {
                output += command
            }
        }
        val basis = groundSorts()
        val obligations = ArrayList<SExpr>()
        var universallyCertified = !hasPartialLog
        var hasSchemas = false
        for ((head, root) in source.obligations) {
            step()
            val parameters = termParameters(root)
            if (parameters.isNotEmpty()) hasSchemas = true
            if (parameters.isNotEmpty() && (head != "assert" || !universalTautology(root))) {
                universallyCertified = false
            }
            val used = HashSet<SExpr>()
            for (substitution in substitutions(basis, parameters)) {
                step()
                val value = expand(root, substitution)
                if (used.add(value.expression)) obligations += command(head, value.expression)
            }
        }
        for (symbol in source.symbols.filter { it.definition == null }) {
            if (symbol.declaration.parameters.any {
                    smtSortParameters(it, source.sortParameters, cancellation)
                        .isNotEmpty()
                } || smtSortParameters(symbol.declaration.result, source.sortParameters,
                    cancellation).isNotEmpty()) continue
            instance(symbol, symbol.declaration.parameters, symbol.declaration.result)
        }
        val definitionSchemas = definitionObligations(basis, obligations)
        if (definitionSchemas) universallyCertified = false
        val constructive = (hasSchemas || definitionSchemas || source.obligations.isEmpty()) && constructiveSchema()
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

    private fun definitionParameters(symbol: SmtTypedSymbol): Set<String> {
        val definition = symbol.definition ?: return emptySet()
        val rankParameters = (symbol.declaration.parameters + symbol.declaration.result).flatMapTo(HashSet()) {
            smtSortParameters(it, source.sortParameters, cancellation)
        }
        return termParameters(definition.body) - rankParameters
    }

    private fun definitionObligations(basis: List<SmtSort>,
        obligations: MutableList<SExpr>): Boolean {
        if (hasPartialLog) {
            return source.symbols.any { definitionParameters(it).isNotEmpty() }
        }
        var universal = false
        for (symbol in source.symbols) {
            val definition = symbol.definition ?: continue
            if (definitionParameters(symbol).isEmpty()) continue
            universal = true
            val witnesses = HashMap<Instance, List<List<Value>>>()
            val bodies = HashMap<Pair<Instance, Int>, SExpr>()
            val typeParameters = termParameters(definition.body) +
                (symbol.declaration.parameters + symbol.declaration.result).flatMap {
                    smtSortParameters(it, source.sortParameters, cancellation)
                }
            for (substitution in substitutions(basis, typeParameters)) {
                step()
                val parameters = symbol.declaration.parameters.map {
                    substituteSmtSort(it, substitution, cancellation)
                }
                val result = substituteSmtSort(symbol.declaration.result, substitution, cancellation)
                val rank = Instance(symbol.identity, parameters, result)
                val tuples = witnesses.getOrPut(rank) {
                    var tuples = listOf(emptyList<Value>())
                    for (sort in parameters) {
                        val values = if (sort == SmtSort.Bool) listOf(
                            Value(SExpr.Atom("false"), sort), Value(SExpr.Atom("true"), sort))
                        else {
                            val name = freshName()
                            declarations += command("declare-const", SExpr.Atom(name), sortExpression(sort))
                            listOf(Value(SExpr.Atom(name), sort))
                        }
                        val next = ArrayList<List<Value>>()
                        for (tuple in tuples) {
                            for (value in values) {
                                step()
                                next += tuple + value
                            }
                        }
                        tuples = next
                    }
                    tuples
                }
                for ((index, tuple) in tuples.withIndex()) {
                    step()
                    val locals = definition.parameters.zip(tuple).toMap()
                    val body = expand(definition.body, substitution, locals).expression
                    val key = rank to index
                    val previous = bodies[key]
                    if (previous == null) bodies[key] = body
                    if (previous != null && previous != body) {
                        obligations += command("assert", command("=", previous, body))
                    }
                }
            }
        }
        return universal
    }

    private fun constructiveSchema(): Boolean {
        // A Boolean interpretation embeds in every available non-datatype sort. Ground terms stay in that image.
        if (hasPartialLog) return false
        if (source.commands.any {
                (it as? SExpr.SList)?.polynomialHead() in
                    setOf("declare-datatype", "declare-datatypes")
            }) return false
        val seen = HashSet<Int>()
        val work = ArrayDeque<Int>()
        for ((head, root) in source.obligations) {
            if (head != "assert") return false
            work.addLast(root)
        }
        for (symbol in source.symbols) {
            symbol.definition?.let { definition ->
                if (definitionParameters(symbol).isNotEmpty()) work.addLast(definition.body)
            }
        }
        fun supported(sort: SmtSort): Boolean = sort == SmtSort.Bool ||
            (sort is SmtSort.Uninterpreted && sort.arguments.isEmpty() &&
                polynomialSymbol(sort.name) in source.sortParameters)
        val operators = setOf("literal:true", "literal:false", "and", "or", "not", "xor", "=>", "=", "distinct", "ite")
        while (work.isNotEmpty()) {
            step()
            val index = work.removeLast()
            if (!seen.add(index)) continue
            val node = source.terms[index]
            val symbol = symbol(node)
            if (symbol != null) {
                if (!(symbol.declaration.parameters + symbol.declaration.result).all(::supported)) return false
                symbol.definition?.let { work.addLast(it.body) }
            } else if (!node.operator.startsWith("parameter:") && node.operator !in operators) {
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

    private fun expand(root: Int, substitution: Map<String, SmtSort>, locals: Map<Int, Value> = emptyMap()): Value {
        val work = ArrayDeque<Frame>()
        val values = ArrayDeque<Value>()
        work.addLast(Frame.Evaluate(root, substitution, locals))
        while (work.isNotEmpty()) {
            step()
            when (val frame = work.removeLast()) {
                is Frame.Evaluate -> {
                    val local = frame.locals[frame.node]
                    if (local != null) {
                        values.addLast(local)
                        continue
                    }
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
                        val expression = if (node.datatype != null) {
                            val datatype = node.datatype
                            val identifier = if (datatype.kind == SmtTypedDatatypeKind.TESTER)
                                command("_", SExpr.Atom("is"), SExpr.Atom(datatype.name))
                            else SExpr.Atom(datatype.name)
                            val qualified = command("as", identifier, sortExpression(result))
                            if (arguments.isEmpty()) qualified
                            else SExpr.SList(listOf(qualified) + arguments.map { it.expression })
                        } else if (node.operator.startsWith("literal:")) SExpr.Atom(head)
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
                        val types = smtRankSubstitution(symbol.declaration, rank.first, rank.second,
                            source.sortParameters, cancellation)
                        val definition = symbol.definition
                        val locals = definition.parameters.indices.associate { index ->
                            definition.parameters[index] to arguments[index].copy(sort = rank.first[index])
                        }
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

    private fun groundSorts(): List<SmtSort> {
        val sorts = linkedSetOf<SmtSort>(SmtSort.Bool, SmtSort.Int, SmtSort.Real)
        val work = ArrayDeque<SmtSort>()
        for (raw in source.commands) {
            step()
            val command = raw as? SExpr.SList ?: continue
            when (command.polynomialHead()) {
                "declare-sort" -> if (command.polynomialAtom(2) == "0") {
                    work.addLast(SmtSort.Uninterpreted((command.items[1] as SExpr.Atom).text))
                }
                "declare-datatype" -> {
                    val body = command.items[2] as SExpr.SList
                    if ((body.items.firstOrNull() as? SExpr.Atom)?.text != "par") {
                        work.addLast(SmtSort.Uninterpreted((command.items[1] as SExpr.Atom).text))
                    }
                }
                "declare-datatypes" -> for (declaration in (command.items[1] as SExpr.SList).items) {
                    val pair = declaration as SExpr.SList
                    if ((pair.items[1] as SExpr.Atom).text == "0") {
                        work.addLast(SmtSort.Uninterpreted((pair.items[0] as SExpr.Atom).text))
                    }
                }
            }
        }
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
                is SmtSort.Array -> {
                    work.addLast(sort.index)
                    work.addLast(sort.element)
                }
                is SmtSort.Uninterpreted -> for (argument in sort.arguments) work.addLast(argument)
                else -> Unit
            }
        }
        return sorts.toList()
    }

    private fun substitutions(sorts: List<SmtSort>, parameters: Set<String>): List<Map<String, SmtSort>> {
        var result = listOf(emptyMap<String, SmtSort>())
        for (parameter in parameters) {
            val next = ArrayList<Map<String, SmtSort>>()
            for (previous in result) {
                for (sort in sorts) {
                    step()
                    next += previous + (parameter to sort)
                }
            }
            result = next
        }
        return result
    }

    private fun termParameters(root: Int): Set<String> {
        val found = HashSet<String>()
        val seen = HashSet<Pair<Int, Map<String, SmtSort>>>()
        val work = ArrayDeque<Pair<Int, Map<String, SmtSort>>>()
        work.addLast(root to emptyMap())
        while (work.isNotEmpty()) {
            step()
            val frame = work.removeLast()
            if (!seen.add(frame)) continue
            val (index, substitution) = frame
            val node = source.terms[index]
            val result = substituteSmtSort(node.sort, substitution, cancellation)
            found += smtSortParameters(result, source.sortParameters, cancellation)
            for (argument in node.arguments) work.addLast(argument to substitution)
            val symbol = symbol(node)
            val definition = symbol?.definition
            if (definition != null) {
                val arguments = node.arguments.map {
                    substituteSmtSort(source.terms[it].sort, substitution, cancellation)
                }
                val rank = instantiateSmtRank(symbol.declaration.parameters, symbol.declaration.result,
                    source.sortParameters, arguments, result, cancellation)
                    ?: smtUnsupported("polymorphic helper rank cannot be instantiated")
                val types = smtRankSubstitution(symbol.declaration, rank.first, rank.second,
                    source.sortParameters, cancellation)
                work.addLast(definition.body to (substitution + types))
            }
        }
        return found
    }

    private fun universalTautology(root: Int): Boolean = smtUniversalTautology(source, root, cancellation)

    private fun symbol(term: SmtTypedTerm): SmtTypedSymbol? = if (term.datatype == null &&
        term.operator.startsWith("symbol:")) {
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
