package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun canonicalSmtCore(root: SExpr, cancellation: ExecutionControl): SExpr {
    val work = ArrayDeque<Pair<SExpr, Boolean>>()
    val values = ArrayDeque<SExpr>()
    work.addLast(root to false)
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (node, combine) = work.removeLast()
        when (node) {
            is SExpr.Atom -> {
                val name = polynomialSymbol(node.text)
                values.addLast(if (name in setOf("Bool", "Int", "Real", "true", "false")) SExpr.Atom(name) else node)
            }
            is SExpr.SList -> if (combine) {
                values.addLast(SExpr.SList(List(node.items.size) { values.removeLast() }.reversed()))
            } else {
                work.addLast(node to true)
                for (item in node.items.asReversed()) work.addLast(item to false)
            }
        }
    }
    return values.single()
}
