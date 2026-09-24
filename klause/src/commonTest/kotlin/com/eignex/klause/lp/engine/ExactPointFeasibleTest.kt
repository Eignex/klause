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

class ExactPointFeasibleTest {
    @Test
    fun `independent denominators produce a checked point`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        val y = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(2_000_003.0), Relation.EQ, 1.0)
        builder.addRealRow(intArrayOf(y), doubleArrayOf(2_000_029.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val result = recoverExactPointWitness(
                assertNotNull(state.toWorkingModel()),
                doubleArrayOf(1.0 / 2_000_003.0, 1.0 / 2_000_029.0),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                Cancellation.Never,
            )

            assertEquals(0, result.repairs)
            assertNotNull(result.witness)
            assertEquals(null, result.decline)
        }
    }

    @Test
    fun `two sparse row residuals admit exact local correction`() {
        val builder = LpBuilder()
        val values = doubleArrayOf(
            5.0 / 8.0,
            129.0 / 1024.0,
            1973.0 / 22711.0,
            1017.0 / 6272.0,
            3.0 / 128.0,
            221.0 / 784.0,
            670.0 / 20739.0,
        )
        val columns = IntArray(values.size) { builder.addRealVar(0.0, 1.0) }
        builder.addRealRow(
            columns.copyOfRange(0, 4),
            doubleArrayOf(1.0, 1.0, 1.0, 1.0),
            Relation.EQ,
            1.0,
        )
        builder.addRealRow(
            intArrayOf(columns[4], columns[5], columns[6]),
            doubleArrayOf(1.0, -1.0, 8.0),
            Relation.LE,
            0.0,
        )
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val model = assertNotNull(state.toWorkingModel())
            val result = recoverExactPointWitness(
                model,
                values,
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                Cancellation.Never,
            )

            assertEquals(2, result.repairs)
            assertNotNull(result.witness)
            assertNotNull(checkedLpWitness(model, result.witness.primal))
        }
    }

    @Test
    fun `incompatible rows reject a reconstructed point`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val result = recoverExactPointWitness(
                assertNotNull(state.toWorkingModel()),
                doubleArrayOf(0.5),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                Cancellation.Never,
            )

            assertNull(result.witness)
            assertEquals(LpRefinementDecline.CANDIDATE, result.decline)
        }
    }

    @Test
    fun `strict row boundary is not used as a repair target`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0, strict = true)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val result = recoverExactPointWitness(
                assertNotNull(state.toWorkingModel()),
                doubleArrayOf(1.0),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                Cancellation.Never,
            )

            assertNull(result.witness)
            assertEquals(0, result.repairs)
        }
    }

    @Test
    fun `binary feasible point avoids reconstruction and repair`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 1.0)
        val model = builder.build(Sense.MINIMIZE)

        val point = assertNotNull(exactPointWitness(model, doubleArrayOf(0.5)))

        assertEquals(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(2)), point.primal.single())
    }

    @Test
    fun `cancelled point attempt retains only spent source work`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits())
            val first = recoverExactPointWitness(
                assertNotNull(state.toWorkingModel()),
                doubleArrayOf(0.5),
                request,
                Cancellation { true },
            )

            assertNull(first.witness)
            assertEquals(LpRefinementDecline.CANCELLED, first.decline)
            assertEquals(first.work, owner.refinementCache.work)
        }
    }

    @Test
    fun `cancellation after reconstruction retains spent source work`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            var polls = 0
            val result = recoverExactPointWitness(
                assertNotNull(state.toWorkingModel()),
                doubleArrayOf(0.5),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                Cancellation { ++polls > 10 },
            )

            assertNull(result.witness)
            assertEquals(LpRefinementDecline.CANCELLED, result.decline)
            assertTrue(result.work > 0L)
            assertEquals(result.work, owner.refinementCache.work)
        }
    }

    @Test
    fun `oversized exact row declines within the bit limit`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1e40)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val result = recoverExactPointWitness(
                assertNotNull(state.toWorkingModel()),
                doubleArrayOf(0.5),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxBits = 24)),
                Cancellation.Never,
            )

            assertNull(result.witness)
            assertEquals(LpRefinementDecline.BITS, result.decline)
            assertEquals(result.work, owner.refinementCache.work)
        }
    }

    @Test
    fun `point allocation exhaustion persists in the source ledger`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val result = recoverExactPointWitness(
                assertNotNull(state.toWorkingModel()),
                doubleArrayOf(0.5),
                LpRefinementRequest(
                    owner,
                    owner.refinementCache,
                    LpRefinementLimits(maxAllocation = 0L),
                ),
                Cancellation.Never,
            )

            assertNull(result.witness)
            assertEquals(LpRefinementDecline.ALLOCATION, result.decline)
            assertEquals(result.allocation, owner.refinementCache.allocation)
        }
    }

    @Test
    fun `recovered point maps the source objective scale and constant`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 1.0)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val state = LpExactState(
            source.copy(
                objective = ExactLpObjective(
            listOf(ExactLpNumber.of(2L)),
            scale = ExactLpNumber.of(2L),
            externalConstant = ExactLpNumber.of(3L),
        )
            )
        )

        LpScopedSolver(state).use { owner ->
            val model = assertNotNull(state.toWorkingModel())
            val result = recoverExactPointWitness(
                model,
                doubleArrayOf(0.5),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                Cancellation.Never,
            )

            val point = assertNotNull(result.witness)
            val expected = BigFraction.of(BigInteger.fromInt(7), BigInteger.fromInt(2))
            assertEquals(expected, point.objective)
            val checked = assertNotNull(checkedLpWitness(model, point.primal))
            assertEquals(point.primal, checked.primal)
            assertEquals(point.objective, checked.objective)
        }
    }

    @Test
    fun `point work exhaustion persists in the source ledger`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val request = LpRefinementRequest(
                owner,
                owner.refinementCache,
                LpRefinementLimits(maxWork = 2L),
            )
            val model = assertNotNull(state.toWorkingModel())
            val first = recoverExactPointWitness(
                model,
                doubleArrayOf(0.5),
                request,
                Cancellation.Never,
            )
            val second = recoverExactPointWitness(
                model,
                doubleArrayOf(0.5),
                request,
                Cancellation.Never,
            )

            assertNull(first.witness)
            assertEquals(LpRefinementDecline.WORK, first.decline)
            assertEquals(LpRefinementDecline.WORK, second.decline)
            assertEquals(first.work + second.work, owner.refinementCache.work)
            assertEquals(2L, owner.refinementCache.work)
        }
    }

    @Test
    fun `near zero equalities remain contradictory for every point candidate`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1e-10)
        }.build(Sense.MINIMIZE)

        for (candidate in listOf(0.0, 1e-10)) {
            assertFalse(exactPointFeasible(model, doubleArrayOf(candidate)))
            assertNull(exactPointWitness(model, doubleArrayOf(candidate)))
        }
    }

    @Test
    fun `reconstructed point is accepted only after exact row validation`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)

        val witness = assertNotNull(exactPointWitness(model, doubleArrayOf(1.0 / 3.0)))

        assertEquals(BigFraction.ONE, BigFraction.ofLong(3L) * witness.primal.single())
        assertEquals(BigFraction.ZERO, witness.objective)
        assertTrue(exactPointFeasible(model, doubleArrayOf(1.0 / 3.0)))
    }

    @Test
    fun `binary decimal projection is not a parsed decimal witness`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.1)
        }.build(Sense.MINIMIZE)
        val decimal = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(10))

        val witness = assertNotNull(exactPointWitness(model, doubleArrayOf(0.1)))

        assertEquals(BigFraction.ofDouble(0.1), witness.primal.single())
        assertTrue(witness.primal.single() != decimal)
        assertNull(checkedLpWitness(model, listOf(decimal)))
    }
}
