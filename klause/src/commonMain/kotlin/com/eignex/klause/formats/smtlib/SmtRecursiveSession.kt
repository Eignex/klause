package com.eignex.klause.formats.smtlib

import com.eignex.klause.util.ExecutionControl

/** Solve recursive equations by necessary instances and independently checked total interpretations. */
fun solveSmtRecursiveSession(
    text: String,
    solve: (String) -> SmtSessionResult,
    cancellation: ExecutionControl = ExecutionControl.Never,
    candidateLimit: Int = 256,
): SmtSessionResult {
    val commands = SExprReader(text).readAll().map { it as? SExpr.SList
        ?: throw UnsupportedSmtException("expected a command") }
    val definitions = ArrayList<RecursiveDefinition>()
    val ordinary = ArrayList<SExpr.SList>()
    for (command in commands) when (command.recHead()) {
        "define-fun-rec" -> definitions += RecursiveDefinition(command.items.drop(1))
        "define-funs-rec" -> {
            val signatures = command.items[1] as SExpr.SList
            val bodies = command.items[2] as SExpr.SList
            for ((index, signature) in signatures.items.withIndex()) {
                definitions += RecursiveDefinition((signature as SExpr.SList).items + bodies.items[index])
            }
        }
        "check-sat", "get-model", "get-value" -> Unit
        else -> ordinary += command
    }
    if (definitions.isEmpty()) return solve(text)
    val names = definitions.map { it.name }.toSet()
    val used = HashSet<String>()
    fun collectSymbols(root: SExpr) {
        val pending = ArrayDeque<SExpr>()
        pending += root
        while (pending.isNotEmpty()) when (val node = pending.removeLast()) {
            is SExpr.Atom -> used += smtSessionSymbol(node.text)
            is SExpr.SList -> pending.addAll(node.items)
        }
    }
    commands.forEach(::collectSymbols)
    var freshIndex = 0
    fun fresh(): SExpr.Atom {
        while (true) {
            val name = "klause-recursive-${freshIndex++}"
            if (used.add(name)) return SExpr.Atom(name)
        }
    }
    fun script(forms: List<SExpr>): String = forms.joinToString("\n", postfix = "\n(check-sat)\n") { it.smtText() }
    fun replaceDefinitions(replacements: List<SExpr>): List<SExpr> {
        val forms = ArrayList<SExpr>()
        val byName = definitions.mapIndexed { index, definition -> definition.name to replacements[index] }.toMap()
        for (command in commands) when (command.recHead()) {
            "define-fun-rec" -> forms += byName.getValue(smtSessionSymbol((command.items[1] as SExpr.Atom).text))
            "define-funs-rec" -> {
                for (signature in (command.items[1] as SExpr.SList).items) {
                    val name = ((signature as SExpr.SList).items[0] as SExpr.Atom).text
                    forms += byName.getValue(smtSessionSymbol(name))
                }
            }
            "check-sat", "get-model", "get-value" -> Unit
            else -> forms += command
        }
        return forms
    }
    val declarations = definitions.map { definition ->
        recList("declare-fun", definition.rawName, SExpr.SList(definition.parameters.map { it.second }), definition.sort)
    }
    val necessary = ArrayList<SExpr>()
    necessary += replaceDefinitions(declarations)
    val applications = ArrayList<Pair<RecursiveDefinition, List<SExpr>>>()
    val byName = definitions.associateBy { it.name }
    val pending = ArrayDeque<SExpr>()
    ordinary.filter { it.recHead() == "assert" }.forEach { pending += it.items[1] }
    while (pending.isNotEmpty()) {
        if (cancellation()) return SmtSessionResult("unknown", reasonUnknown = "cancelled")
        val term = pending.removeLast()
        when (term) {
            is SExpr.Atom -> byName[smtSessionSymbol(term.text)]?.takeIf { it.parameters.isEmpty() }
                ?.let { applications += it to emptyList() }
            is SExpr.SList -> {
                byName[term.recHead()]?.let { applications += it to term.items.drop(1) }
                pending.addAll(term.items.drop(1))
            }
        }
    }
    for (definition in definitions) {
        val arguments = definition.parameters.map { (_, sort) ->
            fresh().also { necessary += recList("declare-const", it, sort) }
        }
        applications += definition to arguments
    }
    for ((definition, arguments) in applications.distinct()) {
        if (arguments.size != definition.parameters.size) throw UnsupportedSmtException("wrong recursive function arity")
        val application = if (arguments.isEmpty()) definition.rawName else SExpr.SList(listOf(definition.rawName) + arguments)
        necessary += recList("assert", recList("=", application,
            substituteRecTerm(definition.body, definition.parameters.map { it.first }.zip(arguments).toMap())))
    }
    val relaxation = solve(script(necessary))
    if (relaxation.status == "unsat") return relaxation
    if (relaxation.status == "sat" && relaxation.model != null) {
        val pinned = (SExprReader(relaxation.model.render()).readAll().single() as SExpr.SList).items
            .map { it as SExpr.SList }.filter { smtSessionSymbol((it.items[1] as SExpr.Atom).text) !in names }
        val pinnedNames = pinned.map { smtSessionSymbol((it.items[1] as SExpr.Atom).text) }.toSet()
        val prelude = ordinary.filter { command ->
            command.recHead() !in setOf("assert", "minimize", "maximize") &&
                (command.recHead() !in setOf("declare-const", "declare-fun", "define-fun") ||
                    smtSessionSymbol((command.items[1] as SExpr.Atom).text) !in pinnedNames)
        }
        val ranking = definitions.associate { definition -> definition.name to
            definition.parameters.indexOfFirst { it.second == SExpr.Atom("Int") } }
        if (ranking.values.all { it >= 0 }) {
            val proof = ArrayList<SExpr>()
            proof += prelude
            proof += pinned
            val badCalls = ArrayList<SExpr>()
            var suitable = true
            for (definition in definitions) {
                val bindings = definition.parameters.associate { (name, sort) ->
                    name to fresh().also { proof += recList("declare-const", it, sort) }
                }
                val body = substituteRecTerm(definition.body, bindings)
                val calls = recursiveCalls(body, names)
                val caller = bindings.getValue(definition.parameters[ranking.getValue(definition.name)].first)
                for ((name, arguments, guards) in calls) {
                    val callee = arguments.getOrNull(ranking.getValue(name))
                    if (callee == null || (listOf(callee) + guards).any { containsRecName(it, names) }) {
                        suitable = false
                        break
                    }
                    val decreases = recList("and", recList(">=", caller, SExpr.Atom("0")),
                        recList(">=", callee, SExpr.Atom("0")), recList("<", callee, caller))
                    badCalls += SExpr.SList(listOf(SExpr.Atom("and")) + guards + recList("not", decreases))
                }
            }
            proof += recList("assert", SExpr.SList(listOf(SExpr.Atom("or")) + badCalls))
            if (suitable && solve(script(proof)).status == "unsat") {
                val recursiveModel = SExpr.SList(pinned + definitions.map {
                    SExpr.SList(listOf(SExpr.Atom("define-fun-rec")) + it.fields)
                }).smtText()
                val checked = SmtGroundModel(text, recursiveModel, cancellation)
                val assertions = ordinary.filter { it.recHead() == "assert" }.map { it.items[1] }
                if (runCatching { assertions.all { checked.evaluate(it) == "true" } }.getOrDefault(false)) {
                    return SmtSessionResult("sat", checked)
                }
            }
        }
    }
    val relaxedDefinitions = relaxation.model?.let {
        (SExprReader(it.render()).readAll().single() as SExpr.SList).items.map { it as SExpr.SList }
    }.orEmpty().associateBy { smtSessionSymbol((it.items[1] as SExpr.Atom).text) }
    val candidates = definitions.map { definition ->
        val relaxed = relaxedDefinitions[definition.name]
        val proposed = if (relaxed != null) {
            val formals = (relaxed.items[2] as SExpr.SList).items.map {
                smtSessionSymbol(((it as SExpr.SList).items[0] as SExpr.Atom).text)
            }
            substituteRecTerm(relaxed.items[4], formals.zip(definition.parameters.map {
                SExpr.Atom(it.first)
            }).toMap())
        } else null
        (definition.candidates(names) + listOfNotNull(proposed)).distinct()
    }
    if (candidates.any { it.isEmpty() }) {
        return SmtSessionResult("unknown", reasonUnknown = "recursive-interpretation-incomplete")
    }
    val positions = IntArray(definitions.size)
    var attempted = 0
    while (attempted++ < candidateLimit) {
        if (cancellation()) return SmtSessionResult("unknown", reasonUnknown = "cancelled")
        val interpretations = definitions.indices.map { index -> definitions[index].define(candidates[index][positions[index]]) }
        val candidateCommands = replaceDefinitions(interpretations)
        val candidateSource = script(candidateCommands)
        val wellSorted = runCatching { validateSmtSessionSource(candidateCommands, cancellation) }.isSuccess
        val candidate = if (wellSorted) solve(candidateSource) else SmtSessionResult("unknown")
        if (candidate.status == "sat" && candidate.model != null) {
            val modelDefinitions = (SExprReader(candidate.model.render()).readAll().single() as SExpr.SList).items
                .map { it as SExpr.SList }
            val modelNames = modelDefinitions.map { smtSessionSymbol((it.items[1] as SExpr.Atom).text) }.toSet()
            val validation = ArrayList<SExpr>()
            validation += ordinary.filter { command ->
                command.recHead() !in setOf("assert", "minimize", "maximize") &&
                    (command.recHead() !in setOf("declare-const", "declare-fun", "define-fun") ||
                        smtSessionSymbol((command.items[1] as SExpr.Atom).text) !in modelNames)
            }
            validation += modelDefinitions.filter { smtSessionSymbol((it.items[1] as SExpr.Atom).text) !in names }
            validation += interpretations
            val equalities = ArrayList<SExpr>()
            for (definition in definitions) {
                val arguments = definition.parameters.map { (_, sort) ->
                    fresh().also { validation += recList("declare-const", it, sort) }
                }
                val application = if (arguments.isEmpty()) definition.rawName else SExpr.SList(listOf(definition.rawName) + arguments)
                equalities += recList("=", application,
                    substituteRecTerm(definition.body, definition.parameters.map { it.first }.zip(arguments).toMap()))
            }
            validation += recList("assert", recList("not", SExpr.SList(listOf(SExpr.Atom("and")) + equalities)))
            if (solve(script(validation)).status == "unsat") return candidate
        }
        var index = positions.lastIndex
        while (index >= 0) {
            positions[index]++
            if (positions[index] < candidates[index].size) break
            positions[index] = 0
            index--
        }
        if (index < 0) break
    }
    return SmtSessionResult("unknown", reasonUnknown = "recursive-interpretation-incomplete")
}

