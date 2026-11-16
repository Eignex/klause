package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun smtSelectorInterpretationKey(name: String, argumentSort: SmtSort, resultSort: SmtSort): String =
    "@selector:" + listOf(polynomialSymbol(name), argumentSort.toSmtLib(), resultSort.toSmtLib())
        .joinToString("") { "${it.length}:$it" }

internal data class SmtDatatypeField(val name: String, val sort: SmtSort)
internal data class SmtDatatypeConstructor(val name: String, val fields: List<SmtDatatypeField>)
internal data class SmtDatatypeDefinition(val name: String, val parameters: List<String>,
    val constructors: List<SmtDatatypeConstructor>)
internal data class SmtDatatypeInstance(val sort: SmtSort.Uninterpreted,
    val constructors: List<SmtDatatypeConstructor>)
internal data class SmtDatatypeCase(val constructor: Int?, val variables: List<String>, val body: SExpr)
internal data class SmtDatatypeOperator(val instance: SmtDatatypeInstance,
    val constructor: Int, val selector: Int? = null, val tester: Boolean = false) {
    val result: SmtSort get() = when {
        tester -> SmtSort.Bool
        selector != null -> instance.constructors[constructor].fields[selector].sort
        else -> instance.sort
    }
    val arguments: List<SmtSort> get() = if (tester || selector != null) listOf(instance.sort)
    else instance.constructors[constructor].fields.map { it.sort }
}

