package com.eignex.klause.util

/**
 * The argsort of an integer key vector: indices `0 until n` ordered ascending by [key], ties
 * broken by index ascending. A primitive replacement for `(0 until n).sortedWith(compareBy(...))`,
 * which boxes every index, allocates the selector lambdas plus a `Comparator`, and builds an
 * intermediate `List<Int>` — wasteful on hot paths that recompute an order every call.
 *
 * Each `(key, index)` pair is packed into one `Long` (key in the high word, index in the low) and
 * the primitive [LongArray.sort] orders by key with index as the natural tie-break. The pack is
 * exact and order-preserving even for negative keys: shifting the signed key left by 32 keeps the
 * signed ordering, and the index (a non-negative `0 until n`) only ever occupies the zeroed low
 * word, so it never perturbs the key's bits. [key] is `inline`d, so no `Function` object is
 * allocated for it either.
 */
internal inline fun argsortByIntKey(n: Int, key: (Int) -> Int): IntArray {
    val packed = LongArray(n) { (key(it).toLong() shl 32) or (it.toLong() and 0xFFFFFFFFL) }
    packed.sort()
    return IntArray(n) { (packed[it] and 0xFFFFFFFFL).toInt() }
}