private data class RecursiveCall(val name: String, val arguments: List<SExpr>, val guards: List<SExpr>)

private fun recursiveCalls(body: SExpr, names: Set<String>): List<RecursiveCall> {
    val calls = ArrayList<RecursiveCall>()
    val work = ArrayDeque<Pair<SExpr, List<SExpr>>>()
    work += body to emptyList()
    while (work.isNotEmpty()) {
        val (term, guards) = work.removeLast()
        when (term) {
            is SExpr.Atom -> if (smtSessionSymbol(term.text) in names) calls += RecursiveCall(smtSessionSymbol(term.text), emptyList(), guards)
            is SExpr.SList -> {
                if (term.recHead() in names) calls += RecursiveCall(term.recHead(), term.items.drop(1), guards)
                if (term.recHead() == "ite" && term.items.size == 4) {
                    work += term.items[1] to guards
                    work += term.items[2] to (guards + term.items[1])
                    work += term.items[3] to (guards + recList("not", term.items[1]))
                } else term.items.drop(1).forEach { work += it to guards }
            }
        }
    }
    return calls
}

private fun containsRecName(term: SExpr, names: Set<String>): Boolean {
    val work = ArrayDeque<SExpr>()
    work += term
    while (work.isNotEmpty()) when (val node = work.removeLast()) {
        is SExpr.Atom -> if (smtSessionSymbol(node.text) in names) return true
        is SExpr.SList -> work.addAll(node.items)
    }
    return false
}

