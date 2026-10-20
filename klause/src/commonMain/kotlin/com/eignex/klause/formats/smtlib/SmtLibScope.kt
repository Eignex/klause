package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation

internal class SmtLibScope(private val cancellation: Cancellation) {
    private class Signature(val parameters: List<Sort>, val result: Sort)
    private sealed interface Frame {
        class Evaluate(val expression: SExpr) : Frame
        class Combine(val head: String, val count: Int, val signature: Signature?) : Frame
        class Let(val names: List<String>, val body: SExpr) : Frame
        class Leave(val names: List<String>) : Frame
    }
    private val globals = HashMap<String, Signature>()
    private var querySeen = false
    private var exited = false

    fun command(expression: SExpr): Boolean {
        val command = expression as? SExpr.SList ?: return false
        val head = command.polynomialHead()
        if (exited || (querySeen && head !in setOf("get-model", "get-objectives", "exit", "set-info", "set-option"))) {
            return false
        }
        return when (head) {
            "declare-const", "declare-fun" -> {
                command.polynomialSize(if (head == "declare-const") 3 else 4)
                if (head == "declare-fun" &&
                    (command.items[2] as? SExpr.SList)?.items?.isEmpty() != true
                ) return false
                val name = command.polynomialName(1)
                val result = polynomialSort(command.polynomialAtom(if (head == "declare-const") 2 else 3))
                if (name in globals || name in setOf("true", "false")) false else {
                    globals[name] = Signature(emptyList(), result)
                    true
                }
            }
            "define-fun" -> {
                command.polynomialSize(5)
                val name = command.polynomialName(1)
                if (name in globals || name in setOf("true", "false")) return false
                val parameters = command.items.getOrNull(2) as? SExpr.SList ?: return false
                val names = ArrayList<String>()
                val uniqueNames = HashSet<String>()
                val sorts = ArrayList<Sort>()
                for (parameter in parameters.items) {
                    cancellation.polynomialSourceCheckpoint(1)
                    val pair = parameter as? SExpr.SList ?: return false
                    if (pair.items.size != 2) return false
                    val parameterName = pair.polynomialName(0)
                    if (!uniqueNames.add(parameterName) || parameterName in setOf("true", "false")) return false
                    names += parameterName
                    sorts += polynomialSort(pair.polynomialAtom(1))
                }
                val result = polynomialSort(command.polynomialAtom(3))
                val body = sort(command.items.getOrNull(4) ?: return false,
                    names.zip(sorts).toMap()) ?: return false
                if (!accepts(result, body)) return false
                globals[name] = Signature(sorts, result)
                true
            }
            "assert", "minimize", "maximize" -> {
                command.polynomialSize(2)
                val result = sort(command.items[1]) ?: return false
                if (head == "assert") result == Sort.BOOL else numeric(result)
            }
            "set-logic" -> {
                command.polynomialSize(2)
                command.polynomialName(1)
                true
            }
            "check-sat" -> {
                command.polynomialSize(1)
                querySeen = true
                true
            }
            "exit" -> {
                command.polynomialSize(1)
                exited = true
                true
            }
            "get-model", "get-objectives" -> {
                command.polynomialSize(1)
                true
            }
            "set-info", "set-option" -> {
                command.polynomialSize(3)
                true
            }
            else -> false
        }
    }

