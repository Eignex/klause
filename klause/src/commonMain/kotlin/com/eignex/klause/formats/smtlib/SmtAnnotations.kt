package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun normalizeSmtSourceCommands(
    commands: List<SExpr>,
    scope: SmtLibScope,
    cancellation: ExecutionControl,
): List<SExpr> {
    val normalized = ArrayList<SExpr>()
    fun accept(command: SExpr) {
        if (!scope.command(command)) smtUnsupported("source symbol or expression sort is invalid in its lexical scope")
        normalized += command
    }
    for (command in commands) {
        val commandHead = ((command as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text
        if (commandHead in setOf("set-info", "set-option")) {
            accept(command)
            continue
        }
        val work = ArrayDeque<AnnotationFrame>()
        val values = ArrayDeque<SExpr>()
        work += AnnotationFrame(command, false, emptySet())
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val (node, combine, bound) = work.removeLast()
            when (node) {
                is SExpr.Atom -> values += node
                is SExpr.SList -> if (combine) {
                    val items = List(node.items.size) { values.removeLast() }.reversed()
                    if ((items.firstOrNull() as? SExpr.Atom)?.text == "!") {
                        if (items.size < 3) smtUnsupported("annotated term requires at least one attribute")
                        val body = items[1]
                        var index = 2
                        while (index < items.size) {
                            val keyword = (items[index++] as? SExpr.Atom)?.text
                                ?.takeIf { it.startsWith(':') } ?: smtUnsupported("expected an annotation keyword")
                            val value = items.getOrNull(index)?.takeUnless { it is SExpr.Atom && it.text.startsWith(':') }
                            if (value != null) index++
                            if (keyword == ":named") {
                                val label = value as? SExpr.Atom ?: smtUnsupported(":named requires a symbol")
                                if (freeAnnotationSymbols(body).any { it in bound }) smtUnsupported(":named requires a closed term")
                                val sort = scope.termSort(body) ?: smtUnsupported(":named requires a closed well-sorted term")
                                accept(SExpr.SList(listOf(SExpr.Atom("define-fun"), label, SExpr.SList(emptyList()),
                                    SExprReader(sort.toSmtLib()).readAll().single(), body)))
                            }
                        }
                        values += body
                    } else values += SExpr.SList(items)
                } else {
                    work += AnnotationFrame(node, true, bound)
                    val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
                    val localNames = when (head) {
                        "let" -> (node.items.getOrNull(1) as? SExpr.SList)?.items.orEmpty().mapNotNull {
                            ((it as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text?.let(::smtSessionSymbol)
                        }.toSet()
                        "define-fun", "define-fun-rec" -> (node.items.getOrNull(2) as? SExpr.SList)?.items.orEmpty().mapNotNull {
                            ((it as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text?.let(::smtSessionSymbol)
                        }.toSet()
                        else -> emptySet()
                    }
                    for (index in node.items.indices.reversed()) {
                        val bodyIndex = if (head == "let") 2 else 4
                        work += AnnotationFrame(node.items[index], false, if (index == bodyIndex) bound + localNames else bound)
                    }
                }
            }
        }
        val stripped = values.single()
        val list = stripped as? SExpr.SList
        if ((list?.items?.firstOrNull() as? SExpr.Atom)?.text == "define-const") {
            if (list.items.size != 4) smtUnsupported("define-const expects 3 arguments")
            accept(SExpr.SList(listOf(SExpr.Atom("define-fun"), list.items[1], SExpr.SList(emptyList()),
                list.items[2], list.items[3])))
        } else accept(stripped)
    }
    return normalized
}

private data class AnnotationFrame(val node: SExpr, val combine: Boolean, val bound: Set<String>)

private fun freeAnnotationSymbols(root: SExpr): Set<String> {
    val symbols = HashSet<String>()
    val work = ArrayDeque<Pair<SExpr, Set<String>>>()
    work += root to emptySet()
    while (work.isNotEmpty()) {
        val (node, bound) = work.removeLast()
        when (node) {
            is SExpr.Atom -> smtSessionSymbol(node.text).takeUnless { it in bound }?.let(symbols::add)
            is SExpr.SList -> {
                if ((node.items.firstOrNull() as? SExpr.Atom)?.text == "let" && node.items.size == 3) {
                    val bindings = (node.items[1] as? SExpr.SList)?.items.orEmpty().mapNotNull { it as? SExpr.SList }
                    val names = bindings.mapNotNull { (it.items.firstOrNull() as? SExpr.Atom)?.text?.let(::smtSessionSymbol) }.toSet()
                    bindings.forEach { binding -> binding.items.getOrNull(1)?.let { work += it to bound } }
                    work += node.items[2] to (bound + names)
                } else node.items.forEach { work += it to bound }
            }
        }
    }
    return symbols
}
