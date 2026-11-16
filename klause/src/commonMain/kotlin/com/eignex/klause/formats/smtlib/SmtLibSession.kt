package com.eignex.klause.formats.smtlib

import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException

/** A checked interpretation retained until the assertion context changes. */
interface SmtSessionModel {
    /** Render the model response. */
    fun render(): String
    /** Evaluate a source term in this same interpretation. */
    fun evaluate(term: SExpr): String
}

/** The result of one independent assertion snapshot. */
data class SmtSessionResult(
    val status: String,
    val model: SmtSessionModel? = null,
    val reasonUnknown: String? = null,
) {
    init { require(status in setOf("sat", "unsat", "unknown")) }
}

/** Stateful SMT-LIB command processing over an injected, mode-neutral snapshot solver. */
class SmtLibSession(
    private val solve: (String) -> SmtSessionResult,
    private val respond: (String) -> Unit,
    private val cancellation: Cancellation = Cancellation.Never,
) {
    private data class Entry(val command: SExpr.SList, val names: List<String>, val global: Boolean)
    private data class Scope(val entries: MutableList<Entry> = ArrayList(), val assertions: MutableList<SExpr> = ArrayList())
    private val scopes = arrayListOf(Scope())
    private val options = LinkedHashMap<String, SExpr>()
    private val information = LinkedHashMap<String, SExpr>()
    private var logic: SExpr.SList? = null
    private var result: SmtSessionResult? = null
    private var exited = false
    private var started = false

    /** Execute commands in order; errors are protocol responses and leave the session usable. */
    fun execute(reader: SExprReader) {
        while (!exited) {
            checkpoint()
            val command = try { reader.readCommandOrNull() ?: break } catch (error: UnsupportedSmtException) {
                respond(smtSessionError(error.message.orEmpty()))
                break
            }
            execute(command)
        }
    }

    /** Execute one command, returning whether the session remains open. */
    fun execute(expression: SExpr): Boolean {
        if (exited) return false
        checkpoint()
        try {
            val command = expression as? SExpr.SList ?: fail("expected a command")
            val head = command.atom(0)
            when (head) {
                "set-logic" -> {
                    command.size(2)
                    if (logic != null || scopes.any { it.entries.isNotEmpty() || it.assertions.isNotEmpty() }) {
                        fail("set-logic must precede declarations and assertions")
                    }
                    command.atom(1)
                    logic = command
                    started = true
                    success()
                }
                "set-option" -> setOption(command)
                "get-option" -> {
                    command.size(2)
                    val key = command.atom(1)
                    val value = options[key] ?: defaultOption(key)
                    respond(value?.smtText() ?: "unsupported")
                }
                "set-info" -> {
                    command.size(3)
                    information[command.atom(1)] = command.items[2]
                    success()
                }
                "get-info" -> getInfo(command)
                "declare-sort", "define-sort", "declare-const", "define-const", "declare-fun", "define-fun", "define-fun-rec",
                "define-funs-rec", "declare-datatype", "declare-datatypes" -> declare(command, head)
                "assert" -> {
                    command.size(2)
                    scopes.last().assertions += command.items[1]
                    started = true
                    invalidate()
                    success()
                }
                "push" -> {
                    command.size(2)
                    val count = command.numeral(1)
                    repeat(count) { scopes += Scope() }
                    if (count > 0) invalidate()
                    started = true
                    success()
                }
                "pop" -> {
                    command.size(2)
                    val count = command.numeral(1)
                    if (count >= scopes.size) fail("pop exceeds the assertion stack")
                    repeat(count) {
                        val removed = scopes.removeAt(scopes.lastIndex)
                        scopes.first().entries += removed.entries.filter { it.global }
                    }
                    if (count > 0) invalidate()
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
                    success()
                }
                "check-sat", "check-sat-assuming" -> check(command, head)
                "get-model" -> {
                    command.size(1)
                    respond(model().render())
                }
                "get-value" -> {
                    command.size(2)
                    val terms = command.items[1] as? SExpr.SList ?: fail("get-value expects a term list")
                    if (terms.items.isEmpty()) fail("get-value expects at least one term")
                    val model = model()
                    val values = terms.items.map { term -> "(${term.smtText()} ${model.evaluate(term)})" }
                    respond(values.joinToString(" ", "(", ")"))
                }
                "get-assertions" -> {
                    command.size(1)
                    if (!enabled(":produce-assertions")) fail("assertion production is disabled")
                    respond(scopes.flatMap { it.assertions }.joinToString(" ", "(", ")") { it.smtText() })
                }
                "get-proof", "get-unsat-core", "get-unsat-assumptions", "get-assignment" -> {
                    command.size(1)
                    respond("unsupported")
                }
                "echo" -> {
                    command.size(2)
                    val text = command.atom(1)
                    if (!text.startsWith('"')) fail("echo expects a string")
                    respond(text)
                }
                "exit" -> { command.size(1); success(); exited = true }
                "minimize", "maximize" -> {
                    command.size(2)
                    scopes.last().entries += Entry(command, emptyList(), false)
                    invalidate()
                    success()
                }
                else -> respond("unsupported")
            }
        } catch (error: UnsupportedSmtException) {
            respond(smtSessionError(error.message.orEmpty()))
        }
        return !exited
    }

    private fun declare(command: SExpr.SList, head: String) {
        val names = when (head) {
            "declare-sort", "declare-const", "declare-datatype" -> { command.size(3); listOf(command.atom(1)) }
            "define-sort", "define-const", "declare-fun" -> { command.size(4); listOf(command.atom(1)) }
            "define-fun", "define-fun-rec" -> { command.size(5); listOf(command.atom(1)) }
            "define-funs-rec" -> {
                command.size(3)
                val signatures = command.items[1] as? SExpr.SList ?: fail("expected recursive signatures")
                val bodies = command.items[2] as? SExpr.SList ?: fail("expected recursive bodies")
                if (signatures.items.size != bodies.items.size || signatures.items.isEmpty()) fail("recursive signature/body count mismatch")
                signatures.items.map { (it as? SExpr.SList ?: fail("invalid recursive signature")).atom(0) }
            }
            else -> {
                command.size(3)
                val signatures = command.items[1] as? SExpr.SList ?: fail("expected datatype signatures")
                signatures.items.map { (it as? SExpr.SList ?: fail("invalid datatype signature")).atom(0) }
            }
        }.map(::smtSessionSymbol)
        val existing = scopes.flatMap { scope -> scope.entries.flatMap { it.names } }.toSet()
        if (names.distinct().size != names.size || names.any { it in existing || it == "true" || it == "false" }) {
            fail("symbol is already declared")
        }
        scopes.last().entries += Entry(command, names, enabled(":global-declarations"))
        started = true
        invalidate()
        success()
    }

    private fun setOption(command: SExpr.SList) {
        command.size(3)
        val key = command.atom(1)
        if (key != ":print-success" && (key.startsWith(":produce-") || key in setOf(":global-declarations", ":interactive-mode")) && started) {
            fail("$key may be set only in start mode")
        }
        when (key) {
            ":print-success", ":produce-models", ":produce-assertions", ":global-declarations" -> {
                if (command.atom(2) !in setOf("true", "false")) fail("$key expects a Boolean")
                options[key] = command.items[2]
                success()
            }
            ":produce-proofs", ":produce-unsat-cores", ":produce-unsat-assumptions", ":produce-assignments" -> {
                if (command.atom(2) !in setOf("true", "false")) fail("$key expects a Boolean")
                if (command.atom(2) == "true") respond("unsupported") else {
                    options[key] = command.items[2]
                    success()
                }
            }
            else -> respond("unsupported")
        }
    }

    private fun getInfo(command: SExpr.SList) {
        command.size(2)
        val key = command.atom(1)
        val value = when (key) {
            ":name" -> "\"klause\""
            ":error-behavior" -> "continued-execution"
            ":assertion-stack-levels" -> scopes.lastIndex.toString()
            ":reason-unknown" -> {
                val current = result ?: fail("no check-sat result is available")
                if (current.status != "unknown") fail("the last result was not unknown")
                current.reasonUnknown ?: "incomplete"
            }
            else -> information[key]?.smtText()
        }
        respond(value?.let { "($key $it)" } ?: "unsupported")
    }

    private fun check(command: SExpr.SList, head: String) {
        command.size(if (head == "check-sat") 1 else 2)
        val assumptions = if (head == "check-sat") emptyList() else {
            val list = command.items[1] as? SExpr.SList ?: fail("expected assumption literals")
            list.items
        }
        val source = buildString {
            logic?.let { appendLine(it.smtText()) }
            for (scope in scopes) for (entry in scope.entries) appendLine(entry.command.smtText())
            for (scope in scopes) for (assertion in scope.assertions) appendLine("(assert ${assertion.smtText()})")
            for (assumption in assumptions) appendLine("(assert ${assumption.smtText()})")
            appendLine("(check-sat)")
        }
        val solved = solve(source)
        checkpoint()
        result = solved
        started = true
        respond(requireNotNull(result).status)
    }

    private fun model(): SmtSessionModel {
        if (!enabled(":produce-models")) fail("model production is disabled")
        val current = result ?: fail("no check-sat result is available")
        if (current.status != "sat") fail("the last result was not sat")
        return current.model ?: fail("the last result has no model")
    }

    private fun enabled(key: String): Boolean = (options[key] ?: defaultOption(key)) == SExpr.Atom("true")
    private fun defaultOption(key: String): SExpr? = when (key) {
        ":print-success" -> SExpr.Atom("false")
        ":produce-models", ":produce-assertions", ":global-declarations", ":produce-proofs", ":produce-unsat-cores",
        ":produce-unsat-assumptions", ":produce-assignments" -> SExpr.Atom("false")
        else -> null
    }
    private fun success() { if (enabled(":print-success")) respond("success") }
    private fun invalidate() { result = null }
    private fun checkpoint() {
        cancellation.charge(1)
        if (cancellation()) throw CancellationException("SMT session interrupted")
    }
    private fun fail(message: String): Nothing = throw UnsupportedSmtException(message)
    private fun SExpr.SList.size(expected: Int) {
        if (items.size != expected) fail("${items.firstOrNull()?.smtText()} expects ${expected - 1} arguments")
    }
    private fun SExpr.SList.atom(index: Int): String = (items.getOrNull(index) as? SExpr.Atom)?.text
        ?: fail("expected a symbol")
    private fun SExpr.SList.numeral(index: Int): Int {
        val text = atom(index)
        if (text.isEmpty() || text.any { it !in '0'..'9' }) fail("expected a nonnegative numeral")
        return text.toIntOrNull() ?: fail("stack count exceeds available resources")
    }
}

internal fun smtSessionSymbol(text: String): String = if (text.startsWith('|') && text.endsWith('|')) text.drop(1).dropLast(1) else text

internal fun smtSessionError(message: String): String = "(error \"${message.replace("\"", "\"\"")}\")"

/** Render an S-expression without changing quoted symbols or string contents. */
fun SExpr.smtText(): String = buildString {
    val work = ArrayDeque<Any>()
    work.addLast(this@smtText)
    while (work.isNotEmpty()) when (val item = work.removeLast()) {
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
