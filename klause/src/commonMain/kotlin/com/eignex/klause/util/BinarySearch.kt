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
 * Assumes [this] is sorted ascending in `[fromIndex, toIndex)`.
 */
internal fun IntArray.binarySearchInt(element: Int, fromIndex: Int = 0, toIndex: Int = size): Int {
    var lo = fromIndex
    var hi = toIndex - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val midVal = this[mid]
        when {
            midVal < element -> lo = mid + 1
            midVal > element -> hi = mid - 1
            else -> return mid
        }
    }
    return -(lo + 1)
}
