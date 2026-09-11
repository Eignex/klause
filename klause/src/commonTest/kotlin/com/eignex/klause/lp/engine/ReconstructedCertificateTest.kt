package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
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
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))

        val result = reconstructCertificate(model, doubleArrayOf(1.0 / 3.0), doubleArrayOf(1.0 / 3.0))

        val x = assertNotNull(result.witness).primal.single()
        assertEquals(BigFraction.ONE, x * BigFraction.ofLong(3L))
        assertEquals(third, x)
        assertEquals(third, result.bound?.value)
        assertTrue(result.complementary)
        assertTrue(result.metrics.attempts > 0)
    }

    @Test
    fun `dual reconstruction is useful without a feasible point`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))

        val result = reconstructCertificate(model, duals = doubleArrayOf(1.0 / 3.0))

        assertNull(result.witness)
        assertEquals(third, result.bound?.value)
        assertFalse(result.complementary)
    }

    @Test
    fun `exact source endpoints beyond the denominator floor are seated without guessing`() {
        val endpoint = BigFraction.of(BigInteger.ONE, (BigInteger.ONE shl 60) + BigInteger.ONE)
        val zero = ExactLpNumber.of(0L)
        val side = ExactLpSide(ExactLpNumber.of(endpoint))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(side, side))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.FIXED))

        val result = reconstructCertificate(model, doubleArrayOf(endpoint.toDouble()), doubleArrayOf(), basis)

        assertEquals(endpoint, result.witness?.primal?.single())
        assertTrue(result.nonbasicStatusesMatch)
        assertTrue(result.complementary)
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
    fun `mixed boxes use exact endpoint signs and all costs`() {
        val zero = ExactLpNumber.of(0L)
        val two = ExactLpNumber.of(2L)
        val source = ExactLpModel(
            List(4) { emptyList() },
            emptyList(),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(two))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(two), ExactLpSide(two))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(two))),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero, ExactLpNumber.of(-3L), ExactLpNumber.of(4L), ExactLpNumber.of(5L))),
        )

        val result = reconstructCertificate(
            assertNotNull(LpExactState(source).toWorkingModel()),
            doubleArrayOf(7.0, 2.0, 2.0, 0.0),
            doubleArrayOf(),
        )

        assertEquals(BigFraction.ofLong(2L), result.witness?.objective)
        assertEquals(BigFraction.ofLong(2L), result.bound?.value)
        assertEquals(listOf(true, false, false), result.bound?.support?.sides?.map { it.upper })
        assertTrue(result.complementary)
    }

    @Test
    fun `fixed arithmetic overflow restarts the full point from rational authority`() {
        val huge = BigFraction.of(BigInteger.ONE shl 100, BigInteger.ONE)
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
    fun `rational data beyond fixed arithmetic remains authoritative`() {
        val huge = BigFraction.of((BigInteger.ONE shl 150) + BigInteger.ONE, BigInteger.fromInt(3))
        val h = ExactLpNumber.of(huge)
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(h), ExactLpSide(h)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )

        val result = reconstructCertificate(
            assertNotNull(LpExactState(source).toWorkingModel()),
            doubleArrayOf(huge.toDouble()),
            doubleArrayOf(),
            Basis(intArrayOf(), arrayOf(VarStatus.FIXED)),
        )

        assertEquals(huge, result.witness?.primal?.single())
        assertTrue(result.metrics.verificationRestarts > 0)
        assertTrue(result.complementary)
    }

    @Test
    fun `zero violation checks exact IEEE values above the denominator floor`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val tiny = 1.0 / (1L shl 50).toDouble()

        val result = reconstructCertificate(model, doubleArrayOf(tiny), doubleArrayOf())

        assertEquals(BigFraction.ofDouble(tiny), result.witness?.primal?.single())
        assertEquals(0, result.metrics.attempts)
        assertTrue(result.complementary)
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
    fun `nonfinite candidates decline before reconstruction`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val result = reconstructCertificate(model, doubleArrayOf(value))
            assertNull(result.witness)
            assertEquals(ReconstructionDecline.NONFINITE, result.metrics.decline)
            assertEquals(0, result.metrics.attempts)
        }
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
    fun `rational ray cancels free columns and captures historical local antecedents`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        val premises = ExactLpPremises(emptyList(), listOf(23))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one), ExactLpEntry(1, three))),
            listOf(zero, one),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(global = false, premises = premises), ExactLpRow()),
            ExactLpObjective(listOf(zero, zero, zero)),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(2, true, ExactLpSide(zero), 7L))
        val state = trail.state
        val input = doubleArrayOf(-1.0, 1.0 / 3.0)

        val result = reconstructCertificate(assertNotNull(state.toWorkingModel()), ray = input)
        input.fill(0.0)
        assertTrue(trail.pop(0))

        val conflict = assertNotNull(result.conflict)
        val y = conflict.multipliers
        assertEquals(BigFraction.ZERO, y[0] + y[1] * BigFraction.ofLong(3L))
        assertTrue(y[1] < BigFraction.ZERO)
        assertEquals(listOf(0, 1), conflict.rows.toList())
        val support = assertNotNull(result.conflictSupport)
        assertEquals(state, support.state)
        assertEquals(premises, support.rows.first().second.premises)
        assertTrue(support.sides.any { it.column == 2 && it.upper })
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
    fun `exhaustion during violation evaluation preserves the completed bound`() {
        val model = LpBuilder().apply { addVar(0L, 1L, cost = 1L) }.build(Sense.MINIMIZE)
        val complete = reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            limits = ReconstructionLimits(maxAttempts = 0),
        )

        val result = reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            limits = ReconstructionLimits(maxWork = complete.metrics.work - 1L, maxAttempts = 0),
        )

        assertEquals(ReconstructionPhase.VIOLATION, result.metrics.phase)
        assertEquals(ReconstructionDecline.WORK, result.metrics.decline)
        assertEquals(BigFraction.ZERO, result.bound?.value)
        assertNull(result.witness)
    }

    @Test
    fun `resource exhaustion during support capture withholds the incomplete bound`() {
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
        val complete = reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            limits = ReconstructionLimits(maxAttempts = 0),
        )
        val result = reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            limits = ReconstructionLimits(maxWork = complete.metrics.work - 5L, maxAttempts = 0),
        )

        assertEquals(ReconstructionPhase.SUPPORT, result.metrics.phase)
        assertEquals(ReconstructionDecline.WORK, result.metrics.decline)
        assertNull(result.bound)
        assertNull(result.conflictSupport)
    }

    @Test
    fun `bounded representative slice records total reconstruction work`() {
        val equality = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val box = LpBuilder().apply { addVar(0L, 5L, cost = 1L) }.build(Sense.MINIMIZE)
        val results = listOf(
            "thirds-optimum" to reconstructCertificate(equality, doubleArrayOf(1.0 / 3.0), doubleArrayOf(1.0 / 3.0)),
            "dual-only" to reconstructCertificate(equality, duals = doubleArrayOf(1.0 / 3.0)),
            "nonoptimal-point" to reconstructCertificate(box, doubleArrayOf(3.0), doubleArrayOf()),
            "bad-point" to reconstructCertificate(equality, doubleArrayOf(0.3)),
            "nonfinite" to reconstructCertificate(box, doubleArrayOf(Double.NaN)),
            "resource-decline" to reconstructCertificate(
                box,
                doubleArrayOf(0.0),
                limits = ReconstructionLimits(maxWork = 0),
            ),
        )
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))
        assertEquals(third, results[0].second.witness?.primal?.single())
        assertEquals(third, results[0].second.bound?.value)
        assertTrue(results[0].second.complementary)
        assertNull(results[1].second.witness)
        assertEquals(third, results[1].second.bound?.value)
        assertEquals(BigFraction.ofLong(3L), results[2].second.witness?.objective)
        assertEquals(BigFraction.ZERO, results[2].second.bound?.value)
        assertFalse(results[2].second.complementary)
        for ((label, result) in results) {
            if (label in listOf("bad-point", "nonfinite", "resource-decline")) {
                assertNull(result.witness)
                assertNull(result.bound)
                assertNotNull(result.metrics.decline)
            }
            println(
                "RECONSTRUCTION_COVERAGE|$label|${result.witness != null}|${result.bound != null}|" +
                    "${result.conflict != null}|${result.complementary}|${result.metrics}",
            )
        }
    }

    @Test
    fun `cancellation while capturing support cannot publish an incomplete proof`() {
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
        reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            cancellation = Cancellation {
                total++
                false
            },
            limits = ReconstructionLimits(maxAttempts = 0),
        )
        var checks = 0

        val result = reconstructCertificate(
            model,
            duals = doubleArrayOf(),
            cancellation = Cancellation { ++checks >= total - 4 },
            limits = ReconstructionLimits(maxAttempts = 0),
        )

        assertEquals(ReconstructionDecline.CANCELLED, result.metrics.decline)
        assertEquals(ReconstructionPhase.SUPPORT, result.metrics.phase)
        assertNull(result.bound)
    }

    @Test
    fun `rational factor handoff verifies source coordinates without a double roundtrip`() {
        val exact = BigFraction.of(BigInteger.ONE, (BigInteger.ONE shl 70) + BigInteger.ONE)
        val value = ExactLpNumber.of(exact)
        val one = ExactLpNumber.of(1L)
        val origin = ExactLpNumber.of(10L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(value), ExactLpSide(value)), origin = origin)),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val candidate = exact + BigFraction.ofLong(10L)

        val result = verifyRationalCertificate(
            assertNotNull(LpExactState(source).toWorkingModel()),
            sourcePrimal = listOf(candidate),
            duals = emptyList(),
        )

        assertEquals(candidate, result.witness?.primal?.single())
        assertEquals(exact, result.witness?.objective)
        assertEquals(exact, result.bound?.value)
        assertTrue(result.complementary)
        assertEquals(0, result.metrics.attempts)
    }
}
