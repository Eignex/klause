package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.GridArithmetic
import com.eignex.klause.util.Cancellation

internal const val GRID_ARITHMETIC_ENCODING_LIMIT = 4096L

internal fun GridArithmetic.encodingSize(): Long = inputs.groupBy { it.variable }.values.fold(1L) { size, roles ->
    size * roles.minOf { it.buckets }
}

internal fun gridArithmeticTuples(relation: GridArithmetic, cancellation: Cancellation): List<LongArray>? {
    require(relation.encodingSize() <= GRID_ARITHMETIC_ENCODING_LIMIT)
    val inputs = relation.inputs.groupBy { it.variable }.mapValues { (_, roles) -> roles.minOf { it.buckets } }
        .toList()
    val assignment = HashMap<Int, Long>()
    val tuples = ArrayList<LongArray>()
    fun visit(at: Int): Boolean {
        if (cancellation()) return false
        if (at < inputs.size) {
            val (variable, buckets) = inputs[at]
            for (value in 0 until buckets) {
                assignment[variable] = value.toLong()
                if (!visit(at + 1)) return false
            }
            return true
        }
        cancellation.charge(1)
        val image = relation.image(assignment::getValue) ?: return true
        val output = relation.output.variable
        if (output in assignment && assignment[output] != image) return true
        tuples += LongArray(relation.intVars.size) { index ->
            val variable = relation.intVars[index]
            if (variable == output) image else assignment.getValue(variable)
        }
        return true
    }
    return if (visit(0) && !cancellation()) tuples else null
}
