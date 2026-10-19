package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.util.IntIntMap

internal class LinearCoeffIndex(private val coeffs: LongArray, vars: IntArray) {
    private val coefficient = coeffs.firstOrNull() ?: 0L
    private val minimum = vars.minOrNull() ?: 0
    private val members: LongArray?
    private val index: IntIntMap?

    init {
        val span = (vars.maxOrNull() ?: minimum).toLong() - minimum + 1L
        // Bound word storage by the row's entry count, independently of the model's coordinate range.
        members = if (vars.isNotEmpty() && coeffs.all { it == coefficient } &&
            span <= vars.size.toLong() * Long.SIZE_BITS
        ) {
            LongArray(((span + Long.SIZE_BITS - 1L) / Long.SIZE_BITS).toInt()).also { words ->
                for (variable in vars) {
                    val offset = variable.toLong() - minimum
                    val word = (offset ushr 6).toInt()
                    words[word] = words[word] or (1L shl (offset.toInt() and 63))
                }
            }
        } else {
            null
        }
        index = if (members == null) IntIntMap.build(vars, IntArray(vars.size) { it }, absent = -1) else null
    }

    fun coeffOf(intVar: Int): Long {
        val words = members
        if (words != null) {
            val offset = intVar.toLong() - minimum
            val word = offset ushr 6
            if (offset < 0L || word >= words.size) return 0L
            return if (words[word.toInt()] and (1L shl (offset.toInt() and 63)) != 0L) coefficient else 0L
        }
        val position = requireNotNull(index)[intVar]
        return if (position < 0) 0L else coeffs[position]
    }
}
