package com.eignex.klause.util

/**
 * Binary search for [element] in a sorted [IntArray]. Multiplatform replacement for
 * `kotlin.collections.IntArray.binarySearch`, which is JVM-only (it delegates to
 * `java.util.Arrays.binarySearch`).
 *
 * Same return contract as the JDK / Kotlin stdlib version: the index of [element] if
 * found, otherwise `-(insertion point) - 1` where `insertion point` is the index at
 * which [element] would be inserted to keep the array sorted. Caller code that uses
 * `if (idx >= 0)` / `val insert = -(idx + 1)` works identically.
 *
 * Hybrid: binary search narrows the range, then a linear scan finishes once it drops
 * below [LINEAR_SCAN_THRESHOLD]; small ranges skip the binary loop entirely.
 *
 * Assumes [this] is sorted ascending in `[fromIndex, toIndex)`.
 */
internal fun IntArray.binarySearchInt(element: Int, fromIndex: Int = 0, toIndex: Int = size): Int {
    var lo = fromIndex
    var hi = toIndex - 1
    while (hi - lo >= LINEAR_SCAN_THRESHOLD) {
        val mid = (lo + hi) ushr 1
        val midVal = this[mid]
        when {
            midVal < element -> lo = mid + 1
            midVal > element -> hi = mid - 1
            else -> return mid
        }
    }
    while (lo <= hi) {
        val v = this[lo]
        if (v >= element) return if (v == element) lo else -(lo + 1)
        lo++
    }
    return -(lo + 1)
}

/** Range width below which a linear scan beats the binary loop's data-dependent branches. */
private const val LINEAR_SCAN_THRESHOLD = 8
