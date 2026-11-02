package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit

internal class TransientIntegerRow private constructor(
    override val variables: IntArray,
    override val coefficients: LongArray,
    override val bound: Long,
    private val work: IntegerSourceWork,
) : IntegerLearningInequality {
    override val guard: Int get() = Lit.NONE

    override fun visit(state: PropagationState) = work.visit()

    override fun coefficient(variable: Int): Long {
        val index = indexOf(variable)
        return if (index < 0) 0L else coefficients[index]
    }

    override fun indexOf(variable: Int): Int {
        for (index in variables.indices) {
            work.visit()
            if (variables[index] == variable) return index
        }
        return -1
    }

    companion object {
        fun normalized(terms: Map<Int, Long>, bound: Long, work: IntegerSourceWork): TransientIntegerRow? {
            if (terms.size !in (INTEGER_LEARNING_TERMS + 1)..INTEGER_SOURCE_TERMS) return null
            var divisor = 0L
            for (coefficient in terms.values) {
                work.visit()
                if (coefficient == 0L || coefficient == Long.MIN_VALUE) return null
                divisor = IntegerConflictRow.gcd(divisor, IntegerConflictRow.magnitude(coefficient))
            }
            if (divisor == 0L) return null
            val variables = terms.keys.sorted().toIntArray()
            val coefficients = LongArray(variables.size) {
                work.visit()
                terms.getValue(variables[it]) / divisor
            }
            return TransientIntegerRow(variables, coefficients, IntegerConflictRow.floorDivide(bound, divisor), work)
        }
    }
}
