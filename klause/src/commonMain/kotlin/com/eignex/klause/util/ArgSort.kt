package com.eignex.klause.util

/**
 * Indices ordered by integer [key], ties by index. Packs each signed key in the high
 * word of a `Long` and its nonnegative index in the low word, then uses primitive
 * [LongArray.sort] without boxing. Packing preserves negative-key ordering.
 */
internal inline fun argsortByIntKey(n: Int, key: (Int) -> Int): IntArray {
    val packed = LongArray(n) { (key(it).toLong() shl 32) or (it.toLong() and 0xFFFFFFFFL) }
    packed.sort()
    return IntArray(n) { (packed[it] and 0xFFFFFFFFL).toInt() }
}

/**
 * Stable, boxing-free argsort using bottom-up merge sort over primitive indices.
 * [compare] returns negative when its first index sorts before the second. Equal
 * indices retain input order, giving deterministic ties.
 */
internal inline fun argsortBy(n: Int, compare: (Int, Int) -> Int): IntArray {
    val order = IntArray(n)
    if (n < 2) return order
    return argsortBy(n, order, IntArray(n), compare)
}

internal inline fun argsortBy(n: Int, order: IntArray, scratch: IntArray, compare: (Int, Int) -> Int): IntArray {
    require(n >= 0 && n <= order.size && n <= scratch.size)
    require(order !== scratch)
    for (i in 0 until n) order[i] = i
    var src = order
    if (n < 2) return src
    var dst = scratch
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
