package com.eignex.klause.factor.arithmetic

import com.eignex.util.numbers.mulExactOrNull

internal fun integerPowerOrNull(base: Long, exponent: Int): Long? {
    var remaining = exponent
    var value = 1L
    var factor = base
    while (remaining > 0) {
        if (remaining and 1 != 0) value = mulExactOrNull(value, factor) ?: return null
        remaining = remaining ushr 1
        if (remaining > 0) factor = mulExactOrNull(factor, factor) ?: return null
    }
    return value
}

internal fun compareIntegerPower(base: Long, exponent: Int, value: Long): Int =
    integerPowerOrNull(base, exponent)?.compareTo(value) ?: if (base < 0 && exponent and 1 != 0) -1 else 1

internal fun saturatedIntegerPower(base: Long, exponent: Int): Long = integerPowerOrNull(base, exponent)
    ?: if (base < 0 && exponent and 1 != 0) Long.MIN_VALUE else Long.MAX_VALUE

internal fun powerPreimage(
    lo: Long, hi: Long, exponent: Int, lower: Long, upper: Long, step: () -> Unit = {},
): Pair<Long, Long>? {
    if (lo > hi) return null
    val increasing = exponent and 1 != 0 || lo >= 0
    fun afterLower(value: Long): Boolean {
        step()
        return if (increasing) compareIntegerPower(value, exponent, lower) >= 0
        else compareIntegerPower(value, exponent, upper) <= 0
    }
    fun beforeUpper(value: Long): Boolean {
        step()
        return if (increasing) compareIntegerPower(value, exponent, upper) <= 0
        else compareIntegerPower(value, exponent, lower) >= 0
    }
    if (!afterLower(hi) || !beforeUpper(lo)) return null
    var left = lo
    var right = hi
    while (left < right) {
        val middle = (left and right) + ((left xor right) shr 1)
        if (afterLower(middle)) right = middle else left = middle + 1
    }
    val first = left
    if (!beforeUpper(first)) return null
    left = first
    right = hi
    while (left < right) {
        val middle = (left and right) + ((left xor right) shr 1) + 1L
        if (beforeUpper(middle)) left = middle else right = middle - 1
    }
    return first to left
}
