package com.eignex.klause.util

// Shared index/hash arithmetic for the primitive-specialised open-addressing collections
// (IntHashSet, LongHashSet, MutableIntIntMap, MutableLongIntMap). Only the parts that touch no
// typed storage live here — the probe loops, growth, and backward-shift bodies stay in each class
// so that Int/Long keys and Int values never round-trip through boxed Any.

/** Next power-of-two capacity that keeps the load factor ≤ 0.5 for [initialCapacity] entries. */
internal fun openAddressingCapacity(initialCapacity: Int): Int {
    var cap = 8
    while (cap < initialCapacity * 2) cap *= 2
    return cap
}

/** Fibonacci-multiplicative hash; good distribution for sequential int keys. */
internal fun mixIntKey(x: Int): Int = (x * -0x61c88647).ushr(0)

/**
 * Backward-shift deletion predicate (Knuth 6.4 algorithm R): with a hole at `i` and the next
 * occupied slot at `j`, an entry whose home slot lies cyclically within `(i, j]` is still
 * reachable past the hole and must stay; otherwise it would be stranded and must shift down.
 */
internal fun mustStayDuringShift(home: Int, i: Int, j: Int): Boolean =
    if (i <= j) home > i && home <= j else home > i || home <= j
