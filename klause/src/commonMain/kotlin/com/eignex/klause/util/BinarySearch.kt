package com.eignex.klause.util

import com.eignex.klause.ir.IntDomain

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
 * Hybrid: a single-comparison lower-bound loop narrows the window, then a linear scan
 * finishes once it drops below [LINEAR_SCAN_THRESHOLD]; small ranges skip the binary loop
 * entirely. The narrowing phase does ONE comparison per step (`this[mid] < element`) and
 * never branches on equality — the equality test happens once, in the linear tail. This is
 * the hottest primitive in the solver (`IntDomain.contains` and every sorted-array probe ride
 * on it), and the common caller path is membership that returns "not found", which an
 * early-match exit never helps, so dropping the second comparison per step is a net win.
 *
 * Assumes [this] is sorted ascending in `[fromIndex, toIndex)`.
 */
internal fun IntArray.binarySearchInt(element: Int, fromIndex: Int = 0, toIndex: Int = size): Int {
    var lo = fromIndex
    var len = toIndex - fromIndex
    // Lower-bound narrowing: keep `[lo, lo+len)` as the window that may hold the insertion
    // point. Invariant: everything below `lo` is `< element`, everything at/above `lo+len` is
    // `>= element`. One comparison per iteration, branch-predictor friendly.
    while (len > LINEAR_SCAN_THRESHOLD) {
        val half = len shr 1
        val mid = lo + half
        if (this[mid] < element) {
            lo = mid + 1
            len -= half + 1
        } else {
            len = half
        }
    }
    // Linear finish: advance past the `< element` prefix of the window. The stopping index is
    // the insertion point — it can be `lo + len` itself (the window's upper boundary, which the
    // invariant guarantees is `>= element`), so the membership test happens after the scan, not
    // inside it.
    val end = lo + len
    while (lo < end && this[lo] < element) lo++
    return if (lo < toIndex && this[lo] == element) lo else -(lo + 1)
}

/** [binarySearchInt] for a sorted [LongArray] — same return contract. Used by the wide-value
 *  [com.eignex.klause.ir.intdomain.SurvivorsDomain] whose present values may exceed 32-bit range. */
internal fun LongArray.binarySearchLong(element: Long, fromIndex: Int = 0, toIndex: Int = size): Int {
    var lo = fromIndex
    var len = toIndex - fromIndex
    while (len > LINEAR_SCAN_THRESHOLD) {
        val half = len shr 1
        val mid = lo + half
        if (this[mid] < element) {
            lo = mid + 1
            len -= half + 1
        } else {
            len = half
        }
    }
    val end = lo + len
    while (lo < end && this[lo] < element) lo++
    return if (lo < toIndex && this[lo] == element) lo else -(lo + 1)
}

/** Window width at or below which the linear scan takes over from the binary narrowing. A sweep
 *  over array sizes 16..16384 (the hole-array range) put the optimum at 8-24: thresholds >=64
 *  regress badly (the linear tail dominates), small arrays favour ~8, and the large arrays that
 *  drive sparse-domain membership favour ~16-24. 16 is the best single compromise. */
private const val LINEAR_SCAN_THRESHOLD = 16
