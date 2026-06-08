package com.eignex.klause.solver.lp

/*
 * Exact Long arithmetic for the fraction-free (Bareiss-style) simplex tableau. The tableau shares
 * one integer scale (the basis determinant), so every entry stays a Long and each pivot divides
 * exactly by the previous one. Long is deliberate, not a BigInteger fallback: 64-bit integers keep
 * the inner loops fast. Overflow is detected, never wrapped.
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

/**
 * The Bareiss combine-and-divide step `(p*x - y*z) / d`, computed so the intermediate
 * products are themselves overflow-checked. The division is exact by the fraction-free
 * invariant — a nonzero remainder means the invariant was violated (a bug), so we assert it.
 */
internal fun bareissStep(p: Long, x: Long, y: Long, z: Long, d: Long): Long {
    val numerator = subExact(mulExact(p, x), mulExact(y, z))
    check(numerator % d == 0L) {
        "fraction-free invariant broken: ($p*$x - $y*$z) not divisible by $d"
    }
    return numerator / d
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
