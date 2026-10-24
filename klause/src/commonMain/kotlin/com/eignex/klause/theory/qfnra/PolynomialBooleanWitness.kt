package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED

internal class PolynomialBooleanWitness(
    private val nodes: List<FormulaNode>,
    private val values: IntArray,
    private val parents: List<List<Int>>,
    private val arithmetic: PolynomialArithmetic,
) {
    private val supported = IntArray(nodes.size) { arithmetic.step(); UNASSIGNED }
    private val trueChildren = IntArray(nodes.size)
    private val falseChildren = IntArray(nodes.size)
    private val pending = ArrayDeque<Int>()
    private val queued = BooleanArray(nodes.size)

    fun changed(index: Int) {
        arithmetic.step()
        if (!queued[index]) {
            queued[index] = true
            pending.addLast(index)
        }
    }

    fun witnessed(index: Int): Boolean {
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val current = pending.removeFirst()
            queued[current] = false
            val previous = supported[current]
            val next = support(current)
            if (previous == next) continue
            supported[current] = next
            for (parent in parents[current]) {
                arithmetic.step()
                if (previous == TRUE) trueChildren[parent]--
                if (previous == FALSE) falseChildren[parent]--
                if (next == TRUE) trueChildren[parent]++
                if (next == FALSE) falseChildren[parent]++
                changed(parent)
            }
        }
        return supported[index] != UNASSIGNED
    }

    private fun support(index: Int): Int {
        val value = values[index]
        if (value == UNASSIGNED) return UNASSIGNED
        val confirmed = when (val node = nodes[index]) {
            is FormulaNode.Constant -> value == if (node.value) TRUE else FALSE
            is FormulaNode.BooleanVariable, is FormulaNode.Atom -> true
            is FormulaNode.Not -> supported[node.child] == TRUE - value
            is FormulaNode.Junction -> {
                val count = if (value == TRUE) trueChildren[index] else falseChildren[index]
                val decisive = if (node.conjunction) FALSE else TRUE
                if (value == decisive) count > 0 else count == node.children.size
            }
        }
        return if (confirmed) value else UNASSIGNED
    }
}
