package com.eignex.klause.factor.arithmetic

internal fun integerPowerOrNull(base: Long, exponent: Int): Long? {
    var remaining = exponent
    var value = 1L
    var factor = base
    while (remaining > 0) {
        if (remaining and 1 != 0) value = multiplyOrNull(value, factor) ?: return null
        remaining = remaining ushr 1
        if (remaining > 0) factor = multiplyOrNull(factor, factor) ?: return null
    }
    return value
}

private fun multiplyOrNull(a: Long, b: Long): Long? {
    if (a == 0L || b == 0L) return 0L
    val value = a * b
    return value.takeIf { value / a == b && !(a == -1L && b == Long.MIN_VALUE) }
}

internal fun compareIntegerPower(base: Long, exponent: Int, value: Long): Int =
    integerPowerOrNull(base, exponent)?.compareTo(value) ?: if (base < 0 && exponent and 1 != 0) -1 else 1

internal fun saturatedIntegerPower(base: Long, exponent: Int): Long = integerPowerOrNull(base, exponent)
    ?: if (base < 0 && exponent and 1 != 0) Long.MIN_VALUE else Long.MAX_VALUE

internal fun powerPreimage(lo: Long, hi: Long, exponent: Int, lower: Long, upper: Long): Pair<Long, Long>? {
    if (lo > hi) return null
    val increasing = exponent and 1 != 0 || lo >= 0
    fun afterLower(value: Long): Boolean = if (increasing) {
        compareIntegerPower(value, exponent, lower) >= 0
    } else compareIntegerPower(value, exponent, upper) <= 0
    fun beforeUpper(value: Long): Boolean = if (increasing) {
        compareIntegerPower(value, exponent, upper) <= 0
    } else compareIntegerPower(value, exponent, lower) >= 0
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
