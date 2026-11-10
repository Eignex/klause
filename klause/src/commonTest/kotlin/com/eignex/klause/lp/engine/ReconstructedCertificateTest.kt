package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.negate
import com.eignex.util.numbers.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconstructedCertificateTest {
    @Test
    fun `a nonoptimal point and a weaker bound should remain independent`() {
        val model = LpBuilder().apply { addVar(0L, 5L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = reconstructCertificate(model, doubleArrayOf(3.0), doubleArrayOf())

        assertEquals(listOf(BigFraction.ofLong(3L)), result.witness?.primal)
        assertEquals(BigFraction.ofLong(3L), result.witness?.objective)
        assertEquals(BigFraction.ZERO, result.bound?.value)
        assertFalse(result.complementary)
        assertFalse(result.nonbasicStatusesMatch)
    }

    @Test
    fun `a free point should not validate its zero nonbasic status`() {
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
    fun `bad rationals should not satisfy an exact equality`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)

        val result = reconstructCertificate(model, doubleArrayOf(0.3))

        assertNull(result.witness)
        assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
    }

    @Test
    fun `thirds should reconstruct into independently evaluated primal and dual proofs`() {
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
    fun `rational witnesses should preserve signs across word boundaries`() {
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
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(value))))),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            )

            val result = verifyRationalCertificate(
                assertNotNull(LpExactState(source).toWorkingModel()),
                listOf(value),
                emptyList(),
            )

            assertEquals(listOf(value), assertNotNull(result.witness).primal)
            assertEquals(value, result.witness.objective)
            assertEquals(value, result.bound?.value)
        }
    }

    @Test
    fun `costed logical bounds should preserve defining row premises at zero dual`() {
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
    fun `zero dual rows should certify within the sparse work allowance`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            repeat(64) { addRow(intArrayOf(0), longArrayOf(1L), Relation.LE, 1L) }
        }.build(Sense.MINIMIZE)

        val result = certifyLpBoundOnly(model, DoubleArray(64),
            ReconstructionLimits(maxWork = 4_000L, maxAttempts = 0))

        assertEquals(BigFraction.ZERO, assertNotNull(result.bound).value)
    }

    @Test
    fun `zero dual rows should retain input scalar limits`() {
        val zero = ExactLpNumber.of(0L)
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 128, BIG_ONE))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, huge))),
            listOf(huge),
            List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )

        val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()), doubleArrayOf(0.0),
            ReconstructionLimits(maxBits = 128, maxAttempts = 0))

        assertNull(result.bound)
        assertEquals(ReconstructionDecline.BITS, result.metrics.decline)
    }

    @Test
    fun `zero dual rows should still reject a missing objective bound side`() {
        val zero = ExactLpNumber.of(0L)
        for (cost in listOf(BigFraction.MINUS_ONE, BigFraction.of(BIG_ONE shl 128, BIG_ONE).negated())) {
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
                listOf(ExactLpNumber.of(1L)),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(ExactLpNumber.of(cost), zero)),
            )

            val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()),
                doubleArrayOf(0.0), ReconstructionLimits(maxAttempts = 0))

            assertNull(result.bound)
            assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
        }
    }

    @Test
    fun `zero right hand sides should reduce checked dual work`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        for (weight in listOf(BigFraction.ONE, BigFraction.of(BIG_ONE shl 128, BIG_ONE))) {
            val results = listOf(0L, 1L).map { rhs ->
                val source = ExactLpModel(
                    listOf(List(32) { ExactLpEntry(it, one) }),
                    List(32) { ExactLpNumber.of(rhs) },
                    listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))) +
                        List(32) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
                    List(32) { ExactLpRow() },
                    ExactLpObjective(List(33) { zero }),
                )

                val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()),
                    DoubleArray(32) { -weight.toDouble() }, ReconstructionLimits(maxAttempts = 0))

                val bound = assertNotNull(result.bound)
                assertEquals(weight * BigFraction.ofLong(-32L * rhs), bound.value)
                assertEquals((0 until 32).toList(), assertNotNull(bound.support).rows.map { it.first })
                result
            }

            assertTrue(results[0].metrics.work < results[1].metrics.work)
        }
    }

    @Test
    fun `zero selected endpoints should reduce checked dual work`() {
        for (cost in listOf(BigFraction.ONE, BigFraction.of(BIG_ONE shl 128, BIG_ONE))) {
            val results = listOf(BigFraction.ZERO, BigFraction.ONE).map { lower ->
                val source = ExactLpModel(
                    listOf(emptyList()),
                    emptyList(),
                    listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(lower))))),
                    emptyList(),
                    ExactLpObjective(listOf(ExactLpNumber.of(cost))),
                )

                val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()),
                    doubleArrayOf(), ReconstructionLimits(maxAttempts = 0))

                val bound = assertNotNull(result.bound)
                assertEquals(cost * lower, bound.value)
                assertEquals(listOf(0), assertNotNull(bound.support).sides.map { it.column })
                result
            }

            assertTrue(results[0].metrics.work < results[1].metrics.work)
        }
    }

    @Test
    fun `zero selected endpoints should preserve strict source support`() {
        for (cost in listOf(BigFraction.ONE, BigFraction.of(BIG_ONE shl 128, BIG_ONE))) {
            val source = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L), strict = true)))),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(cost))),
            )

            val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()),
                doubleArrayOf(), ReconstructionLimits(maxAttempts = 0))

            val bound = assertNotNull(result.bound)
            assertEquals(BigFraction.ZERO, bound.value)
            assertTrue(assertNotNull(bound.support).sides.single().side.strict)
        }
    }

    @Test
    fun `zero right hand sides should not supply a missing bound side`() {
        val zero = ExactLpNumber.of(0L)
        for (weight in listOf(BigFraction.ONE, BigFraction.of(BIG_ONE shl 128, BIG_ONE))) {
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
                listOf(zero),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(ExactLpNumber.of(weight.negated()), zero)),
            )

            val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()),
                doubleArrayOf(-weight.toDouble() / 2.0), ReconstructionLimits(maxAttempts = 0))

            assertNull(result.bound)
            assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
            val rejection = assertNotNull(result.metrics.dualRejection)
            assertEquals(0, rejection.column)
            assertTrue(rejection.upper)
        }
    }

    @Test
    fun `missing bound sides should reject before unrelated endpoint arithmetic`() {
        val zero = ExactLpNumber.of(0L)
        for ((bits, limit) in listOf(70 to 128, 130 to 192)) {
            val weight = ExactLpNumber.of(BigFraction.of(BIG_ONE shl bits, BIG_ONE) + BigFraction.ONE)
            val source = ExactLpModel(
                List(2) { emptyList() },
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(weight), ExactLpSide(weight))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
                emptyList(),
                ExactLpObjective(listOf(weight, ExactLpNumber.of(-1L))),
            )

            val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()), doubleArrayOf(),
                ReconstructionLimits(maxBits = limit, maxAttempts = 0))

            assertNull(result.bound)
            assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
            val rejection = assertNotNull(result.metrics.dualRejection)
            assertEquals(1, rejection.column)
            assertTrue(rejection.upper)
            assertEquals(BigFraction.MINUS_ONE, rejection.reduced)
        }
    }

    @Test
    fun `zero selected endpoints should retain input scalar limits`() {
        val zero = ExactLpNumber.of(0L)
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 128, BIG_ONE))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(huge)))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )

        val result = certifyLpBoundOnly(assertNotNull(LpExactState(source).toWorkingModel()), doubleArrayOf(),
            ReconstructionLimits(maxBits = 128, maxAttempts = 0))

        assertNull(result.bound)
        assertEquals(ReconstructionDecline.BITS, result.metrics.decline)
    }

    @Test
    fun `fixed arithmetic overflow should restart the full point from rational authority`() {
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
    fun `strict equality should be a bound but never a feasible point`() {
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
    fun `missing support and free residues should not certify a ray`() {
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
    fun `independent ray denominators should recover a checked contradiction`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-65521L)),
                ExactLpEntry(1, ExactLpNumber.of(65497L)))),
            listOf(ExactLpNumber.of(-65521L), zero),
            listOf(ExactLpColumn(ExactLpBounds())) +
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(2) { ExactLpRow() },
            ExactLpObjective(List(3) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        for (orientation in listOf(1.0, -1.0)) {
            val ray = doubleArrayOf(-orientation / 65521.0, -orientation / 65497.0)
            val binary = reconstructCertificate(model, ray = ray, limits = ReconstructionLimits(maxAttempts = 0))

            val result = reconstructCertificate(model, ray = ray, limits = ReconstructionLimits(maxAttempts = 1))

            assertNull(binary.conflict)
            val proof = assertNotNull(result.conflict)
            assertEquals(listOf(0, 1), proof.rows.toList())
            assertEquals(listOf(BigFraction.of(BIG_ONE, bigIntOf(65521)),
                BigFraction.of(BIG_ONE, bigIntOf(65497))), proof.multipliers)
            assertNotNull(result.conflictSupport)
            assertNotNull(result.metrics.vectorRejection)
            assertNull(result.metrics.decline)
        }
    }

    @Test
    fun `reconstructed ray entries should not refute a feasible boundary`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-65521L)),
                ExactLpEntry(1, ExactLpNumber.of(65497L)))),
            listOf(ExactLpNumber.of(-65521L), ExactLpNumber.of(65497L)),
            listOf(ExactLpColumn(ExactLpBounds())) +
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(2) { ExactLpRow() },
            ExactLpObjective(List(3) { zero }),
        )

        val result = reconstructCertificate(assertNotNull(LpExactState(source).toWorkingModel()),
            ray = doubleArrayOf(-1.0 / 65521.0, -1.0 / 65497.0),
            limits = ReconstructionLimits(maxAttempts = 1))

        assertNull(result.conflict)
        assertNotNull(result.metrics.vectorRejection)
        assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
    }

    @Test
    fun `ray reconstruction should retain the containing work allowance`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-65521L)),
                ExactLpEntry(1, ExactLpNumber.of(65497L)))),
            listOf(ExactLpNumber.of(-65521L), zero),
            listOf(ExactLpColumn(ExactLpBounds())) +
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(2) { ExactLpRow() },
            ExactLpObjective(List(3) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val ray = doubleArrayOf(-1.0 / 65521.0, -1.0 / 65497.0)
        val binary = reconstructCertificate(model, ray = ray, limits = ReconstructionLimits(maxAttempts = 0))

        val result = reconstructCertificate(model, ray = ray,
            limits = ReconstructionLimits(maxWork = binary.metrics.work, maxAttempts = 1))

        assertNull(result.conflict)
        assertEquals(ReconstructionDecline.WORK, result.metrics.decline)
    }

    @Test
    fun `strict selected support should contradict at zero surplus`() {
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
    fun `resource and early cancellation declines should publish no partial proof`() {
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
    fun `a late resource stop should retain a completed independent point`() {
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
    fun `cancellation before returning reconstruction should discard the completed proof`() {
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

    @Test
    fun `exact seeds should preserve constants beyond double integer precision`() {
        val origin = BigFraction.of(BIG_ONE shl 80, BIG_ONE)
        val third = BigFraction.of(BIG_ONE, bigIntOf(3))
        val zero = ExactLpNumber.of(0L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(ExactLpNumber.of(1L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(), origin = ExactLpNumber.of(origin), integral = false),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(
                    listOf(ExactLpNumber.of(1L), zero),
                    constant = ExactLpNumber.of(origin),
                ),
            ),
        )

        val result = reconstructRationalCertificate(
            assertNotNull(state.toWorkingModel()),
            listOf(origin + third),
            listOf(third),
        )

        assertEquals(listOf(origin + third), assertNotNull(result.witness).primal)
        assertEquals(origin + third, assertNotNull(result.bound).value)
        assertTrue(result.complementary)
    }
}
