package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED
import com.eignex.klause.util.magnitudeBitLength

internal fun refutePolynomialBooleans(
    nodes: List<FormulaNode>,
    root: Int,
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
    maximumAtoms: Int,
    maximumTerms: Int,
    maximumBits: Int,
): Boolean {
    if (atoms.size > maximumAtoms || nodes.size > maximumTerms) return false
    var terms = 0
    for (atom in atoms) {
        arithmetic.step()
        if (atom.polynomial.terms.size > maximumTerms - terms) return false
        terms += atom.polynomial.terms.size
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            if (term.coefficient.num.magnitudeBitLength() > maximumBits ||
                term.coefficient.den.magnitudeBitLength() > maximumBits) return false
        }
    }
    var references = 0
    for (node in nodes) {
        arithmetic.step()
        val count = when (node) {
            is FormulaNode.Not -> 1
            is FormulaNode.Junction -> node.children.size
            else -> 0
        }
        if (count > maximumTerms - references) return false
        references += count
    }
    // Free numeric predicates weaken the source; only exhaustion can refute it.
    return PolynomialBooleanRefutation(nodes, root, arithmetic).refutes()
}

internal fun polynomialBooleanFacts(
    nodes: List<FormulaNode>,
    root: Int,
    arithmetic: PolynomialArithmetic,
): Map<Int, Boolean>? = PolynomialBooleanRefutation(nodes, root, arithmetic, skipConfirmedParents = true).booleanFacts()

private class PolynomialBooleanRefutation(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    private val arithmetic: PolynomialArithmetic,
    private val skipConfirmedParents: Boolean = false,
) {
    private val values = IntArray(nodes.size) { UNASSIGNED }
    private val parents = List(nodes.size) {
        arithmetic.step()
        ArrayList<Int>()
    }
    private val leaves = ArrayList<Int>()
    private val trail = ArrayList<Int>()
    private val pending = ArrayDeque<Int>()
    private val queued = BooleanArray(nodes.size)
    private val choices = ArrayList<Choice>()

    fun refutes(): Boolean {
        if (!initialize()) return true
        while (true) {
            arithmetic.step()
            if (propagate()) {
                val leaf = unassignedLeaf() ?: return false
                arithmetic.step()
                choices.add(Choice(leaf, trail.size))
                check(assign(leaf, FALSE))
            } else if (!backtrack()) {
                arithmetic.step()
                return true
            }
        }
    }

    fun booleanFacts(): Map<Int, Boolean>? {
        if (!initialize() || !propagate()) return null
        val result = HashMap<Int, Boolean>()
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node is FormulaNode.BooleanVariable && values[index] != UNASSIGNED) {
                result[node.variable] = values[index] == TRUE
            }
        }
        return result
    }

    private fun initialize(): Boolean {
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            when (node) {
                is FormulaNode.Not -> addParent(node.child, index)
                is FormulaNode.Junction -> for (child in node.children) addParent(child, index)
                is FormulaNode.BooleanVariable -> leaves.add(index)
                else -> Unit
            }
            enqueue(index)
        }
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node is FormulaNode.Atom) leaves.add(index)
            if (node is FormulaNode.Constant && !assign(index, if (node.value) TRUE else FALSE)) return false
        }
        return assign(root, TRUE)
    }

    private fun addParent(child: Int, parent: Int) {
        arithmetic.step()
        parents[child].add(parent)
    }

    private fun enqueue(index: Int) {
        arithmetic.step()
        if (!queued[index]) {
            queued[index] = true
            pending.addLast(index)
        }
    }

    private fun assign(index: Int, value: Int): Boolean {
        arithmetic.step()
        if (values[index] != UNASSIGNED) return values[index] == value
        values[index] = value
        trail.add(index)
        enqueue(index)
        for (parent in parents[index]) {
            if (skipConfirmedParents) {
                arithmetic.step()
                val parentValue = values[parent]
                if (parentValue != UNASSIGNED) {
                    val confirmed = when (nodes[parent]) {
                        is FormulaNode.Junction -> parentValue == value
                        is FormulaNode.Not -> parentValue != value
                        else -> false
                    }
                    if (confirmed) continue
                }
            }
            enqueue(parent)
        }
        return true
    }

    private fun propagate(): Boolean {
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val index = pending.removeFirst()
            queued[index] = false
            when (val node = nodes[index]) {
                is FormulaNode.Not -> {
                    val child = values[node.child]
                    if (child != UNASSIGNED && !assign(index, TRUE - child)) return false
                    val value = values[index]
                    if (value != UNASSIGNED && !assign(node.child, TRUE - value)) return false
                }
                is FormulaNode.Junction -> if (!propagate(index, node)) return false
                else -> Unit
            }
        }
        return true
    }

    private fun propagate(index: Int, node: FormulaNode.Junction): Boolean {
        val decisive = if (node.conjunction) FALSE else TRUE
        val identity = TRUE - decisive
        var unknowns = 0
        var lastUnknown = -1
        var decided = false
        for (child in node.children) {
            arithmetic.step()
            when (values[child]) {
                decisive -> decided = true
                UNASSIGNED -> {
                    unknowns++
                    lastUnknown = child
                }
            }
        }
        if (decided && !assign(index, decisive)) return false
        if (!decided && unknowns == 0 && !assign(index, identity)) return false
        if (values[index] == identity) {
            for (child in node.children) if (!assign(child, identity)) return false
        } else if (values[index] == decisive && !decided && unknowns == 1) {
            if (!assign(lastUnknown, decisive)) return false
        }
        return true
    }

    private fun unassignedLeaf(): Int? {
        for (index in leaves) {
            arithmetic.step()
            if (values[index] == UNASSIGNED) return index
        }
        return null
    }

    private fun backtrack(): Boolean {
        while (choices.isNotEmpty()) {
            arithmetic.step()
            val choice = choices.last()
            while (pending.isNotEmpty()) {
                arithmetic.step()
                queued[pending.removeLast()] = false
            }
            while (trail.size > choice.mark) {
                arithmetic.step()
                values[trail.removeAt(trail.lastIndex)] = UNASSIGNED
            }
            if (!choice.triedTrue) {
                choice.triedTrue = true
                return assign(choice.node, TRUE)
            }
            choices.removeAt(choices.lastIndex)
        }
        return false
    }

    private class Choice(val node: Int, val mark: Int, var triedTrue: Boolean = false)
}
