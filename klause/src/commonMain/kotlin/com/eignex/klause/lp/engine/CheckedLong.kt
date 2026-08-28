package com.eignex.klause.lp.engine

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
