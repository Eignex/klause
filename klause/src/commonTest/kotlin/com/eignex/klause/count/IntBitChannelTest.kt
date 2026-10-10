package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The native int↔bits channel that replaces bit-blasting for XOR-hash counting over integers. */
class IntBitChannelTest {

    private fun ints(domains: List<IntDomain>) = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = domains.toTypedArray(),
        factors = arrayOf<Factor>(),
    ).bake()

    @Test
    fun `channelling keeps the base columns invented sides and closes the ones it appends`() {
        val open = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 7)),
            factors = arrayOf<Factor>(),
            openIntHi = booleanArrayOf(true),
        ).bake()

        val channelled = IntBitChannel.channel(open, intArrayOf(0)).problem

        assertTrue(
            channelled.intBounds.isOpenUpper(0),
            "an invented endpoint must not read back as declared",
        )
        assertEquals(4, channelled.numIntVars, "one base column plus its three channel bits")
        for (v in 1 until channelled.numIntVars) {
            assertTrue(channelled.intBounds.hasUpper(v), "channel column $v is a declared 0/1 bit")
            assertTrue(channelled.intBounds.hasLower(v), "channel column $v is a declared 0/1 bit")
        }
    }

    @Test
    fun `a column spanning more than the Long range is rejected rather than encoded as a constant`() {
        val clamp = 1L shl 62
        val base = ints(listOf(IntDomain(-clamp, clamp)))

        assertFailsWith<IllegalArgumentException> { IntBitChannel.channel(base, intArrayOf(0)) }
    }

    @Test
    fun `non power of two domain prunes out of range bit patterns`() {
        // 6 values over width-3 bits: patterns 6 and 7 land outside [0,5] and must be infeasible.
        val base = ints(listOf(IntDomain(0, 5)))
        val ch = IntBitChannel.channel(base, intArrayOf(0))
        assertEquals(3, ch.bitsPerVar[0].size)

        val values = BacktrackSolver(ch.problem).enumerate(BacktrackParams()).map { it.ints[0] }.toList()
        assertEquals((0L..5L).toList().sorted(), values.sorted())
        assertEquals(6, values.size)
    }

    @Test
    fun `no requested vars returns the base problem unchanged`() {
        val base = ints(listOf(IntDomain(0, 3)))
        val ch = IntBitChannel.channel(base, intArrayOf())
        assertEquals(base, ch.problem)
        assertTrue(ch.bitsPerVar.isEmpty())
    }
}
