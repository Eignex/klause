package com.eignex.klause.util

import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.subExact
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toDouble

internal class ExactIntegerSum {
    private var narrow = 0L
    private var wide: BigInt? = null

    fun reset() {
        narrow = 0L
        wide = null
    }

    fun addDelta(coefficient: Long, before: Long, after: Long) {
        if (coefficient == 0L) return
        val exact = wide
        if (exact != null) {
            wide = exact + bigIntOf(coefficient) * (bigIntOf(after) - bigIntOf(before))
            return
        }
        try {
            narrow = addExact(narrow, mulExact(coefficient, subExact(after, before)))
        } catch (_: CheckedLongOverflowException) {
            wide = bigIntOf(narrow) + bigIntOf(coefficient) * (bigIntOf(after) - bigIntOf(before))
        }
    }

    fun toDouble(): Double = wide?.toDouble() ?: narrow.toDouble()
}
