package com.eignex.klause.formats.smtlib

import com.eignex.klause.util.ExecutionControl

internal fun validateSmtSessionSource(commands: List<SExpr>, control: ExecutionControl) {
    smtSessionScope(commands, control)
}

internal fun prepareSmtSessionCommand(
    commands: List<SExpr>, command: SExpr.SList, control: ExecutionControl,
): List<SExpr.SList> {
    val scope = smtSessionScope(commands, control)
    return normalizeSmtSourceCommands(listOf(command), scope, control).map { it as SExpr.SList }
}

internal fun smtSessionScope(commands: List<SExpr>, control: ExecutionControl): SmtLibScope {
    val scope = SmtLibScope(control)
    val used = sessionSymbols(commands)
    var next = 0
    fun fresh(): SExpr.Atom {
        var name: String
        do { name = "klause-session-check-${next++}" } while (!used.add(name))
        return SExpr.Atom(name)
    }
    for (expression in commands) {
        control.charge(1)
        val command = expression as? SExpr.SList ?: throw UnsupportedSmtException("expected a command")
        val head = (command.items.firstOrNull() as? SExpr.Atom)?.text
        if (head in setOf("check-sat", "check-sat-assuming", "get-model", "get-objectives", "exit")) continue
        val definitions = when (head) {
            "define-fun-rec" -> {
                if (command.items.size != 5) throw UnsupportedSmtException("invalid recursive definition")
                listOf(command.items.drop(1))
            }
            "define-funs-rec" -> {
                if (command.items.size != 3) throw UnsupportedSmtException("invalid recursive definitions")
                val signatures = command.items[1] as? SExpr.SList
                    ?: throw UnsupportedSmtException("expected recursive signatures")
                val bodies = command.items[2] as? SExpr.SList
                    ?: throw UnsupportedSmtException("expected recursive bodies")
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
        if (definitions.isEmpty()) {
            normalizeSmtSourceCommands(listOf(command), scope, control)
        } else {
            for (definition in definitions) {
                val parameters = definition[1] as? SExpr.SList
                    ?: throw UnsupportedSmtException("expected recursive parameters")
                val sorts = parameters.items.map {
                    val pair = it as? SExpr.SList ?: throw UnsupportedSmtException("invalid recursive parameter")
                    if (pair.items.size != 2) throw UnsupportedSmtException("invalid recursive parameter")
                    pair.items[1]
                }
                val declaration = SExpr.SList(listOf(SExpr.Atom("declare-fun"), definition[0],
                    SExpr.SList(sorts), definition[2]))
                normalizeSmtSourceCommands(listOf(declaration), scope, control)
            }
            for (definition in definitions) {
                val check = SExpr.SList(listOf(SExpr.Atom("define-fun"), fresh()) + definition.drop(1))
                normalizeSmtSourceCommands(listOf(check), scope, control)
            }
        }
    }
    return scope
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
        do { name = "klause-session-query-${next++}" } while (!used.add(name))
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

internal fun validateSmtSessionQueries(
    commands: List<SExpr>, queries: List<SExpr>, control: ExecutionControl,
) {
    prepareSmtSessionQueries(commands, queries, control)
}

internal fun smtSessionNamedTerms(
    commands: List<SExpr>, control: ExecutionControl,
): List<Pair<SExpr.Atom, SmtSort>> {
    val scope = smtSessionScope(commands, control)
    val names = ArrayList<SExpr.Atom>()
    val pending = ArrayDeque<Pair<SExpr, Boolean>>()
    commands.asReversed().forEach { pending += it to false }
    while (pending.isNotEmpty()) {
        control.charge(1)
        val (term, visited) = pending.removeLast()
        if (term !is SExpr.SList) continue
        if (visited) {
            if ((term.items.firstOrNull() as? SExpr.Atom)?.text == "!") {
                for (index in 2 until term.items.lastIndex) {
                    if ((term.items[index] as? SExpr.Atom)?.text == ":named") {
                        names += term.items[index + 1] as? SExpr.Atom
                            ?: throw UnsupportedSmtException(":named requires a symbol")
                    }
                }
            }
        } else {
            pending += term to true
            term.items.asReversed().forEach { pending += it to false }
        }
    }
    return names.map { it to (scope.termSort(it)
        ?: throw UnsupportedSmtException("named term has no source sort")) }
}

private fun sessionSymbols(expressions: List<SExpr>): MutableSet<String> {
    val names = HashSet<String>()
    val pending = ArrayDeque<SExpr>()
    pending.addAll(expressions)
    while (pending.isNotEmpty()) when (val term = pending.removeLast()) {
        is SExpr.Atom -> names += smtSessionSymbol(term.text)
        is SExpr.SList -> pending.addAll(term.items)
    }
    return names
}
