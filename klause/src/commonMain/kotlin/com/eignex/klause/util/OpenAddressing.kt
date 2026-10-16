package com.eignex.klause.util

// Shared index/hash arithmetic for the primitive-specialised open-addressing collections. Only the
// parts that touch no typed storage live here; probe loops, growth and backward-shift bodies stay in
// each class so primitive keys and values never round-trip through boxed Any.

/** Next power-of-two capacity that keeps the load factor ≤ 0.5 for [initialCapacity] entries. */
internal fun openAddressingCapacity(initialCapacity: Int): Int {
    var cap = 8
    while (cap < initialCapacity * 2) cap *= 2
    return cap
}

/**
 * Fibonacci multiplicative hash (Knuth, TAOCP Vol. 3 §6.4,
 * https://www-cs-faculty.stanford.edu/~knuth/taocp.html); spreads sequential int keys well.
 */
internal fun mixIntKey(x: Int): Int = (x * -0x61c88647).ushr(0)

/** 64-bit Fibonacci multiplicative mix with a high-to-low xor fold, truncated to an Int slot index;
 *  spreads packed long keys well. Every long-keyed table uses it. */
internal fun mixLongKey(x: Long): Int {
    var h = x * -0x61c8864680b583ebL // 2^64 / golden ratio, odd
    h = h xor (h ushr 32)
    return h.toInt()
}

/**
 * Backward-shift deletion predicate (Knuth, TAOCP Vol. 3 §6.4 Algorithm R,
 * https://www-cs-faculty.stanford.edu/~knuth/taocp.html): with a hole at `i` and the next
 * occupied slot at `j`, an entry whose home slot lies cyclically within `(i, j]` is still
 * reachable past the hole and must stay; otherwise it would be stranded and must shift down.
 */
internal fun mustStayDuringShift(home: Int, i: Int, j: Int): Boolean =
    if (i <= j) home > i && home <= j else home > i || home <= j
