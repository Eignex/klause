package com.eignex.klause.solver

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SampleTest {
    @Test
    fun `algebraic coordinates survive captured buffers and unchanged copies`() {
        val exact = AlgebraicArithmetic().rational(BigFraction.ofLong(2))
        val values = mutableListOf(exact)
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(2.0), algebraicReals = values)
        sample.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(Any()), EvidenceKind.Witness)
        values.clear()

        val copied = sample.copy()

        assertEquals(listOf(exact), copied.algebraicReals)
        assertNotNull(copied.witnessCertificate)
        assertEquals(sample, copied)
        assertEquals(sample.hashCode(), copied.hashCode())
    }

    @Test
    fun `replacing approximate coordinates discards algebraic authority`() {
        val exact = AlgebraicArithmetic().rational(BigFraction.ofLong(2))
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(2.0), algebraicReals = listOf(exact))
        sample.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(Any()), EvidenceKind.Witness)

        val changed = sample.copy(reals = doubleArrayOf(3.0))

        assertNull(changed.algebraicReals)
        assertNull(changed.witnessCertificate)
    }

    @Test
    fun `finite samples capture constructor buffers`() {
        val bools = booleanArrayOf(true)
        val ints = longArrayOf(Long.MIN_VALUE)
        val reals = doubleArrayOf(-0.0)
        val sample = Sample(bools, ints, reals)

        bools[0] = false
        ints[0] = 0
        reals[0] = 1.0

        assertEquals(Sample(booleanArrayOf(true), longArrayOf(Long.MIN_VALUE), doubleArrayOf(-0.0)), sample)
    }

    @Test
    fun `exported finite arrays cannot change a sample or its copy`() {
        val sample = Sample(booleanArrayOf(true), longArrayOf(2), doubleArrayOf(0.5))
        val copied = sample.copy()
        val originalHash = sample.hashCode()

        sample.bools[0] = false
        sample.ints[0] = 3
        sample.reals[0] = 1.5

        assertEquals(Sample(booleanArrayOf(true), longArrayOf(2), doubleArrayOf(0.5)), sample)
        assertEquals(sample, copied)
        assertEquals(originalHash, sample.hashCode())
    }


    @Test
    fun `a copied wide witness keeps exact integers without a finite stand-in`() {
        val wide = parseBigInt("9223372036854775808")
        val values = mutableListOf(wide)
        val bools = booleanArrayOf(true)
        val original = Sample(bools, LongArray(0), exactInts = values)
        original.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(Any()), EvidenceKind.Witness)
        values[0] = bigIntOf(0)
        bools[0] = false
        original.bools[0] = false

        val copied = original.copy()

        assertEquals(wide, copied.exactInts?.single())
        assertEquals(1, copied.numIntVars)
        assertTrue(copied.bools.single())
        assertNotNull(copied.witnessCertificate)
        assertFailsWith<IllegalStateException> { copied.ints }
        assertEquals(original, copied)
        assertEquals(original.hashCode(), copied.hashCode())
    }

    @Test
    fun `changing coordinates discards theory acceptance`() {
        val sample = Sample(booleanArrayOf(false), longArrayOf(2), exactInts = listOf(bigIntOf(2)))
        sample.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(Any()), EvidenceKind.Witness)

        val changed = sample.copy(ints = longArrayOf(3))
        val changedBoolean = sample.copy(bools = booleanArrayOf(true))

        assertNull(changed.exactInts)
        assertNull(changed.witnessCertificate)
        assertNull(changedBoolean.witnessCertificate)
        assertEquals(bigIntOf(2), sample.exactInts?.single())
    }

    @Test
    fun `exact integer coordinates must agree with the finite view`() {
        assertFailsWith<IllegalArgumentException> {
            Sample(BooleanArray(0), longArrayOf(1), exactInts = listOf(bigIntOf(2)))
        }
        assertFailsWith<IllegalArgumentException> {
            Sample(BooleanArray(0), longArrayOf(0), exactInts = listOf(parseBigInt("9223372036854775808")))
        }
    }

    @Test
    fun `certified reals survive discrete reconstruction and snapshot their source list`() {
        val exact = BigFraction.ofLong(2) * BigFraction.ofLong(3).reciprocal()
        val values = mutableListOf(exact)
        val approximations = doubleArrayOf(exact.toDouble())
        val sample = Sample(booleanArrayOf(false), longArrayOf(2), approximations, values)
        values[0] = BigFraction.ZERO
        approximations[0] = 0.0
        sample.reals[0] = 0.0

        val reconstructed = sample.copy(bools = booleanArrayOf(true), ints = longArrayOf(3))

        assertEquals(exact, reconstructed.exactReals?.single())
        assertEquals(exact, sample.exactReals?.single())
        assertEquals(exact.toDouble(), sample.reals.single())
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
        for ((approximate, exact) in listOf(0.0 to BigFraction.ofLong(2), -0.0 to BigFraction.ZERO)) {
            assertFailsWith<IllegalArgumentException> {
                Sample(BooleanArray(0), LongArray(0), doubleArrayOf(approximate), listOf(exact))
            }
        }
    }

    @Test
    fun `exact authority survives a nonfinite double projection`() {
        val exact = BigFraction.of(BIG_ONE shl 1024, BIG_ONE)

        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(Double.POSITIVE_INFINITY), listOf(exact))

        assertEquals(exact, sample.exactReals?.single())
        assertEquals(Double.POSITIVE_INFINITY, sample.approximateRealValue(0))
    }
}
