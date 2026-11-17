package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.util.ExecutionControl
import kotlin.coroutines.cancellation.CancellationException

/** A checked interpretation retained until the assertion context changes. */
interface SmtSessionModel {
    /** Render the model response. */
    fun render(): String

    /** Evaluate a well-sorted source term in this same interpretation. */
    fun evaluate(term: SExpr): String

    /** Abstract domain elements available to later queries on this interpretation. */
    fun abstractValues(): Map<String, SmtSort> = emptyMap()

    /** Reuse this fixed interpretation with a universally certified recursive source definition. */
    fun withRecursiveDefinitions(source: List<SExpr>): SmtSessionModel? = null
}

/** The result of one independent assertion snapshot. */
data class SmtSessionResult(
    /** One SMT check-sat status. */
    val status: String,
    /** A retained interpretation when one is available. */
    val model: SmtSessionModel? = null,
    /** A solver-defined reason for an inconclusive query. */
    val reasonUnknown: String? = null,
    /** Solver observations emitted after the status response. */
    val observations: List<String> = emptyList(),
) {
    init {
        require(status in setOf("sat", "unsat", "unknown"))
    }
}

/** Stateful SMT-LIB command processing over an injected, mode-neutral snapshot solver. */
class SmtLibSession(
    private val solve: (String) -> SmtSessionResult,
    respond: (String) -> Unit,
    private val cancellation: ExecutionControl = ExecutionControl.Never,
    diagnostic: (String) -> Unit = {},
    openAppend: (String) -> SmtSessionSink = { throw UnsupportedSmtException("no append destination is configured") },
    private val version: String = "unknown",
    private val authors: String = "Eignex",
    private val validate: (List<SExpr>, ExecutionControl) -> Unit = ::validateSmtSessionSource,
) : AutoCloseable {
    private data class Entry(
        val command: SExpr.SList, val global: Boolean,
        val assertionSource: SExpr? = null, val named: Boolean = false,
    )
    private data class Scope(val entries: MutableList<Entry> = ArrayList())
    private data class State(
        val scopes: List<Scope>, val options: Map<String, SExpr>, val information: Map<String, SExpr>,
        val logic: SExpr.SList?, val result: SmtSessionResult?, val started: Boolean,
        val queryDefinitions: List<SExpr.SList>,
    )
    private val scopes = arrayListOf(Scope())
    private val options = LinkedHashMap<String, SExpr>()
    private val information = LinkedHashMap<String, SExpr>()
    private val channels = SmtSessionChannels(respond, diagnostic, openAppend)
    private var logic: SExpr.SList? = null
    private var result: SmtSessionResult? = null
    private var exited = false
    private var started = false
    private val queryDefinitions = ArrayList<SExpr.SList>()

    /** Execute commands in order; errors preserve state and leave the session usable. */
    fun execute(reader: SExprReader) {
        while (!exited) {
            checkpoint()
            val command = try {
                reader.readCommandOrNull() ?: break
            } catch (error: UnsupportedSmtException) {
                channels.regular(smtSessionError(error.message.orEmpty()))
                break
            }
            execute(command)
        }
    }

    /** Execute one command, returning whether the session remains open. */
    fun execute(expression: SExpr): Boolean {
        if (exited) return false
        checkpoint()
        val saved = save()
        val commandName = (((expression as? SExpr.SList)?.items?.firstOrNull()) as? SExpr.Atom)?.text
        try {
            val command = expression as? SExpr.SList ?: fail("expected a command")
            when (val head = command.atom(0)) {
                "set-logic" -> {
                    command.size(2)
                    if (started) fail("set-logic must precede declarations and assertions")
                    command.atom(1)
                    logic = command
                    started = true
                    success()
                }
                "set-option" -> setOption(command)
                "get-option" -> {
                    command.size(2)
                    val key = command.keyword(1)
                    val value = when (key) {
                        ":regular-output-channel" -> SExpr.Atom(smtSessionString(channels.name(false)))
                        ":diagnostic-output-channel" -> SExpr.Atom(smtSessionString(channels.name(true)))
                        ":interactive-mode" -> options[":produce-assertions"] ?: defaultOption(":produce-assertions")
                        else -> options[key] ?: defaultOption(key)
                    }
                    channels.regular(value?.smtText() ?: "unsupported")
                }
                "set-info" -> {
                    if (command.items.size !in 2..3) {
                        fail("expected an information attribute")
                    }
                    information[command.keyword(1)] = command.items.getOrNull(2) ?: SExpr.Atom("")
                    success()
                }
                "get-info" -> getInfo(command)
                "declare-sort", "declare-sort-parameter", "define-sort", "declare-const", "define-const", "declare-fun",
                "define-fun", "define-fun-rec", "define-funs-rec", "declare-datatype", "declare-datatypes" -> {
                    addCommand(command)
                    started = true
                    invalidate()
                    success()
                }
                "assert", "minimize", "maximize" -> {
                    command.size(2)
                    addCommand(command)
                    started = true
                    invalidate()
                    success()
                }
                "push" -> {
                    command.size(2)
                    val count = command.numeral(1)
                    if (count > 1_000_000 - scopes.size) fail("assertion stack resource limit")
                    repeat(count) {
                        checkpoint()
                        scopes += Scope()
                    }
                    if (count > 0) invalidate()
                    started = true
                    success()
                }
                "pop" -> {
                    command.size(2)
                    val count = command.numeral(1)
                    if (count >= scopes.size) fail("pop exceeds the assertion stack")
                    repeat(count) { scopes.removeAt(scopes.lastIndex) }
                    if (count > 0) {
                        invalidate()
                        validateCurrent()
                    }
                    success()
                }
                "reset-assertions" -> {
                    command.size(1)
                    val retained = scopes.flatMap { scope -> scope.entries.filter { it.global } }
                    scopes.clear()
                    scopes += Scope(retained.toMutableList())
                    invalidate()
                    success()
                }
                "reset" -> {
                    command.size(1)
                    scopes.clear()
                    scopes += Scope()
                    options.clear()
                    information.clear()
                    logic = null
                    started = false
                    invalidate()
                    channels.reset()
                    success()
                }
                "check-sat", "check-sat-assuming" -> check(command, head)
                "get-model" -> {
                    command.size(1)
                    channels.regular(model().render())
                }
                "get-value" -> getValue(command)
                "get-assertions" -> {
                    command.size(1)
                    if (!enabled(":produce-assertions")) fail("assertion production is disabled")
                    channels.regular(assertions().joinToString(" ", "(", ")") { it.smtText() })
                }
                "get-assignment" -> getAssignment(command)
                "get-proof", "get-unsat-core", "get-unsat-assumptions" -> {
                    command.size(1)
                    val option = when (head) {
                        "get-proof" -> ":produce-proofs"
                        "get-unsat-core" -> ":produce-unsat-cores"
                        else -> ":produce-unsat-assumptions"
                    }
                    if (!enabled(option)) fail("$option is disabled")
                    if (result?.status != "unsat") fail("the last result was not unsat")
                    channels.regular("unsupported")
                }
                "echo" -> {
                    command.size(2)
                    channels.regular(smtSessionString(smtSessionStringValue(command.items[1])))
                }
                "exit" -> {
                    command.size(1)
                    success()
                    exited = true
                }
                else -> channels.regular("unsupported")
            }
        } catch (error: UnsupportedSmtException) {
            restore(saved)
            channels.regular(smtSessionError(error.message.orEmpty()))
        } catch (error: IllegalArgumentException) {
            restore(saved)
            channels.regular(smtSessionError(error.message ?: "invalid command"))
        } catch (error: PolynomialSourceInterrupted) {
            restore(saved)
            val cancellationError = CancellationException("SMT command processing interrupted")
            cancellationError.addSuppressed(error)
            interrupted(commandName, cancellationError)
        } catch (error: CancellationException) {
            restore(saved)
            interrupted(commandName, error)
        }
        return !exited
    }

    private fun interrupted(commandName: String?, error: CancellationException) {
        if (commandName in setOf("check-sat", "check-sat-assuming")) {
            result = SmtSessionResult("unknown", reasonUnknown = "interrupted")
            channels.regular("unknown")
            exited = true
        } else throw error
    }

    private fun commands(): List<SExpr> = buildList {
        logic?.let(::add)
        scopes.forEach { scope -> scope.entries.forEach { add(it.command) } }
    }
    private fun assertions(): List<SExpr> = scopes.flatMap { scope ->
        scope.entries.mapNotNull { it.assertionSource }
    }
    private fun addCommand(command: SExpr.SList) {
        val head = command.atom(0)
        val prepared = prepareSmtSessionCommand(commands(), command, cancellation)
        prepared.forEachIndexed { index, normalized ->
            val named = index != prepared.lastIndex
            val assertion = command.items.getOrNull(1)?.takeIf { head == "assert" && !named }
            val global = enabled(":global-declarations") && (named || head !in setOf("assert", "minimize", "maximize"))
            declarationScope(global).entries += Entry(normalized, global, assertion, named)
        }
    }
    private fun declarationScope(global: Boolean): Scope = if (global) scopes.first() else scopes.last()
    private fun validateCurrent() = validate(commands(), cancellation)

    private fun setOption(command: SExpr.SList) {
        command.size(3)
        val original = command.keyword(1)
        val key = if (original == ":interactive-mode") ":produce-assertions" else original
        if (key in START_OPTIONS && started) fail("$key may be set only in start mode")
        when (key) {
            ":print-success", ":produce-models", ":produce-assertions",
            ":produce-assignments", ":global-declarations" -> {
                if (command.atom(2) !in setOf("true", "false")) fail("$key expects a Boolean")
                options[key] = command.items[2]
                success()
            }
            ":regular-output-channel", ":diagnostic-output-channel" -> {
                val destination = smtSessionStringValue(command.items[2])
                channels.select(destination, key == ":diagnostic-output-channel")
                success()
            }
            else -> channels.regular("unsupported")
        }
    }

    private fun getInfo(command: SExpr.SList) {
        command.size(2)
        val key = command.keyword(1)
        if (key == ":all-statistics") {
            channels.regular("unsupported")
            return
        }
        val value = when (key) {
            ":name" -> smtSessionString("klause")
            ":version" -> smtSessionString(version)
            ":authors" -> smtSessionString(authors)
            ":error-behavior" -> "continued-execution"
            ":assertion-stack-levels" -> scopes.lastIndex.toString()
            ":reason-unknown" -> {
                val current = result ?: fail("no check-sat result is available")
                if (current.status != "unknown") fail("the last result was not unknown")
                current.reasonUnknown?.let(::smtSessionString) ?: "incomplete"
            }
            else -> null
        }
        channels.regular(value?.let { "($key $it)" } ?: "unsupported")
    }

    private fun check(command: SExpr.SList, head: String) {
        command.size(if (head == "check-sat") 1 else 2)
        val assumptions = if (head == "check-sat") emptyList() else
            (command.items[1] as? SExpr.SList)?.items ?: fail("expected assumption formulas")
        val prepared = prepareSmtSessionQueries(commands(), assumptions, cancellation)
        addQueryDefinitions(prepared.definitions)
        val source = commands() + prepared.terms.map { SExpr.SList(listOf(SExpr.Atom("assert"), it)) }
        validate(source, cancellation)
        val solved = solve((source + SExpr.SList(listOf(SExpr.Atom("check-sat")))).joinToString("\n") { it.smtText() })
        result = solved
        queryDefinitions.clear()
        started = true
        channels.regular(solved.status)
        solved.observations.forEach(::comment)
        if (cancellation()) exited = true
    }

    private fun getValue(command: SExpr.SList) {
        command.size(2)
        val terms = command.items[1] as? SExpr.SList ?: fail("get-value expects a term list")
        if (terms.items.isEmpty()) fail("get-value expects at least one term")
        val current = model()
        val prepared = prepareSmtSessionQueries(commands(), terms.items, cancellation, current.abstractValues())
        val definitions = queryDefinitions + prepared.definitions
        val pairs = prepared.terms.mapIndexed { index, term ->
            "(${terms.items[index].smtText()} ${current.evaluate(withQueryDefinitions(term, definitions))})"
        }
        addQueryDefinitions(prepared.definitions)
        queryDefinitions += prepared.definitions
        channels.regular(pairs.joinToString(" ", "(", ")"))
    }

    private fun addQueryDefinitions(definitions: List<SExpr.SList>) {
        val global = enabled(":global-declarations")
        declarationScope(global).entries += definitions.map { Entry(it, global, named = true) }
    }

    private fun getAssignment(command: SExpr.SList) {
        command.size(1)
        if (!enabled(":produce-assignments")) fail("assignment production is disabled")
        val current = model(requireProduction = false)
        val labels = scopes.flatMap { scope -> scope.entries.filter { it.named }.map { it.command } }
        val pairs = labels.mapNotNull { definition ->
            val name = definition.items[1]
            if (definition.items[3] == SExpr.Atom("Bool")) {
                "(${name.smtText()} ${current.evaluate(withQueryDefinitions(name, queryDefinitions))})"
            } else null
        }
        channels.regular(pairs.joinToString(" ", "(", ")"))
    }

    private fun model(requireProduction: Boolean = true): SmtSessionModel {
        if (requireProduction && !enabled(":produce-models")) fail("model production is disabled")
        val current = result ?: fail("no check-sat result is available")
        if (current.status == "unsat") fail("the last result was unsat")
        return current.model ?: fail("the last result has no available model")
    }

    private fun enabled(key: String): Boolean = (options[key] ?: defaultOption(key)) == SExpr.Atom("true")
    private fun defaultOption(key: String): SExpr? = when (key) {
        ":print-success", ":produce-models", ":produce-assertions", ":global-declarations", ":produce-proofs",
        ":produce-unsat-cores", ":produce-unsat-assumptions",
        ":produce-assignments", ":interactive-mode" -> SExpr.Atom("false")
        ":random-seed", ":reproducible-resource-limit", ":verbosity" -> SExpr.Atom("0")
        else -> null
    }
    private fun success() {
        if (enabled(":print-success")) channels.regular("success")
    }
    private fun invalidate() {
        result = null
        queryDefinitions.clear()
    }
    private fun withQueryDefinitions(term: SExpr, definitions: List<SExpr.SList>): SExpr {
        var wrapped = term
        for (definition in definitions.asReversed()) {
            val binding = SExpr.SList(listOf(definition.items[1], definition.items[4]))
            wrapped = SExpr.SList(listOf(SExpr.Atom("let"), SExpr.SList(listOf(binding)), wrapped))
        }
        return wrapped
    }
    private fun checkpoint() {
        cancellation.charge(1)
        if (cancellation()) throw CancellationException("SMT session interrupted")
    }
    private fun save(): State = State(scopes.map { Scope(it.entries.toMutableList()) }, options.toMap(),
        information.toMap(), logic, result, started, queryDefinitions.toList())
    private fun restore(saved: State) {
        scopes.clear()
        scopes.addAll(saved.scopes)
        options.clear()
        options.putAll(saved.options)
        information.clear()
        information.putAll(saved.information)
        logic = saved.logic
        result = saved.result
        started = saved.started
        queryDefinitions.clear()
        queryDefinitions.addAll(saved.queryDefinitions)
    }
    override fun close() = channels.close()

    /** End an interrupted invocation while preserving any status already returned for the current context. */
    fun interrupt(reason: String) {
        if (result == null) {
            result = SmtSessionResult("unknown", reasonUnknown = reason)
            channels.regular("unknown")
        }
        comment(reason)
        exited = true
    }

    /** Emit a solver observation on the current regular channel without adding a protocol response. */
    fun comment(text: String) {
        text.lineSequence().forEach { channels.regular(if (it.startsWith(';')) it else "; $it") }
    }

    /** Emit an invocation diagnostic on the currently selected diagnostic channel. */
    fun diagnose(text: String) = channels.diagnostic(text)
    private fun fail(message: String): Nothing = throw UnsupportedSmtException(message)
    private fun SExpr.SList.size(expected: Int) {
        if (items.size != expected) fail("${items.firstOrNull()?.smtText()} expects ${expected - 1} arguments")
    }
    private fun SExpr.SList.atom(index: Int): String =
        (items.getOrNull(index) as? SExpr.Atom)?.text ?: fail("expected a symbol")
    private fun SExpr.SList.keyword(index: Int): String = atom(index).takeIf { it.length > 1 && it.startsWith(':') }
        ?: fail("expected a keyword")
    private fun SExpr.SList.numeral(index: Int): Int {
        val text = atom(index)
        if (text.isEmpty() || text.any { it !in '0'..'9' } || (text.length > 1 && text[0] == '0')) {
            fail("expected a nonnegative numeral")
        }
        return text.toIntOrNull() ?: fail("stack count exceeds available resources")
    }
    private companion object {
        val START_OPTIONS = setOf(":produce-models", ":produce-assertions", ":produce-assignments",
            ":global-declarations",
            ":produce-proofs", ":produce-unsat-cores", ":produce-unsat-assumptions")
    }
}

internal fun smtSessionSymbol(text: String): String =
    if (text.startsWith('|') && text.endsWith('|')) text.drop(1).dropLast(1) else text
internal fun smtSessionError(message: String): String = "(error ${smtSessionString(message)})"

/** Render an S-expression without changing quoted symbols or string contents. */
fun SExpr.smtText(): String = buildString {
    val work = ArrayDeque<Any>()
    work.addLast(this@smtText)
    while (work.isNotEmpty()) {
        when (val item = work.removeLast()) {
            is String -> append(item)
            is SExpr.Atom -> append(item.text)
            is SExpr.SList -> {
                append('(')
                work.addLast(")")
                for (index in item.items.indices.reversed()) {
                    work.addLast(item.items[index])
                    if (index > 0) work.addLast(" ")
                }
            }
        }
    }
}
