package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.negate
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconstructedCertificateTest {
    @Test
    fun `nonoptimal point and weaker bound remain independent`() {
        val model = LpBuilder().apply { addVar(0L, 5L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = reconstructCertificate(model, doubleArrayOf(3.0), doubleArrayOf())

        assertEquals(listOf(BigFraction.ofLong(3L)), result.witness?.primal)
        assertEquals(BigFraction.ofLong(3L), result.witness?.objective)
        assertEquals(BigFraction.ZERO, result.bound?.value)
        assertFalse(result.complementary)
        assertFalse(result.nonbasicStatusesMatch)
    }

    @Test
    fun `free point does not validate its zero nonbasic status`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.FREE))

        val result = reconstructCertificate(model, doubleArrayOf(5.0), doubleArrayOf(), basis)

        assertEquals(BigFraction.ofLong(5L), result.witness?.primal?.single())
        assertTrue(result.complementary)
        assertFalse(result.nonbasicStatusesMatch)
    }

    @Test
    fun `bad rationals cannot satisfy an exact equality`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)

        val result = reconstructCertificate(model, doubleArrayOf(0.3))

        assertNull(result.witness)
        assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
    }

    @Test
    fun `thirds reconstruct into independently evaluated primal and dual proofs`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val third = BigFraction.of(BIG_ONE, bigIntOf(3))

        val result = reconstructCertificate(model, doubleArrayOf(1.0 / 3.0), doubleArrayOf(1.0 / 3.0))

        val x = assertNotNull(result.witness).primal.single()
        assertEquals(BigFraction.ONE, x * BigFraction.ofLong(3L))
        assertEquals(third, x)
        assertEquals(third, result.bound?.value)
        assertTrue(result.complementary)
        assertTrue(result.metrics.attempts > 0)
    }

    @Test
    fun `rational witnesses preserve signs across word boundaries`() {
        val values = listOf(
            BigFraction.of(bigIntOf(-2), bigIntOf(3)),
            BigFraction.ofLong(Long.MIN_VALUE),
            BigFraction.ofLong(Long.MAX_VALUE),
            BigFraction.of((BIG_ONE shl 64) + BIG_ONE, BIG_ONE),
            BigFraction.of(((BIG_ONE shl 64) + BIG_ONE).negate(), BIG_ONE),
            BigFraction.of(BIG_ONE, (BIG_ONE shl 64) + BIG_ONE),
        )
        for (value in values) {
            val source = ExactLpModel(
                listOf(emptyList()), emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(value))))),
                emptyList(), ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            )

            val result = verifyRationalCertificate(
                assertNotNull(LpExactState(source).toWorkingModel()), listOf(value), emptyList(),
            )

            assertEquals(listOf(value), assertNotNull(result.witness).primal)
            assertEquals(value, result.witness?.objective)
            assertEquals(value, result.bound?.value)
        }
    }

    @Test
    fun `wrong fixed status cannot seat unequal exact endpoints`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)

        val result = reconstructCertificate(
            model,
            doubleArrayOf(0.5),
            doubleArrayOf(),
            Basis(intArrayOf(), arrayOf(VarStatus.FIXED)),
        )

        assertEquals(BigFraction.ofDouble(0.5), result.witness?.primal?.single())
        assertFalse(result.nonbasicStatusesMatch)
        assertTrue(result.complementary)
    }

    @Test
    fun `costed logical bounds preserve defining row premises at zero dual`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val premises = ExactLpPremises(emptyList(), listOf(17))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(global = false, premises = premises)),
            ExactLpObjective(
                listOf(zero, one),
                constant = ExactLpNumber.of(6L),
                scale = ExactLpNumber.of(2L),
                externalConstant = ExactLpNumber.of(4L),
            ),
        )
        val state = LpExactState(source)

        val result = reconstructCertificate(
            assertNotNull(state.toWorkingModel()),
            doubleArrayOf(1.0),
            doubleArrayOf(0.0),
        )

        assertEquals(BigFraction.ofLong(7L), result.witness?.objective)
        assertEquals(BigFraction.ofLong(7L), result.bound?.value)
        val support = assertNotNull(result.bound?.support)
        assertEquals(listOf(0), support.rows.map { it.first })
        assertEquals(premises, support.rows.single().second.premises)
        assertEquals(listOf(1), support.sides.map { it.column })
        assertTrue(result.complementary)
    }

    @Test
    fun `fixed arithmetic overflow restarts the full point from rational authority`() {
        val huge = BigFraction.of(BIG_ONE shl 100, BIG_ONE)
        val one = ExactLpNumber.of(1L)
        val h = ExactLpNumber.of(huge)
        val source = ExactLpModel(
            listOf(emptyList(), emptyList()),
            emptyList(),
            List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(h))) },
            emptyList(),
            ExactLpObjective(listOf(h, ExactLpNumber.of(huge.negated())), constant = one),
        )

        val result = reconstructCertificate(
            assertNotNull(LpExactState(source).toWorkingModel()),
            doubleArrayOf(huge.toDouble(), huge.toDouble()),
        )

        assertEquals(BigFraction.ONE, result.witness?.objective)
        assertEquals(listOf(huge, huge), result.witness?.primal)
        assertTrue(result.metrics.verificationRestarts > 0)
    }

    @Test
    fun `strict equality is a bound but never a feasible point`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )

        val result = reconstructCertificate(
            assertNotNull(LpExactState(source).toWorkingModel()),
            doubleArrayOf(0.0),
            doubleArrayOf(),
        )

        assertNull(result.witness)
        assertEquals(BigFraction.ZERO, result.bound?.value)
        assertFalse(result.complementary)
    }

    @Test
    fun `missing support and free residues cannot certify a ray`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            List(2) { ExactLpColumn(ExactLpBounds()) },
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )

        val result = reconstructCertificate(
            assertNotNull(LpExactState(source).toWorkingModel()),
            ray = doubleArrayOf(1.0),
        )

        assertNull(result.conflict)
        assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
    }

    @Test
    fun `strict selected support can contradict at zero surplus`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(zero),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )

        val result = reconstructCertificate(
            assertNotNull(LpExactState(source).toWorkingModel()),
            ray = doubleArrayOf(-1.0),
        )

        assertNotNull(result.conflict)
        assertTrue(assertNotNull(result.conflictSupport).sides.any { it.side.strict })
    }

    @Test
    fun `resource and early cancellation declines publish no partial proof`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        for (limits in listOf(
            ReconstructionLimits(maxWork = 0),
            ReconstructionLimits(maxAllocation = 0),
            ReconstructionLimits(maxCoordinates = 0),
        )) {
            val result = reconstructCertificate(model, doubleArrayOf(0.0), limits = limits)
            assertNull(result.witness)
            assertNotNull(result.metrics.decline)
        }
        val cancelled = reconstructCertificate(model, doubleArrayOf(0.0), cancellation = Cancellation { true })
        assertEquals(ReconstructionDecline.CANCELLED, cancelled.metrics.decline)
        assertNull(cancelled.witness)
    }

    @Test
    fun `late resource stop retains a completed independent point`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val complete = reconstructCertificate(model, doubleArrayOf(0.5), limits = ReconstructionLimits(maxAttempts = 0))
        val work = complete.metrics.work

        val result = reconstructCertificate(
            model,
            doubleArrayOf(0.5),
            doubleArrayOf(),
            limits = ReconstructionLimits(maxWork = work + 10, maxAttempts = 0),
        )

        assertEquals(complete.witness?.primal, assertNotNull(result.witness).primal)
        assertEquals(ReconstructionDecline.WORK, result.metrics.decline)
        assertNull(result.bound)
    }

    @Test
    fun `cancellation before returning reconstruction discards completed proof`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        var total = 0
        val completed = reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            cancellation = Cancellation {
                total++
                false
            },
            limits = ReconstructionLimits(maxAttempts = 0),
        )
        assertNotNull(completed.bound)
        var checks = 0

        val result = reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            cancellation = Cancellation { ++checks >= total },
            limits = ReconstructionLimits(maxAttempts = 0),
        )

        assertEquals(ReconstructionDecline.CANCELLED, result.metrics.decline)
        assertNull(result.bound)
    }

}
