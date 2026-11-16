package com.eignex.klause.formats.smtlib

import com.eignex.klause.util.ExecutionControl

internal fun validateSmtSessionSource(commands: List<SExpr>, control: ExecutionControl) {
    smtSessionScope(commands, control)
}

internal fun prepareSmtSessionCommand(
    commands: List<SExpr>, command: SExpr.SList, control: ExecutionControl,
): List<SExpr.SList> {
    if ((command.items.firstOrNull() as? SExpr.Atom)?.text in setOf("define-fun-rec", "define-funs-rec")) {
        return prepareRecursiveCommand(commands, command, control)
    }
    val scope = smtSessionScope(commands, control)
    return normalizeSmtSourceCommands(listOf(command), scope, control).map { it as SExpr.SList }
}

private fun prepareRecursiveCommand(
    commands: List<SExpr>, command: SExpr.SList, control: ExecutionControl,
): List<SExpr.SList> {
    val fields = recursiveFields(command)
    val scope = smtSessionScope(commands, control)
    val used = sessionSymbols(commands + command)
    var next = 0
    fun fresh(): SExpr.Atom {
        var name: String
        do {
            name = "klause-session-recursive-${next++}"
        } while (!used.add(name))
        return SExpr.Atom(name)
    }
    for (definition in fields) {
        normalizeSmtSourceCommands(listOf(recursiveDeclaration(definition)), scope, control)
    }
    val labels = ArrayList<SExpr.SList>()
    val bodies = fields.map { definition ->
        val wrapper = SExpr.SList(listOf(SExpr.Atom("define-fun"), fresh()) + definition.drop(1))
        val normalized = normalizeSmtSourceCommands(listOf(wrapper), scope, control)
        labels += normalized.dropLast(1).map { it as SExpr.SList }
        (normalized.last() as SExpr.SList).items[4]
    }
    val normalized = if ((command.items[0] as SExpr.Atom).text == "define-fun-rec") {
        SExpr.SList(command.items.take(4) + bodies.single())
    } else SExpr.SList(command.items.take(2) + SExpr.SList(bodies))
    val result = labels + normalized
    validateSmtSessionSource(commands + result, control)
    return result
}

private fun recursiveFields(command: SExpr.SList): List<List<SExpr>> = when (
    (command.items.firstOrNull() as? SExpr.Atom)?.text
) {
    "define-fun-rec" -> {
        if (command.items.size != 5) throw UnsupportedSmtException("invalid recursive definition")
        listOf(command.items.drop(1))
    }
    "define-funs-rec" -> {
        if (command.items.size != 3) throw UnsupportedSmtException("invalid recursive definitions")
        val signatures = command.items[1] as? SExpr.SList
            ?: throw UnsupportedSmtException("expected recursive signatures")
        val bodies = command.items[2] as? SExpr.SList ?: throw UnsupportedSmtException("expected recursive bodies")
        if (signatures.items.isEmpty() || signatures.items.size != bodies.items.size) {
            throw UnsupportedSmtException("recursive signature/body count mismatch")
        }
        signatures.items.mapIndexed { index, signature ->
            val fields = (signature as? SExpr.SList)?.items
                ?: throw UnsupportedSmtException("invalid recursive signature")
            if (fields.size != 3) throw UnsupportedSmtException("invalid recursive signature")
            fields + bodies.items[index]
        }
    }
    else -> emptyList()
}

