package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED

internal class PolynomialBooleanPropagation(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    private val arithmetic: PolynomialArithmetic,
    private val skipConfirmedParents: Boolean = false,
) {
    private val values = IntArray(nodes.size) { UNASSIGNED }
    private val unassignedChildren = IntArray(nodes.size)
    private val unassignedChildXor = IntArray(nodes.size)
    private val falseChildren = IntArray(nodes.size)
    private val parents = List(nodes.size) {
        arithmetic.step()
        ArrayList<Int>()
    }
    private val leaves = ArrayList<Int>()
    private val trail = ArrayList<Int>()
    private val pending = ArrayDeque<Int>()
    private val queued = BooleanArray(nodes.size)
    private val choices = ArrayList<Choice>()

    val trailSize: Int get() = trail.size

    fun value(index: Int): Int = values[index]

    fun decide(): Boolean {
        val leaf = unassignedLeaf() ?: return false
        arithmetic.step()
        choices.add(Choice(leaf, trail.size))
        check(assign(leaf, FALSE))
        return true
    }

    fun initialize(): Boolean {
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            when (node) {
                is FormulaNode.Not -> addParent(node.child, index)
                is FormulaNode.Junction -> {
                    unassignedChildren[index] = node.children.size
                    for (child in node.children) {
                        addParent(child, index)
                        arithmetic.step()
                        unassignedChildXor[index] = unassignedChildXor[index] xor child
                    }
                }
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

    fun assign(index: Int, value: Int): Boolean {
        arithmetic.step()
        if (values[index] != UNASSIGNED) return values[index] == value
        values[index] = value
        trail.add(index)
        enqueue(index)
        for (parent in parents[index]) {
            updateChild(parent, index, value, assigned = true)
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

    private fun updateChild(parent: Int, child: Int, value: Int, assigned: Boolean) {
        arithmetic.step()
        if (nodes[parent] !is FormulaNode.Junction) return
        val direction = if (assigned) 1 else -1
        unassignedChildren[parent] -= direction
        unassignedChildXor[parent] = unassignedChildXor[parent] xor child
        if (value == FALSE) falseChildren[parent] += direction
    }

    fun propagate(): Boolean {
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
        arithmetic.step()
        val decisive = if (node.conjunction) FALSE else TRUE
        val identity = TRUE - decisive
        val unknowns = unassignedChildren[index]
        val decisiveCount = if (node.conjunction) falseChildren[index]
        else node.children.size - unknowns - falseChildren[index]
        val decided = decisiveCount > 0
        if (decided && !assign(index, decisive)) return false
        if (!decided && unknowns == 0 && !assign(index, identity)) return false
        if (values[index] == identity) {
            for (child in node.children) if (!assign(child, identity)) return false
        } else if (values[index] == decisive && !decided && unknowns == 1) {
            if (!assign(unassignedChildXor[index], decisive)) return false
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

    fun backtrack(): Boolean {
        while (choices.isNotEmpty()) {
            arithmetic.step()
            val choice = choices.last()
            while (pending.isNotEmpty()) {
                arithmetic.step()
                queued[pending.removeLast()] = false
            }
            while (trail.size > choice.mark) {
                arithmetic.step()
                val index = trail.removeAt(trail.lastIndex)
                val value = values[index]
                values[index] = UNASSIGNED
                for (parent in parents[index]) updateChild(parent, index, value, assigned = false)
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
