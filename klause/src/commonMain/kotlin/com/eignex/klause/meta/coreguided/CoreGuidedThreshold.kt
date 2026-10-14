package com.eignex.klause.meta.coreguided

import com.eignex.klause.ir.Lit

internal class CoreGuidedThreshold(val literals: IntArray) {
    private var layer = 1
    private var position = 0
    private var previous: IntArray? = null
    private var current: IntArray? = null

    fun advance(
        minimum: Int,
        allocate: () -> Int,
        clause: (IntArray) -> Unit,
        charge: (Long) -> Unit,
    ): Int? {
        require(minimum in 2..literals.size)
        if (position == literals.size) {
            if (layer == minimum) return requireNotNull(current).last()
            check(layer < minimum)
            previous = current
            current = null
            position = 0
            layer++
        }
        val output = current ?: run {
            charge(literals.size.toLong())
            IntArray(literals.size).also { current = it }
        }
        val end = minOf(literals.size, position + 256)
        while (position < end) {
            val i = position++
            charge(1L)
            if (i < layer - 1) continue
            if (layer == 1 && i == 0) {
                output[i] = literals[i]
                continue
            }
            val value = allocate()
            output[i] = value
            if (layer == 1) {
                disjunction(output[i - 1], literals[i], value, clause)
            } else if (i == layer - 1) {
                conjunction(requireNotNull(previous)[i - 1], literals[i], value, clause)
            } else {
                extend(output[i - 1], requireNotNull(previous)[i - 1], literals[i], value, clause)
            }
        }
        return if (position == literals.size && layer == minimum) output.last() else null
    }

    private fun disjunction(left: Int, right: Int, output: Int, clause: (IntArray) -> Unit) {
        clause(intArrayOf(Lit.negate(left), output))
        clause(intArrayOf(Lit.negate(right), output))
        clause(intArrayOf(Lit.negate(output), left, right))
    }

    private fun conjunction(left: Int, right: Int, output: Int, clause: (IntArray) -> Unit) {
        clause(intArrayOf(Lit.negate(output), left))
        clause(intArrayOf(Lit.negate(output), right))
        clause(intArrayOf(Lit.negate(left), Lit.negate(right), output))
    }

    private fun extend(same: Int, lower: Int, literal: Int, output: Int, clause: (IntArray) -> Unit) {
        // p(i, k) is p(i-1, k) or (p(i-1, k-1) and literal(i)); both directions retain exact costs.
        clause(intArrayOf(Lit.negate(same), output))
        clause(intArrayOf(Lit.negate(lower), Lit.negate(literal), output))
        clause(intArrayOf(Lit.negate(output), same, lower))
        clause(intArrayOf(Lit.negate(output), same, literal))
    }
}
