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
        val work = ArrayDeque<Any>()
        val values = ArrayDeque<SExpr>()
        val bound = AnnotationBindings(cancellation)
        work += AnnotationFrame(command, false)
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val next = work.removeLast()
            if (next is SExpr.Atom) {
                values += next
                continue
            }
            if (next is AnnotationExit) {
                bound.leave(next.bindings)
                continue
            }
            val frame = next as AnnotationFrame
            if (!frame.combine && frame.bindings.isNotEmpty()) {
                bound.enter(frame.bindings)
                work += AnnotationExit(frame.bindings)
            }
            val node = frame.node
            when (node) {
                is SExpr.Atom -> values += node
                is SExpr.SList -> if (frame.combine) {
                    var changed: MutableList<SExpr>? = null
                    for (index in node.items.indices.reversed()) {
                        val child = values.removeLast()
                        if (child !== node.items[index]) {
                            if (changed == null) changed = node.items.toMutableList()
                            changed[index] = child
                        }
                    }
                    val combined = if (changed == null) node else SExpr.SList(changed)
                    val items = combined.items
                    if ((items.firstOrNull() as? SExpr.Atom)?.text == "!") {
                        if (items.size < 3) smtUnsupported("annotated term requires at least one attribute")
                        val body = items[1]
                        var index = 2
                        while (index < items.size) {
                            val keyword = (items[index++] as? SExpr.Atom)?.text
                                ?.takeIf { it.startsWith(':') } ?: smtUnsupported("expected an annotation keyword")
                            val value = items.getOrNull(index)?.takeUnless {
                                it is SExpr.Atom && it.text.startsWith(':')
                            }
                            if (value != null) index++
                            if (keyword == ":named") {
                                val label = value as? SExpr.Atom ?: smtUnsupported(":named requires a symbol")
                                if (!bound.isEmpty && freeAnnotationSymbols(body, scope, cancellation)
                                        .any { it in bound }) {
                                    smtUnsupported(":named requires a closed term")
                                }
                                val sort = scope.defineClosedConstant(label, body)
                                    ?: smtUnsupported(":named requires a fresh symbol and a closed well-sorted term")
                                normalized += SExpr.SList(listOf(SExpr.Atom("define-fun"), label,
                                    SExpr.SList(emptyList()),
                                    SExprReader(sort.toSmtLib()).readAll().single(), body))
                            }
                        }
                        values += body
                    } else values += combined
                } else {
                    frame.combine = true
                    work += frame
                    val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
                    val localNames = frame.pattern?.let {
                        smtPatternVariables(it, scope.datatypes.operatorNames)
                    } ?: when (head) {
                        "let" -> annotationBindingNames(node.items.getOrNull(1))
                        "define-fun", "define-fun-rec" -> annotationBindingNames(node.items.getOrNull(2))
                        else -> emptySet()
                    }
                    for (index in node.items.indices.reversed()) {
                        val bodyIndex = when {
                            frame.pattern != null -> 1
                            head == "let" -> 2
                            else -> 4
                        }
                        val child = node.items[index]
                        val pattern = if (frame.matchCases) (child as? SExpr.SList)?.items?.firstOrNull() else null
                        val bindings = if (index == bodyIndex) localNames else emptySet()
                        work += if (child is SExpr.Atom && bindings.isEmpty()) child else {
                            AnnotationFrame(child, false, bindings,
                                matchCases = head == "match" && index == 2, pattern = pattern)
                        }
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

private class AnnotationFrame(
    val node: SExpr, var combine: Boolean, val bindings: Set<String> = emptySet(),
    val matchCases: Boolean = false, val pattern: SExpr? = null,
)

private class AnnotationExit(val bindings: Set<String>)

private class AnnotationBindings(private val cancellation: ExecutionControl) {
    private val counts = HashMap<String, Int>()
    val isEmpty: Boolean get() = counts.isEmpty()

    fun enter(names: Set<String>) {
        for (name in names) {
            cancellation.polynomialSourceCheckpoint(1)
            counts[name] = (counts[name] ?: 0) + 1
        }
    }

    fun leave(names: Set<String>) {
        for (name in names) {
            cancellation.polynomialSourceCheckpoint(1)
            val count = counts.getValue(name)
            if (count == 1) counts.remove(name) else counts[name] = count - 1
        }
    }

    operator fun contains(name: String): Boolean = name in counts
}

private fun annotationBindingNames(expression: SExpr?): Set<String> {
    val bindings = (expression as? SExpr.SList)?.items.orEmpty()
    if (bindings.size == 1) {
        val binding = bindings[0] as? SExpr.SList ?: return emptySet()
        val name = (binding.items.firstOrNull() as? SExpr.Atom)?.text ?: return emptySet()
        return setOf(smtSessionSymbol(name))
    }
    return bindings.mapNotNull {
        val name = ((it as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text
        name?.let(::smtSessionSymbol)
    }.toSet()
}

private fun freeAnnotationSymbols(
    root: SExpr,
    scope: SmtLibScope,
    cancellation: ExecutionControl,
): Set<String> {
    val symbols = HashSet<String>()
    val constructors = scope.datatypes.operatorNames
    val bound = AnnotationBindings(cancellation)
    val work = ArrayDeque<Any>()
    work += AnnotationFrame(root, false)
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val next = work.removeLast()
        if (next is AnnotationExit) {
            bound.leave(next.bindings)
            continue
        }
        val node = if (next is SExpr.Atom) next else {
            val frame = next as AnnotationFrame
            if (frame.bindings.isNotEmpty()) {
                bound.enter(frame.bindings)
                work += AnnotationExit(frame.bindings)
            }
            frame.node
        }
        when (node) {
            is SExpr.Atom -> if (scope.literalSort(node.text) == null) {
                smtSessionSymbol(node.text).takeUnless { it in bound }?.let(symbols::add)
            }
            is SExpr.SList -> {
                val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
                if (head == "let" && node.items.size == 3) {
                    val bindings = (node.items[1] as? SExpr.SList)?.items.orEmpty().mapNotNull { it as? SExpr.SList }
                    val names = bindings.mapNotNull {
                        (it.items.firstOrNull() as? SExpr.Atom)?.text?.let(::smtSessionSymbol)
                    }.toSet()
                    work += AnnotationFrame(node.items[2], false, names)
                    for (binding in bindings.asReversed()) {
                        binding.items.getOrNull(1)?.let { work += annotationVisit(it) }
                    }
                } else if (head == "match" && node.items.size == 3) {
                    for (case in (node.items[2] as? SExpr.SList)?.items.orEmpty().asReversed()) {
                        val pair = case as? SExpr.SList ?: continue
                        if (pair.items.size == 2) {
                            work += AnnotationFrame(pair.items[1], false,
                                smtPatternVariables(pair.items[0], constructors))
                        }
                    }
                    work += annotationVisit(node.items[1])
                } else if (head == "!") {
                    node.items.getOrNull(1)?.let { work += annotationVisit(it) }
                } else if (head == "as" || head == "_") {
                    continue
                } else node.items.asReversed().forEach { work += annotationVisit(it) }
            }
        }
    }
    return symbols
}

private fun annotationVisit(node: SExpr): Any = if (node is SExpr.Atom) node else AnnotationFrame(node, false)

internal fun smtPatternVariables(pattern: SExpr, constructors: Set<String>): Set<String> = when (pattern) {
    is SExpr.Atom -> setOf(smtSessionSymbol(pattern.text)).filterNot { it in constructors }.toSet()
    is SExpr.SList -> pattern.items.drop(1).mapNotNull {
        (it as? SExpr.Atom)?.text?.let(::smtSessionSymbol)
    }.toSet()
}
