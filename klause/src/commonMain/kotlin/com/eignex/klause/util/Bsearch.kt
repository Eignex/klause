package com.eignex.klause.util

/**
 * Binary search over a sorted [IntArray], with the same contract as the JVM stdlib's
 * `List<T>.binarySearch`: returns the index of [element] if present, otherwise
 * `-(insertionPoint) - 1` (a negative value whose bitwise inverse is the index where the
 * element would be inserted to keep the array sorted).
 *
 * The Kotlin **common** stdlib has no `binarySearch` for primitive arrays (it exists only on
 * `List` and, for the JVM target, on primitive arrays via the JVM stdlib). Code in
 * `commonMain` that called `IntArray.binarySearch` therefore compiled on the JVM target but
 * not on Kotlin/Native or Kotlin/JS. This helper provides a target-independent equivalent;
 * the distinct name avoids clashing with the JVM stdlib member on that target.
 *
 * [this] must be sorted ascending over `[fromIndex, toIndex)`; behaviour is otherwise
 * undefined (same precondition as the stdlib).
 */
internal fun IntArray.bsearch(element: Int, fromIndex: Int = 0, toIndex: Int = size): Int {
    var low = fromIndex
    var high = toIndex - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midVal = this[mid]
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid
        }
    }
    return -(low + 1)
}
