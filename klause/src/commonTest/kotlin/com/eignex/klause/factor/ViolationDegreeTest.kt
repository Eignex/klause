package com.eignex.klause.factor

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `residual above the soft cap matches the log loop reference`() {
        val cap = 16
        for (raw in longArrayOf(17, 18, 31, 32, 33, 100, 1000, 1 shl 20, Long.MAX_VALUE)) {
            val expected = cap + bitLengthByLoop(raw - cap)
            assertEquals(expected, compressViolation(raw, cap), "raw=$raw")
        }
    }

    @Test
    fun `zero soft cap gives a pure log scale`() {
        for (raw in longArrayOf(1, 2, 3, 4, 7, 8, 1024)) {
            assertEquals(bitLengthByLoop(raw), compressViolation(raw, 0), "raw=$raw")
        }
    }
}