private data class RecursiveDefinition(val fields: List<SExpr>) {
    val rawName: SExpr.Atom get() = fields[0] as SExpr.Atom
    val name: String get() = smtSessionSymbol(rawName.text)
    val parameters: List<Pair<String, SExpr>> get() = (fields[1] as SExpr.SList).items.map {
        val pair = it as SExpr.SList
        smtSessionSymbol((pair.items[0] as SExpr.Atom).text) to pair.items[1]
    }
    val sort: SExpr get() = fields[2]
    val body: SExpr get() = fields[3]
    fun define(candidate: SExpr): SExpr.SList = SExpr.SList(listOf(SExpr.Atom("define-fun")) + fields.take(3) + candidate)
    fun candidates(recursiveNames: Set<String>): List<SExpr> {
        val values = ArrayList<SExpr>()
        val scalar = (sort as? SExpr.Atom)?.text
        when (scalar) {
            "Bool" -> values += listOf(SExpr.Atom("false"), SExpr.Atom("true"))
            "Int", "Real" -> values += listOf(SExpr.Atom("0"), SExpr.Atom("1"), recList("-", SExpr.Atom("1")))
            else -> {
                val word = sort as? SExpr.SList
                if (word?.recHead() == "_" && (word.items[1] as? SExpr.Atom)?.text == "BitVec") {
                    values += recList("_", SExpr.Atom("bv0"), word.items[2])
                    values += recList("_", SExpr.Atom("bv1"), word.items[2])
                }
            }
        }
        parameters.filter { it.second == sort }.forEach { values += SExpr.Atom(it.first) }
        val work = ArrayDeque<SExpr>()
        work += body
        while (work.isNotEmpty()) {
            val term = work.removeLast()
            if (term is SExpr.SList) {
                if (term.recHead() == "ite") {
                    values += term.items[2]
                    values += term.items[3]
                }
                work.addAll(term.items.drop(1))
            }
        }
        fun containsRecursive(term: SExpr): Boolean {
            val pending = ArrayDeque<SExpr>()
            pending += term
            while (pending.isNotEmpty()) when (val node = pending.removeLast()) {
                is SExpr.Atom -> if (smtSessionSymbol(node.text) in recursiveNames) return true
                is SExpr.SList -> pending.addAll(node.items)
            }
            return false
        }
        val safe = values.filterNot(::containsRecursive).distinct().toMutableList()
        if (!containsRecursive(body)) safe += body
        return safe.distinct()
    }
}

