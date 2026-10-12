package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation

internal class SmtLibScope(private val cancellation: Cancellation) {
    private val globals = HashSet<String>()
    private class Locals(val names: Set<String>, val parent: Locals?) {
        fun contains(name: String): Boolean {
            var scope: Locals? = this
            while (scope != null) {
                if (name in scope.names) return true
                scope = scope.parent
            }
            return false
        }
    }
    private data class Frame(val expression: SExpr, val locals: Locals?)

    fun command(expression: SExpr): Boolean {
        val command = expression as? SExpr.SList ?: return false
        return when (command.polynomialHead()) {
            "declare-const", "declare-fun" -> declare(command.polynomialName(1))
            "define-fun" -> {
                val name = command.polynomialName(1)
                if (!declare(name)) return false
                val parameters = command.items.getOrNull(2) as? SExpr.SList ?: return false
                val names = parameters.items.map {
                    (it as? SExpr.SList)?.polynomialName(0) ?: return false
                }
                if (names.size != names.toSet().size) return false
                references(command.items.getOrNull(4) ?: return false, Locals(names.toSet(), null), name)
            }
            "assert", "minimize", "maximize" -> references(command.items.getOrNull(1) ?: return false, null, null)
            else -> true
        }
    }

    private fun declare(name: String): Boolean = name !in setOf("true", "false") && globals.add(name)

    private fun references(root: SExpr, locals: Locals?, hidden: String?): Boolean {
        fun known(text: String, scope: Locals?): Boolean {
            val name = polynomialSymbol(text)
            if (scope?.contains(name) == true) return true
            if (name == hidden) return false
            return name in globals || text in setOf("true", "false", "pi") ||
                text.toLongOrNull() != null || text.all { it in '0'..'9' || it == '.' }
        }
        val work = ArrayDeque<Frame>()
        work.addLast(Frame(root, locals))
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint()
            val (node, scope) = work.removeLast()
            when (node) {
                is SExpr.Atom -> if (!known(node.text, scope)) return false
                is SExpr.SList -> {
                    val head = node.polynomialHead()
                    if (head == "let") {
                        if (node.items.size != 3) return false
                        val bindings = node.items[1] as? SExpr.SList ?: return false
                        val names = HashSet<String>()
                        for (binding in bindings.items) {
                            val pair = binding as? SExpr.SList ?: return false
                            if (pair.items.size != 2) return false
                            val name = pair.polynomialName(0)
                            if (!names.add(name) || name in setOf("true", "false") || name.toLongOrNull() != null) {
                                return false
                            }
                            work.addLast(Frame(pair.items[1], scope))
                        }
                        work.addLast(Frame(node.items[2], Locals(names, scope)))
                    } else {
                        if (head !in OPERATORS && (!known(head, scope) || scope?.contains(head) == true)) return false
                        for (argument in node.items.drop(1)) work.addLast(Frame(argument, scope))
                    }
                }
            }
        }
        return true
    }

    private companion object {
        val OPERATORS = setOf(
            "+", "-", "*", "/", "^", "and", "or", "not", "xor", "=>", "ite",
            "=", "distinct", "<", "<=", ">", ">=", "to_real", "to_int", "abs", "div", "mod", "is_int",
            "exp", "log", "sin", "cos", "pi",
        )
    }
}
