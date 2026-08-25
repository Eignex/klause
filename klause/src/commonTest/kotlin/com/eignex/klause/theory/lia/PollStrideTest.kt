package com.eignex.klause.theory.lia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How often the General LIA sweeps look at the budget. The work they amortise is `BigInteger` arithmetic
 * over the witness box's endpoints, so the stride has to shrink as that box widens — a fixed one either
 * overshoots the budget on a wide box or costs more than it guards on a narrow one.
 */
class PollStrideTest {

    @Test
    fun `a narrow box polls at the coarsest stride`() {
        assertEquals(256, pollStrideFor(1))
        assertEquals(256, pollStrideFor(64))
    }

    @Test
    fun `a box too wide to walk polls on every factor`() {
        // The bofill-scheduling instance behind #1578's residue carries a 413454-bit box.
        assertEquals(1, pollStrideFor(413_454))
    }

    @Test
    fun `the stride never grows as the box widens`() {
        var previous = Int.MAX_VALUE
        for (bits in listOf(1, 64, 256, 1_024, 8_192, 65_536, 413_454)) {
            val stride = pollStrideFor(bits)
            assertTrue(stride <= previous, "stride grew at $bits bits: $previous then $stride")
            assertTrue(stride >= 1, "stride must stay positive, got $stride at $bits bits")
            previous = stride
        }
    }
}
