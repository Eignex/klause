package com.eignex.klause.solver

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssignmentTest {
    @Test
    fun `copyInto should leave the target independent of the source`() {
        val source = Assignment(65, 1, 1)
        source.setBool(0, true)
        source.setBool(64, true)
        source.setInt(0, Long.MIN_VALUE)
        source.setReal(0, -0.0)
        val target = Assignment(65, 1, 1)

        source.copyInto(target)
        source.flipBool(0)
        source.flipBool(64)
        source.setInt(0, Long.MAX_VALUE)
        source.setReal(0, 1.5)

        assertEquals(
            Sample(BooleanArray(65) { it == 0 || it == 64 }, longArrayOf(Long.MIN_VALUE), doubleArrayOf(-0.0)),
            target.snapshot(),
        )
    }

    @Test
    fun `copyInto should not mutate an earlier snapshot of the target`() {
        val source = Assignment(65, 1, 1)
        source.setBool(64, true)
        source.setInt(0, 3)
        source.setReal(0, 0.5)
        val target = Assignment(65, 1, 1)
        source.copyInto(target)
        val published = target.snapshot()

        source.flipBool(64)
        source.setInt(0, 7)
        source.setReal(0, 1.5)
        source.copyInto(target)

        assertEquals(
            Sample(BooleanArray(65) { it == 64 }, longArrayOf(3), doubleArrayOf(0.5)),
            published,
        )
        assertEquals(source.snapshot(), target.snapshot())
    }

    @Test
    fun `copy should keep algebraic coordinates and certificate after source list changes`() {
        val exact = AlgebraicArithmetic().rational(BigFraction.ofLong(2))
        val values = mutableListOf(exact)
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(2.0), algebraicReals = values)
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )
        val runtime = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))
        val publication = CompletedEnginePublication.Witness(runtime, sample, null)
        sample.witnessCertificate = EvidenceCertificate.published(publication)
        values.clear()

        val copied = sample.copy()

        assertEquals(listOf(exact), copied.algebraicReals)
        assertNotNull(copied.witnessCertificate)
        assertEquals(sample, copied)
        assertEquals(sample.hashCode(), copied.hashCode())
    }

    @Test
    fun `copy with new reals should discard algebraic coordinates and certificate`() {
        val exact = AlgebraicArithmetic().rational(BigFraction.ofLong(2))
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(2.0), algebraicReals = listOf(exact))
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )
        val runtime = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))
        val publication = CompletedEnginePublication.Witness(runtime, sample, null)
        sample.witnessCertificate = EvidenceCertificate.published(publication)

        val changed = sample.copy(reals = doubleArrayOf(3.0))

        assertNull(changed.algebraicReals)
        assertNull(changed.witnessCertificate)
    }

    @Test
    fun `constructor should snapshot the given finite arrays`() {
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
    fun `exported finite arrays should not mutate the sample or its copy`() {
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
    fun `copy of a wide witness should keep exact integers without a finite view`() {
        val wide = parseBigInt("9223372036854775808")
        val values = mutableListOf(wide)
        val bools = booleanArrayOf(true)
        val original = Sample(bools, LongArray(0), exactInts = values)
        val source = Problem(
            1,
            IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), Bits(1), Bits(1)),
            emptyArray(),
        )
        val runtime = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))
        original.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(runtime, original, null),
        )
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
    fun `copy with changed coordinates should discard the witness certificate`() {
        val sample = Sample(booleanArrayOf(false), longArrayOf(2), exactInts = listOf(bigIntOf(2)))
        val source = Problem(1, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val runtime = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))
        val publication = CompletedEnginePublication.Witness(runtime, sample, null)
        sample.witnessCertificate = EvidenceCertificate.published(publication)

        val changed = sample.copy(ints = longArrayOf(3))
        val changedBoolean = sample.copy(bools = booleanArrayOf(true))

        assertNull(changed.exactInts)
        assertNull(changed.witnessCertificate)
        assertNull(changedBoolean.witnessCertificate)
        assertEquals(bigIntOf(2), sample.exactInts?.single())
    }

    @Test
    fun `constructor should reject exact integers disagreeing with the finite view`() {
        assertFailsWith<IllegalArgumentException> {
            Sample(BooleanArray(0), longArrayOf(1), exactInts = listOf(bigIntOf(2)))
        }
        assertFailsWith<IllegalArgumentException> {
            Sample(BooleanArray(0), longArrayOf(0), exactInts = listOf(parseBigInt("9223372036854775808")))
        }
    }

    @Test
    fun `copy with new discrete values should keep snapshotted exact reals`() {
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
    fun `copy with new reals should clear exact reals`() {
        val exact = BigFraction.ofLong(1)
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(1.0), listOf(exact))

        val replaced = sample.copy(reals = doubleArrayOf(0.5))

        assertNull(replaced.exactReals)
    }

    @Test
    fun `equality should distinguish exact reals with equal double projections`() {
        val oneThird = BigFraction.ofLong(3).reciprocal()
        val rounded = requireNotNull(BigFraction.ofDouble(oneThird.toDouble()))
        val exact = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(oneThird.toDouble()), listOf(oneThird))
        val approximate = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(oneThird.toDouble()), listOf(rounded))

        assertNotEquals(exact, approximate)
    }

    @Test
    fun `constructor should reject exact reals misaligned with approximate reals`() {
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
    fun `exact real should survive an infinite double projection`() {
        val exact = BigFraction.of(BIG_ONE shl 1024, BIG_ONE)

        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(Double.POSITIVE_INFINITY), listOf(exact))

        assertEquals(exact, sample.exactReals?.single())
        assertEquals(Double.POSITIVE_INFINITY, sample.approximateRealValue(0))
    }

    @Test
    fun `an accepted witness should retain its source semantic policy through an unchanged copy`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val source = Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics)
        val sample = Sample(booleanArrayOf(true), longArrayOf())
        val runtime = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))
        sample.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(runtime, sample, null),
        )

        val copied = sample.copy()

        assertEquals(semantics.effective, copied.semantics?.effective)
    }

    @Test
    fun `replacing witness coordinates should discard source semantic qualification`() {
        val source = Problem(
            1, 0, emptyArray(), emptyArray(), semantics = SourceSemantics(SourceFormat.DIMACS).resolve(),
        )
        val sample = Sample(booleanArrayOf(true), longArrayOf())
        val runtime = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))
        sample.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(runtime, sample, null),
        )

        val replaced = sample.copy(bools = booleanArrayOf(false))

        assertNull(replaced.semantics)
    }

}
