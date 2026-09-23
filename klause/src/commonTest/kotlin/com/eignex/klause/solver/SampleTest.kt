package com.eignex.klause.solver

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SampleTest {
    @Test
    fun `certified reals survive discrete reconstruction and snapshot their source list`() {
        val exact = BigFraction.ofLong(2) * BigFraction.ofLong(3).reciprocal()
        val values = mutableListOf(exact)
        val sample = Sample(booleanArrayOf(false), longArrayOf(2), doubleArrayOf(exact.toDouble()), values)
        values[0] = BigFraction.ZERO

        val reconstructed = sample.copy(bools = booleanArrayOf(true), ints = longArrayOf(3))

        assertEquals(exact, reconstructed.exactReals?.single())
        assertEquals(exact, sample.exactReals?.single())
    }

    @Test
    fun `replacing approximate reals clears old exact authority`() {
        val exact = BigFraction.ofLong(1)
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(1.0), listOf(exact))

        val replaced = sample.copy(reals = doubleArrayOf(0.5))

        assertNull(replaced.exactReals)
    }

    @Test
    fun `exact authority distinguishes equal double projections`() {
        val oneThird = BigFraction.ofLong(3).reciprocal()
        val rounded = requireNotNull(BigFraction.ofDouble(oneThird.toDouble()))
        val exact = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(oneThird.toDouble()), listOf(oneThird))
        val approximate = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(oneThird.toDouble()), listOf(rounded))

        assertNotEquals(exact, approximate)
    }

    @Test
    fun `exact coordinates must align with real coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            Sample(BooleanArray(0), LongArray(0), doubleArrayOf(1.0), emptyList())
        }
    }
}
