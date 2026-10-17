package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED

internal fun polynomialProjectionAtoms(
    nodes: List<FormulaNode>,
    root: Int,
    atomCount: Int,
    arithmetic: PolynomialArithmetic,
): BooleanArray? {
    val facts = polynomialBooleanFacts(nodes, root, arithmetic) ?: return null
    if (facts.isEmpty()) return null
    val values = IntArray(nodes.size) { UNASSIGNED }
    for ((index, node) in nodes.withIndex()) {
        arithmetic.step()
        // Imposed compound truths are constraints; only entailed Boolean leaves are substituted.
        values[index] = when (node) {
            is FormulaNode.Constant -> if (node.value) TRUE else FALSE
            is FormulaNode.BooleanVariable -> facts[node.variable]?.let { if (it) TRUE else FALSE } ?: UNASSIGNED
            is FormulaNode.Atom -> UNASSIGNED
            is FormulaNode.Not -> values[node.child].let { if (it == UNASSIGNED) UNASSIGNED else TRUE - it }
            is FormulaNode.Junction -> booleanProjectionValue(node, values, arithmetic)
        }
    }
    val atoms = BooleanArray(atomCount)
    val visited = BooleanArray(nodes.size)
    val pending = ArrayDeque<Int>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        arithmetic.step()
        val index = pending.removeLast()
        if (visited[index] || values[index] != UNASSIGNED) continue
        visited[index] = true
        when (val node = nodes[index]) {
            is FormulaNode.Atom -> atoms[node.atom] = true
            is FormulaNode.Not -> pending.addLast(node.child)
            is FormulaNode.Junction -> for (child in node.children) {
                arithmetic.step()
                pending.addLast(child)
            }
            else -> Unit
        }
    }
    return atoms
}

private fun booleanProjectionValue(
    node: FormulaNode.Junction,
    values: IntArray,
    arithmetic: PolynomialArithmetic,
): Int {
    val decisive = if (node.conjunction) FALSE else TRUE
    var unknown = false
    for (child in node.children) {
        arithmetic.step()
        when (values[child]) {
            decisive -> return decisive
            UNASSIGNED -> unknown = true
        }
    }
    return if (unknown) UNASSIGNED else TRUE - decisive
}