    private fun sort(root: SExpr, parameters: Map<String, Sort> = emptyMap()): Sort? {
        val bindings = HashMap<String, ArrayDeque<Sort>>()
        for ((name, type) in parameters) {
            cancellation.polynomialSourceCheckpoint(1)
            bindings.getOrPut(name) { ArrayDeque() }.addLast(type)
        }
        val work = ArrayDeque<Frame>()
        val values = ArrayDeque<Sort>()
        work.addLast(Frame.Evaluate(root))
        fun pop(count: Int): List<Sort> = List(count) { values.removeLast() }.reversed()
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            when (val frame = work.removeLast()) {
                is Frame.Evaluate -> when (val node = frame.expression) {
                    is SExpr.Atom -> {
                        val name = polynomialSymbol(node.text)
                        val result = literal(node.text) ?: bindings[name]?.lastOrNull() ?: globals[name]
                            ?.takeIf { it.parameters.isEmpty() }?.result ?: return null
                        values.addLast(result)
                    }
                    is SExpr.SList -> {
                        val head = node.polynomialHead()
                        if (head == "let") {
                            if (node.items.size != 3) return null
                            val bindings = node.items[1] as? SExpr.SList ?: return null
                            val names = ArrayList<String>()
                            val uniqueNames = HashSet<String>()
                            val arguments = ArrayList<SExpr>()
                            for (binding in bindings.items) {
                                cancellation.polynomialSourceCheckpoint(1)
                                val pair = binding as? SExpr.SList ?: return null
                                if (pair.items.size != 2) return null
                                val name = pair.polynomialName(0)
                                if (!uniqueNames.add(name) || name in setOf("true", "false")) return null
                                names += name
                                arguments += pair.items[1]
                            }
                            work.addLast(Frame.Let(names, node.items[2]))
                            for (argument in arguments.asReversed()) {
                                work.addLast(Frame.Evaluate(argument))
                            }
                        } else {
                            val name = polynomialSymbol(head)
                            if (name in bindings) return null
                            val signature = globals[name]
                            work.addLast(Frame.Combine(head, node.items.size - 1, signature))
                            for (argument in node.items.drop(1).asReversed()) {
                                work.addLast(Frame.Evaluate(argument))
                            }
                        }
                    }
                }
                is Frame.Let -> {
                    val sorts = pop(frame.names.size)
                    for ((index, name) in frame.names.withIndex()) {
                        cancellation.polynomialSourceCheckpoint(1)
                        bindings.getOrPut(name) { ArrayDeque() }.addLast(sorts[index])
                    }
                    work.addLast(Frame.Leave(frame.names))
                    work.addLast(Frame.Evaluate(frame.body))
                }
                is Frame.Leave -> {
                    for (name in frame.names) {
                        cancellation.polynomialSourceCheckpoint(1)
                        val stack = bindings.getValue(name)
                        stack.removeLast()
                        if (stack.isEmpty()) bindings.remove(name)
                    }
                }
                is Frame.Combine -> {
                    val args = pop(frame.count)
                    val signature = frame.signature
                    val result = if (signature == null) operator(frame.head, args) else {
                        if (args.size != signature.parameters.size || args.indices.any {
                                !accepts(signature.parameters[it], args[it])
                            }) return null
                        signature.result
                    }
                    values.addLast(result ?: return null)
                }
            }
        }
        return values.single()
    }

    private fun literal(text: String): Sort? {
        if (text in setOf("true", "false")) return Sort.BOOL
        if (text.startsWith("#")) smtUnsupported("bitvector literal '$text'")
        if (text.isNotEmpty() && text.all { it in '0'..'9' }) return Sort.INT
        val dot = text.indexOf('.')
        return if (dot > 0 && dot < text.lastIndex && text.indices.all { it == dot || text[it] in '0'..'9' }) {
            Sort.REAL
        } else null
    }

    private fun operator(head: String, args: List<Sort>): Sort? = when (head) {
        "+", "*" -> numericResult(args)
        "-" -> if (args.isEmpty()) null else numericResult(args)
        "/" -> if (args.size >= 2 && args.all(::numeric)) Sort.REAL else null
        "^" -> if (args.size == 2 && numeric(args[0]) && args[1] == Sort.INT) args[0] else null
        "and", "or" -> if (args.all { it == Sort.BOOL }) Sort.BOOL else null
        "xor", "=>" -> if (args.size >= 2 && args.all { it == Sort.BOOL }) Sort.BOOL else null
        "not" -> if (args == listOf(Sort.BOOL)) Sort.BOOL else null
        "=", "distinct" -> if (args.size >= 2 &&
            (args.all { it == Sort.BOOL } || args.all(::numeric))) Sort.BOOL else null
        "<", "<=", ">", ">=" -> if (args.size >= 2 && args.all(::numeric)) Sort.BOOL else null
        "ite" -> {
            if (args.size != 3) smtUnsupported("ite expects 3 arguments")
            if (args[0] != Sort.BOOL) null
            else if (args[1] == Sort.BOOL && args[2] == Sort.BOOL) Sort.BOOL else numericResult(args.drop(1))
        }
        "to_real" -> if (args == listOf(Sort.INT)) Sort.REAL else null
        "to_int" -> if (args.size == 1 && numeric(args[0])) Sort.INT else null
        "is_int" -> if (args.size == 1 && numeric(args[0])) Sort.BOOL else null
        "abs" -> if (args.size == 1 && numeric(args[0])) args[0] else null
        "div", "mod" -> if (args == listOf(Sort.INT, Sort.INT)) Sort.INT else null
        else -> null
    }

    private fun numeric(sort: Sort): Boolean = sort != Sort.BOOL
    private fun numericResult(args: List<Sort>): Sort? = if (!args.all(::numeric)) null
        else if (Sort.REAL in args) Sort.REAL else Sort.INT
    private fun accepts(expected: Sort, actual: Sort): Boolean = expected == actual ||
        (expected == Sort.REAL && actual == Sort.INT)
}

internal fun SExpr.factorSourceSymbols(cancellation: Cancellation): SExpr {
    val work = ArrayDeque<Pair<SExpr, Boolean>>()
    val values = ArrayDeque<SExpr>()
    work.addLast(this to false)
    val punctuation = "~!@$%^&*_+=<>.?/-"
    fun initial(character: Char): Boolean = character in 'a'..'z' || character in 'A'..'Z' || character in punctuation
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (node, combine) = work.removeLast()
        when (node) {
            is SExpr.Atom -> {
                val name = polynomialSymbol(node.text)
                val simple = name.isNotEmpty() && initial(name[0]) &&
                    name.all { initial(it) || it in '0'..'9' }
                values.addLast(if (simple && name != node.text) SExpr.Atom(name) else node)
            }
            is SExpr.SList -> if (combine) {
                val children = List(node.items.size) { values.removeLast() }.reversed()
                val unchanged = children.indices.all { children[it] === node.items[it] }
                values.addLast(if (unchanged) node else SExpr.SList(children))
            } else {
                work.addLast(node to true)
                for (child in node.items.asReversed()) work.addLast(child to false)
            }
        }
    }
    return values.single()
}
