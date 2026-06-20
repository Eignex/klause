package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.factor.compressViolation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `compressViolation` splits at [softCap]: residuals at or below it pass through verbatim, larger
 * ones get a `softCap + ⌊log2(raw − softCap + 1)⌋` tail. These cases pin both regimes and the
 * boundary, and cross-check the tail against an independent shift-loop reference so the
 * `countLeadingZeroBits` bit-length stays exactly the floor-log it stands in for.
 */
class ViolationDegreeTest {

    /** Independent ⌊log2(extra)⌋ + 1 reference for `extra ≥ 1`. */
    private fun bitLengthByLoop(extra: Long): Int {
        var e = extra
        var bits = 0
        while (e > 0L) {
            e = e shr 1
            bits++
        }
        return bits
    }

    @Test
    fun nonPositiveResidualIsZero() {
        assertEquals(0, compressViolation(0L, 16))
        assertEquals(0, compressViolation(-5L, 16))
    }

    @Test
    fun atOrBelowCapPassesThrough() {
        assertEquals(1, compressViolation(1L, 16))
        assertEquals(16, compressViolation(16L, 16))
    }

    @Test
    fun aboveCapMatchesLogLoopReference() {
        val cap = 16
        for (raw in longArrayOf(17, 18, 31, 32, 33, 100, 1000, 1 shl 20, Long.MAX_VALUE)) {
            val expected = cap + bitLengthByLoop(raw - cap)
            assertEquals(expected, compressViolation(raw, cap), "raw=$raw")
        }
    }

    @Test
    fun zeroCapIsPureLogScale() {
        for (raw in longArrayOf(1, 2, 3, 4, 7, 8, 1024)) {
            assertEquals(bitLengthByLoop(raw), compressViolation(raw, 0), "raw=$raw")
        }
    }
}
