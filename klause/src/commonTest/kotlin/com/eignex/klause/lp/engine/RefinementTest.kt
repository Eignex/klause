package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RefinementTest {
    @Test
    fun `an unchanged checked point can gain a certified objective bound`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxRounds = 1)),
                doubleArrayOf(0.5),
                doubleArrayOf(0.0),
                Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED)),
                witness = ExactLpWitness(listOf(third), third),
            )

            assertEquals(listOf(third), assertNotNull(result.witness).primal)
            assertEquals(third, assertNotNull(result.bound).value)
            assertEquals(1, result.metrics.rounds)
            assertNull(result.conflict)
        }
    }

    @Test
    fun `residual correction reaches a fractional optimum including logical cost`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), integral = false) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, one)),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val model = assertNotNull(state.toWorkingModel())
            val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
            val result = refineLp(
                model,
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(0.25),
                doubleArrayOf(0.0),
                basis,
            )

            val point = assertNotNull(result.witness)
            assertEquals(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3)), point.primal.single())
            assertEquals(BigFraction.ONE, point.primal.single() * BigFraction.ofLong(3L))
            assertEquals(BigFraction.ZERO, point.objective)
            assertEquals(point.objective, assertNotNull(result.bound).value)
            assertTrue(result.metrics.rounds > 0)
            assertSame(state, owner.state)
            assertNull(owner.lastResult)
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
        }
    }

    @Test
    fun `exact seeds preserve constants beyond double integer precision`() {
        val origin = BigFraction.of(BigInteger.ONE shl 80, BigInteger.ONE)
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))
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

    @Test
    fun `an unchanged exhausted attempt cannot buy another child`() {
        val builder = LpBuilder()
        builder.addVar(0, 2, cost = 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        LpScopedSolver(state).use { owner ->
            val request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxWork = 1L))
            val first = refineLp(assertNotNull(state.toWorkingModel()), request, doubleArrayOf(1.0), doubleArrayOf())
            val second = refineLp(assertNotNull(state.toWorkingModel()), request, doubleArrayOf(0.5), doubleArrayOf())

            assertEquals(LpRefinementDecline.WORK, first.metrics.decline)
            assertEquals(LpRefinementDecline.REPEATED, second.metrics.decline)
            assertEquals(0L, second.metrics.work)
            assertEquals(0L, owner.metrics.createdOwners)
            assertNull(owner.lastWorkingMetrics)
        }
    }

    @Test
    fun `cancellation withholds correction publication and leaves source available`() {
        val builder = LpBuilder()
        builder.addVar(0, 2, cost = 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(1.0),
                doubleArrayOf(),
                cancellation = Cancellation { true },
            )

            assertEquals(LpRefinementDecline.CANCELLED, result.metrics.decline)
            assertNull(result.witness)
            assertSame(state, owner.state)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `large precision inputs decline before creating a numerical child`() {
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 160, BigInteger.ONE))
        val state = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(huge)), integral = false)),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxBits = 64)),
            )

            assertEquals(LpRefinementDecline.BITS, result.metrics.decline)
            assertNull(result.witness)
            assertNull(owner.lastWorkingMetrics)
        }
    }
}
