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

/**
 * The argsort of `0 until n` under a custom [compare] (negative ⇒ first sorts before second), as a
 * stable, boxing-free bottom-up merge sort over a primitive index array. A primitive replacement for
 * `(0 until n).sortedWith(comparator)`, which boxes every index and allocates an intermediate
 * `List<Int>` plus a `Comparator` — wasteful when a multi-key order is rebuilt on a hot path and the
 * key can't collapse to a single `Int` (which [argsortByIntKey] handles). [compare] is `inline`d, so
 * no `Comparator` object is allocated for it. Stability (equal elements keep input order) makes the
 * result deterministic and matches `sortedWith`.
 */
internal inline fun argsortBy(n: Int, compare: (Int, Int) -> Int): IntArray {
    var src = IntArray(n) { it }
    if (n < 2) return src
    var dst = IntArray(n)
    var width = 1
    while (width < n) {
        var lo = 0
        while (lo < n) {
            val mid = minOf(lo + width, n)
            val hi = minOf(lo + 2 * width, n)
            var i = lo
            var j = mid
            var k = lo
            while (i < mid && j < hi) dst[k++] = if (compare(src[i], src[j]) <= 0) src[i++] else src[j++]
            while (i < mid) dst[k++] = src[i++]
            while (j < hi) dst[k++] = src[j++]
            lo += 2 * width
        }
        val tmp = src
        src = dst
        dst = tmp
        width *= 2
    }
    return src
}
