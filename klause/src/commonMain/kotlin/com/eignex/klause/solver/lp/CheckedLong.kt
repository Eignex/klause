package com.eignex.klause.solver.lp

/*
 * Overflow-checked exact Long arithmetic for LP relaxation construction (coefficient/right-hand-side
 * assembly) and the integer bound computations. Long is deliberate, not a BigInteger fallback: 64-bit
 * integers keep the inner loops fast, and overflow is detected and raised as [LpOverflowException]
 * rather than silently wrapped (a wrapped coefficient would make the LP bound unsound). Exact
 * larger-than-64-bit accumulation, where needed, uses the 128-bit [Int128] rather than a bignum.
 */

/** `a` + `b`, or throw [LpOverflowException] on 64-bit overflow. */
internal fun addExact(a: Long, b: Long): Long {
    val r = a + b
    // Overflow iff a and b share a sign that differs from the result's sign.
    if ((a xor r) and (b xor r) < 0L) throw LpOverflowException("addExact overflow: $a + $b")
    return r
}

/** `a` - `b`, or throw [LpOverflowException] on 64-bit overflow. */
internal fun subExact(a: Long, b: Long): Long {
    val r = a - b
    if ((a xor b) and (a xor r) < 0L) throw LpOverflowException("subExact overflow: $a - $b")
    return r
}

/** `a` * `b`, or throw [LpOverflowException] on 64-bit overflow. */
internal fun mulExact(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val r = a * b
    // r / a == b is the standard inverse check; the second clause catches the single
    // wrap-around case (Long.MIN_VALUE * -1) that the division check misses.
    if (r / a != b || (a == -1L && b == Long.MIN_VALUE)) {
        throw LpOverflowException("mulExact overflow: $a * $b")
    }
    return r
}

/** Greatest common divisor of |`a`| and |`b`|; `gcd(0, 0) == 0`. Used for row scaling/normalization. */
internal fun gcdLong(a: Long, b: Long): Long {
    var x = if (a < 0L) -a else a
    var y = if (b < 0L) -b else b
    while (y != 0L) {
        val t = x % y
        x = y
        y = t
    }
    return x
}