private fun substituteRecTerm(root: SExpr, replacements: Map<String, SExpr>): SExpr {
    val work = ArrayDeque<RecursiveSubstitutionFrame>()
    val values = ArrayDeque<SExpr>()
    work += RecursiveSubstitutionFrame.Evaluate(root, replacements)
    while (work.isNotEmpty()) {
        when (val frame = work.removeLast()) {
            is RecursiveSubstitutionFrame.Evaluate -> when (val term = frame.term) {
                is SExpr.Atom -> values += frame.environment[smtSessionSymbol(term.text)] ?: term
                is SExpr.SList -> if (term.recHead() == "let") {
                    val bindings = (term.items[1] as SExpr.SList).items.map { it as SExpr.SList }
                    val names = bindings.map { smtSessionSymbol((it.items[0] as SExpr.Atom).text) }
                    work += RecursiveSubstitutionFrame.Let(names, term.items[2], frame.environment)
                    for (binding in bindings.asReversed()) {
                        work += RecursiveSubstitutionFrame.Evaluate(binding.items[1], frame.environment)
                    }
                } else {
                    work += RecursiveSubstitutionFrame.Combine(term.items.size)
                    for (child in term.items.asReversed()) {
                        work += RecursiveSubstitutionFrame.Evaluate(child, frame.environment)
                    }
                }
            }
            is RecursiveSubstitutionFrame.Combine ->
                values += SExpr.SList(List(frame.count) { values.removeLast() }.reversed())
            is RecursiveSubstitutionFrame.Let -> {
                val arguments = List(frame.names.size) { values.removeLast() }.reversed()
                work += RecursiveSubstitutionFrame.Evaluate(frame.body,
                    frame.environment + frame.names.zip(arguments))
            }
        }
    }
    return values.single()
}

private sealed interface RecursiveSubstitutionFrame {
    data class Evaluate(val term: SExpr, val environment: Map<String, SExpr>) : RecursiveSubstitutionFrame
    data class Combine(val count: Int) : RecursiveSubstitutionFrame
    data class Let(val names: List<String>, val body: SExpr, val environment: Map<String, SExpr>) :
        RecursiveSubstitutionFrame
}

private fun SExpr.SList.recHead(): String = (items.firstOrNull() as? SExpr.Atom)?.text?.let(::smtSessionSymbol).orEmpty()
private fun recList(head: String, vararg args: SExpr): SExpr.SList = SExpr.SList(listOf(SExpr.Atom(head)) + args)
