package com.eignex.klause.factor

// Long arithmetic on variable values that saturates instead of wrapping. Local search reads a violation as a
// distance and treats zero as satisfied; a wrapped distance can come out zero or negative on a violated factor.
// Values stay inside ±2^62, so a result saturated to a Long limit is never equal to one, and a saturated distance
// is zero exactly when the true one is.

/** `a + b`, saturated to the `Long` range. */
internal fun saturatedAdd(a: Long, b: Long): Long {
    val sum = a + b
    if ((a xor sum) and (b xor sum) >= 0L) return sum
    return if (a < 0L) Long.MIN_VALUE else Long.MAX_VALUE
}

/** `a − b`, saturated to the `Long` range. */
internal fun saturatedSub(a: Long, b: Long): Long {
    val difference = a - b
    if ((a xor b) and (a xor difference) >= 0L) return difference
    return if (a < 0L) Long.MIN_VALUE else Long.MAX_VALUE
}

/** `a · b`, saturated to the `Long` range. */
internal fun saturatedMul(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val product = a * b
    val overflows = product / a != b || (a == -1L && b == Long.MIN_VALUE)
    if (!overflows) return product
    return if ((a < 0L) != (b < 0L)) Long.MIN_VALUE else Long.MAX_VALUE
}

/** `|a − b|`, saturated to `Long.MAX_VALUE`. */
internal fun distance(a: Long, b: Long): Long = if (a >= b) saturatedSub(a, b) else saturatedSub(b, a)