internal class SmtDatatypes(private val cancellation: ExecutionControl,
    private val checkpoint: ((Long) -> Unit)? = null) {
    private val definitions = LinkedHashMap<String, SmtDatatypeDefinition>()
    private val groups = HashMap<String, Set<String>>()
    val names: Set<String> get() = definitions.keys
    val operatorNames: Set<String> get() = definitions.values.flatMap { definition ->
        definition.constructors.flatMap { constructor ->
            listOf(polynomialSymbol(constructor.name)) + constructor.fields.map { polynomialSymbol(it.name) }
        }
    }.toSet()

    fun snapshot(cancellation: ExecutionControl, checkpoint: ((Long) -> Unit)? = null): SmtDatatypes =
        SmtDatatypes(cancellation, checkpoint).also {
            it.definitions.putAll(definitions)
            it.groups.putAll(groups)
        }

    fun headers(command: SExpr.SList): List<Pair<String, Int>> = when (command.polynomialHead()) {
        "declare-datatype" -> {
            command.polynomialSize(3)
            val declaration = command.items[2] as? SExpr.SList ?: smtUnsupported("invalid datatype declaration")
            val parameters = if ((declaration.items.firstOrNull() as? SExpr.Atom)?.text
                    ?.let(::polynomialSymbol) == "par") {
                (declaration.items.getOrNull(1) as? SExpr.SList)?.items?.size
                    ?: smtUnsupported("invalid datatype parameters")
            } else 0
            listOf((command.items[1] as SExpr.Atom).text to parameters)
        }
        "declare-datatypes" -> {
            command.polynomialSize(3)
            val headers = command.items[1] as? SExpr.SList ?: smtUnsupported("invalid datatype headers")
            if (headers.items.isEmpty()) smtUnsupported("empty datatype group")
            headers.items.map {
                step()
                val header = it as? SExpr.SList ?: smtUnsupported("invalid datatype header")
                header.polynomialSize(2)
                val arity = header.polynomialAtom(1).toIntOrNull()
                    ?: smtUnsupported("invalid datatype arity")
                if (arity < 0) smtUnsupported("invalid datatype arity")
                (header.items[0] as SExpr.Atom).text to arity
            }
        }
        else -> emptyList()
    }

    fun declare(command: SExpr.SList, parseSort: (SExpr, Map<String, SmtSort>) -> SmtSort) {
        val headers = headers(command)
        val bodies = if (command.polynomialHead() == "declare-datatype") listOf(command.items[2])
        else (command.items[2] as? SExpr.SList)?.items ?: smtUnsupported("invalid datatype bodies")
        if (headers.size != bodies.size) smtUnsupported("datatype header count differs from body count")
        val group = headers.map { polynomialSymbol(it.first) }.toSet()
        if (group.size != headers.size || group.any { it in definitions }) smtUnsupported("duplicate datatype sort")
        val declared = ArrayList<SmtDatatypeDefinition>()
        val operators = operatorNames.toMutableSet()
        for ((index, header) in headers.withIndex()) {
            step()
            val body = bodies[index] as? SExpr.SList ?: smtUnsupported("invalid datatype body")
            val polymorphic = (body.items.firstOrNull() as? SExpr.Atom)?.text?.let(::polynomialSymbol) == "par"
            val parameters = if (polymorphic) {
                body.polynomialSize(3)
                val params = body.items[1] as? SExpr.SList ?: smtUnsupported("invalid datatype parameters")
                params.items.map {
                    (it as? SExpr.Atom)?.text?.let(::polynomialSymbol)
                        ?: smtUnsupported("invalid datatype parameter")
                }
            } else emptyList()
            if (parameters.size != header.second || parameters.toSet().size != parameters.size) {
                smtUnsupported("datatype parameter arity or names are invalid")
            }
            val parameterIdentities = parameters.indices.map { "\u0000datatype:${polynomialSymbol(header.first)}:$it" }
            val parameterSubstitutions = parameters.zip(parameterIdentities.map { SmtSort.Uninterpreted(it) }).toMap()
            for (parameter in parameters) parseSort(SExpr.Atom(parameter), parameterSubstitutions)
            val constructors = (if (polymorphic) body.items[2] as? SExpr.SList else body)
                ?: smtUnsupported("invalid datatype constructors")
            if (constructors.items.isEmpty()) smtUnsupported("datatype has no constructors")
            val constructorNames = HashSet<String>()
            val parsed = constructors.items.map { expression ->
                step()
                val ctor = expression as? SExpr.SList ?: smtUnsupported("invalid datatype constructor")
                val name = (ctor.items.firstOrNull() as? SExpr.Atom)?.text
                    ?: smtUnsupported("missing datatype constructor name")
                if (!constructorNames.add(polynomialSymbol(name))) smtUnsupported("duplicate datatype constructor")
                if (!operators.add(polynomialSymbol(name))) smtUnsupported("duplicate datatype function symbol")
                val selectorNames = HashSet<String>()
                val fields = ctor.items.drop(1).map { fieldExpression ->
                    step()
                    val field = fieldExpression as? SExpr.SList ?: smtUnsupported("invalid datatype selector")
                    field.polynomialSize(2)
                    val selector = (field.items[0] as? SExpr.Atom)?.text ?: smtUnsupported("invalid selector name")
                    if (!selectorNames.add(polynomialSymbol(selector))) smtUnsupported("duplicate constructor selector")
                    if (!operators.add(polynomialSymbol(selector))) smtUnsupported("duplicate datatype function symbol")
                    val sort = parseSort(field.items[1], parameterSubstitutions)
                    if (containsGroupBelowTop(sort, group)) {
                        smtUnsupported("recursive datatype occurs below a field sort head")
                    }
                    SmtDatatypeField(selector, sort)
                }
                SmtDatatypeConstructor(name, fields)
            }
            declared += SmtDatatypeDefinition(header.first, parameterIdentities, parsed)
        }
        val founded = HashSet<String>()
        var changed = true
        while (changed) {
            changed = false
            for (definition in declared) {
                step()
                val name = polynomialSymbol(definition.name)
                if (name !in founded && definition.constructors.any { constructor ->
                        constructor.fields.all { field ->
                            val dependency = (field.sort as? SmtSort.Uninterpreted)?.name?.let(::polynomialSymbol)
                            dependency !in group || dependency in founded
                        }
                    }) {
                    founded += name
                    changed = true
                }
            }
        }
        if (founded != group) smtUnsupported("datatype group is not well founded")
        for (definition in declared) {
            val name = polynomialSymbol(definition.name)
            definitions[name] = definition
            groups[name] = group
        }
    }

    fun group(sort: SmtSort.Uninterpreted): Set<String> = groups.getValue(polynomialSymbol(sort.name))

    fun recursive(sort: SmtSort.Uninterpreted): Boolean {
        val start = polynomialSymbol(sort.name)
        val work = ArrayDeque<String>()
        val seen = HashSet<String>()
        work.addLast(start)
        while (work.isNotEmpty()) {
            step()
            val name = work.removeLast()
            if (!seen.add(name)) continue
            val definition = definitions[name] ?: continue
            for (constructor in definition.constructors) {
                for (field in constructor.fields) {
                    val child = (field.sort as? SmtSort.Uninterpreted)?.name?.let(::polynomialSymbol) ?: continue
                    if (child in definition.parameters) continue
                    if (child == start) return true
                    if (child in definitions) work.addLast(child)
                }
            }
        }
        return false
    }

    fun constructorName(instance: SmtDatatypeInstance, constructor: Int): String {
        val name = instance.constructors[constructor].name
        return if (instance.sort.arguments.isEmpty()) name else "(as $name ${instance.sort.toSmtLib()})"
    }

    fun instance(sort: SmtSort): SmtDatatypeInstance? {
        val named = sort as? SmtSort.Uninterpreted ?: return null
        val definition = definitions[polynomialSymbol(named.name)] ?: return null
        if (named.arguments.size != definition.parameters.size) smtUnsupported("invalid datatype instantiation")
        val substitutions = definition.parameters.zip(named.arguments).toMap()
        return SmtDatatypeInstance(named, definition.constructors.map { constructor ->
            step()
            constructor.copy(fields = constructor.fields.map { field ->
                field.copy(sort = substitute(field.sort, substitutions))
            })
        })
    }

    fun hasOperator(name: String): Boolean = definitions.values.any { definition ->
        definition.constructors.any { constructor ->
            polynomialSymbol(constructor.name) == name || constructor.fields.any { polynomialSymbol(it.name) == name }
        }
    }

    fun cases(sort: SmtSort, expression: SExpr): List<SmtDatatypeCase> {
        val instance = instance(sort) ?: smtUnsupported("match expects a datatype scrutinee")
        val cases = expression as? SExpr.SList ?: smtUnsupported("invalid match cases")
        if (cases.items.isEmpty()) smtUnsupported("match has no cases")
        val seen = HashSet<Int>()
        var catchAll = false
        val result = cases.items.map { caseExpression ->
            step()
            if (catchAll) smtUnsupported("match case follows a catch-all pattern")
            val case = caseExpression as? SExpr.SList ?: smtUnsupported("invalid match case")
            case.polynomialSize(2)
            val pattern = case.items[0]
            val parts = if (pattern is SExpr.SList) pattern.items else listOf(pattern)
            val name = (parts.firstOrNull() as? SExpr.Atom)?.text?.let(::polynomialSymbol)
                ?: smtUnsupported("invalid match pattern")
            val constructor = instance.constructors.indexOfFirst { polynomialSymbol(it.name) == name }
            if (constructor < 0) {
                if (pattern !is SExpr.Atom) smtUnsupported("unknown match constructor")
                if (name in setOf("true", "false")) smtUnsupported("invalid match variable")
                catchAll = true
                SmtDatatypeCase(null, listOf(name), case.items[1])
            } else {
                if (!seen.add(constructor)) smtUnsupported("duplicate match constructor")
                val fields = instance.constructors[constructor].fields
                if (parts.size != fields.size + 1) smtUnsupported("match constructor arity differs")
                val variables = parts.drop(1).map {
                    (it as? SExpr.Atom)?.text?.let(::polynomialSymbol)
                        ?: smtUnsupported("invalid match variable")
                }
                if (variables.toSet().size != variables.size) smtUnsupported("duplicate match variable")
                if (variables.any { it in setOf("true", "false") }) smtUnsupported("invalid match variable")
                SmtDatatypeCase(constructor, variables, case.items[1])
            }
        }
        if (!catchAll && seen.size != instance.constructors.size) smtUnsupported("match is not exhaustive")
        return result
    }

    fun resolve(name: String, arguments: List<SmtSort>, qualification: SmtSort? = null,
        tester: Boolean = false): SmtDatatypeOperator? {
        val candidates = ArrayList<SmtDatatypeOperator>()
        for (definition in definitions.values) {
            for ((constructorIndex, constructor) in definition.constructors.withIndex()) {
                step()
                val isConstructor = polynomialSymbol(constructor.name) == name
                val selectors = if (tester) emptyList() else constructor.fields.indices.filter {
                    polynomialSymbol(constructor.fields[it].name) == name
                }
                if (!isConstructor && selectors.isEmpty()) continue
                val choices: List<Int?> = if (isConstructor) listOf(null) else selectors
                for (selector in choices) {
                    val substitutions = HashMap<String, SmtSort>()
                    val resultTemplate = SmtSort.Uninterpreted(definition.name,
                        definition.parameters.map { SmtSort.Uninterpreted(it) })
                    val parameterTemplates = if (tester || selector != null) listOf(resultTemplate)
                    else constructor.fields.map { it.sort }
                    if (parameterTemplates.size != arguments.size) continue
                    val outputTemplate = when {
                        tester -> SmtSort.Bool
                        selector != null -> constructor.fields[selector].sort
                        else -> resultTemplate
                    }
                    if (qualification != null && !unify(outputTemplate, qualification,
                            definition.parameters.toSet(), substitutions, false)) continue
                    if (!parameterTemplates.indices.all {
                            unify(parameterTemplates[it], arguments[it], definition.parameters.toSet(), substitutions)
                        }) continue
                    if (definition.parameters.any { it !in substitutions }) continue
                    val sort = substitute(resultTemplate, substitutions)
                    val instance = instance(sort) ?: continue
                    val operator = SmtDatatypeOperator(instance, constructorIndex, selector, tester)
                    if (qualification == null || operator.result == qualification) candidates += operator
                }
            }
        }
        return candidates.distinct().singleOrNull()
    }

    fun substitute(root: SmtSort, substitutions: Map<String, SmtSort>): SmtSort {
        val work = ArrayDeque<Pair<SmtSort, Boolean>>()
        val values = ArrayDeque<SmtSort>()
        work.addLast(root to false)
        while (work.isNotEmpty()) {
            step()
            val (sort, combine) = work.removeLast()
            when (sort) {
                is SmtSort.Array -> if (combine) {
                    val element = values.removeLast()
                    values.addLast(SmtSort.Array(values.removeLast(), element))
                } else {
                    work.addLast(sort to true)
                    work.addLast(sort.element to false)
                    work.addLast(sort.index to false)
                }
                is SmtSort.Uninterpreted -> if (sort.arguments.isEmpty()) {
                    values.addLast(substitutions[polynomialSymbol(sort.name)] ?: sort)
                } else if (combine) {
                    val arguments = List(sort.arguments.size) { values.removeLast() }.reversed()
                    values.addLast(sort.copy(arguments = arguments))
                } else {
                    work.addLast(sort to true)
                    for (argument in sort.arguments.asReversed()) work.addLast(argument to false)
                }
                else -> values.addLast(sort)
            }
        }
        return values.single()
    }

    private fun unify(template: SmtSort, actual: SmtSort, parameters: Set<String>,
        substitutions: MutableMap<String, SmtSort>, promotion: Boolean = true): Boolean {
        val work = ArrayDeque<Triple<SmtSort, SmtSort, Boolean>>()
        work.addLast(Triple(template, actual, promotion))
        while (work.isNotEmpty()) {
            step()
            val (first, second, promote) = work.removeLast()
            if (first is SmtSort.Uninterpreted && first.arguments.isEmpty() &&
                polynomialSymbol(first.name) in parameters) {
                val name = polynomialSymbol(first.name)
                val previous = substitutions[name]
                when {
                    previous == null -> substitutions[name] = second
                    previous == second -> Unit
                    promote && previous == SmtSort.Real && second == SmtSort.Int -> Unit
                    promote && previous == SmtSort.Int && second == SmtSort.Real -> substitutions[name] = second
                    else -> return false
                }
            } else when {
                first is SmtSort.Array && second is SmtSort.Array -> {
                    work.addLast(Triple(first.index, second.index, false))
                    work.addLast(Triple(first.element, second.element, false))
                }
                first is SmtSort.Uninterpreted && second is SmtSort.Uninterpreted -> {
                    if (polynomialSymbol(first.name) != polynomialSymbol(second.name) ||
                        first.arguments.size != second.arguments.size) return false
                    for (index in first.arguments.indices) {
                        work.addLast(Triple(first.arguments[index], second.arguments[index], false))
                    }
                }
                first == second || (promote && first == SmtSort.Real && second == SmtSort.Int) -> Unit
                else -> return false
            }
        }
        return true
    }

    private fun containsGroupBelowTop(root: SmtSort, group: Set<String>): Boolean {
        val work = ArrayDeque<SmtSort>()
        when (root) {
            is SmtSort.Array -> {
                work.addLast(root.index)
                work.addLast(root.element)
            }
            is SmtSort.Uninterpreted -> for (argument in root.arguments) work.addLast(argument)
            else -> Unit
        }
        while (work.isNotEmpty()) {
            step()
            when (val sort = work.removeLast()) {
                is SmtSort.Uninterpreted -> {
                    if (polynomialSymbol(sort.name) in group) return true
                    for (argument in sort.arguments) work.addLast(argument)
                }
                is SmtSort.Array -> {
                    work.addLast(sort.index)
                    work.addLast(sort.element)
                }
                else -> Unit
            }
        }
        return false
    }

    private fun step() {
        val current = checkpoint
        if (current == null) cancellation.polynomialSourceCheckpoint(1) else current(1)
    }
}