internal fun smtSessionScope(commands: List<SExpr>, control: ExecutionControl): SmtLibScope {
    val scope = SmtLibScope(control)
    val used = sessionSymbols(commands)
    var next = 0
    fun fresh(): SExpr.Atom {
        var name: String
        do {
            name = "klause-session-check-${next++}"
        } while (!used.add(name))
        return SExpr.Atom(name)
    }
    for (expression in commands) {
        control.charge(1)
        val command = expression as? SExpr.SList ?: throw UnsupportedSmtException("expected a command")
        val head = (command.items.firstOrNull() as? SExpr.Atom)?.text
        if (head in setOf("check-sat", "check-sat-assuming", "get-model", "get-objectives", "exit")) continue
        val definitions = recursiveFields(command)
        if (definitions.isEmpty()) {
            normalizeSmtSourceCommands(listOf(command), scope, control)
        } else {
            for (definition in definitions) {
                normalizeSmtSourceCommands(listOf(recursiveDeclaration(definition)), scope, control)
            }
            for (definition in definitions) {
                val check = SExpr.SList(listOf(SExpr.Atom("define-fun"), fresh()) + definition.drop(1))
                normalizeSmtSourceCommands(listOf(check), scope, control)
            }
        }
    }
    return scope
}

private fun recursiveDeclaration(definition: List<SExpr>): SExpr.SList {
    val parameters = definition[1] as? SExpr.SList ?: smtUnsupported("expected recursive parameters")
    val sorts = parameters.items.map {
        val pair = it as? SExpr.SList ?: smtUnsupported("invalid recursive parameter")
        if (pair.items.size != 2) smtUnsupported("invalid recursive parameter")
        pair.items[1]
    }
    return SExpr.SList(listOf(SExpr.Atom("declare-fun"), definition[0], SExpr.SList(sorts), definition[2]))
}

internal data class SmtSessionQueries(val terms: List<SExpr>, val definitions: List<SExpr.SList>)

internal fun smtSessionQuerySort(
    commands: List<SExpr>, term: SExpr, control: ExecutionControl,
    abstractValues: Map<String, SmtSort> = emptyMap(),
): SmtSort {
    val prepared = prepareSmtSessionQueries(commands, listOf(term), control, abstractValues)
    val scope = smtSessionScope(commands + prepared.definitions, control)
    abstractValues.forEach { (name, sort) -> scope.modelValue(name, sort) }
    return scope.termSort(prepared.terms.single())
        ?: throw UnsupportedSmtException("query is not a closed well-sorted term")
}

internal fun prepareSmtSessionQueries(
    commands: List<SExpr>,
    queries: List<SExpr>,
    control: ExecutionControl,
    abstractValues: Map<String, SmtSort> = emptyMap(),
): SmtSessionQueries {
    val scope = smtSessionScope(commands, control)
    abstractValues.forEach { (name, sort) -> scope.modelValue(name, sort) }
    val used = sessionSymbols(commands + queries)
    val terms = ArrayList<SExpr>()
    val definitions = ArrayList<SExpr.SList>()
    var next = 0
    for (query in queries) {
        var name: String
        do {
            name = "klause-session-query-${next++}"
        } while (!used.add(name))
        val binding = SExpr.SList(listOf(SExpr.Atom(name), query))
        val wrapper = SExpr.SList(listOf(SExpr.Atom("assert"), SExpr.SList(listOf(SExpr.Atom("let"),
            SExpr.SList(listOf(binding)), SExpr.Atom("true")))))
        val normalized = normalizeSmtSourceCommands(listOf(wrapper), scope, control)
        definitions += normalized.dropLast(1).map { it as SExpr.SList }
        val assertion = normalized.last() as SExpr.SList
        val let = assertion.items[1] as SExpr.SList
        val bindings = let.items[1] as SExpr.SList
        terms += (bindings.items[0] as SExpr.SList).items[1]
    }
    return SmtSessionQueries(terms, definitions)
}

private fun sessionSymbols(expressions: List<SExpr>): MutableSet<String> {
    val names = HashSet<String>()
    val pending = ArrayDeque<SExpr>()
    pending.addAll(expressions)
    while (pending.isNotEmpty()) {
        when (val term = pending.removeLast()) {
            is SExpr.Atom -> names += smtSessionSymbol(term.text)
            is SExpr.SList -> pending.addAll(term.items)
        }
    }
    return names
}
