package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpEpochBatchTest {
    @Test
    fun `rejected batch adoption leaves staged witnesses available for retry`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10, cost = 1) }.build(Sense.MINIMIZE).trailModel())
        var reject = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        LpPropagator(object : LpSearchPolicy {}, solveContext = LpSolveContext(engineFactory = factory)).use { core ->
            assertTrue(core.install(Any(), source))
            assertNotNull(core.solve())
            val before = core.state
            val lower = listOf(ExactLpSide(ExactLpNumber.of(2L)))
            val upper = listOf(ExactLpSide(ExactLpNumber.of(8L)))
            reject = true

            assertIs<LpBoundBatchResult.Declined>(core.assertBounds(lower, upper))

            assertSame(before, core.state)
            reject = false
            assertEquals(LpBoundBatchResult.Applied(2), core.assertBounds(lower, upper))
            assertEquals(listOf(0L, 1L), assertNotNull(core.state).assertions.map { it.witness })
            assertNotNull(core.solve())
        }
    }

    @Test
    fun `unprojectable donors must be repaired by the first accepted assertion`() {
        val zero = ExactLpNumber.of(0L)
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 2048, BigInteger.ONE))
        val source = ExactLpModel(
            List(2) { emptyList() },
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(upper = ExactLpSide(huge)))),
            emptyList(),
            ExactLpObjective(listOf(zero, zero)),
        )
        for (repairFirst in listOf(false, true)) {
            val trail = LpBoundTrail(source)
            val before = trail.state
            val updates = listOf(
                LpBoundAssertion(0, false, ExactLpSide(zero), 0, 0),
                LpBoundAssertion(1, true, ExactLpSide(zero), 1, 0),
            ).let { if (repairFirst) it.reversed() else it }

            val result = trail.assertBounds(updates)

            if (repairFirst) {
                assertEquals(LpBoundBatchResult.Applied(2), result)
                assertEquals(listOf(0, 1), trail.state.changedColumns)
                assertNotNull(trail.state.toWorkingModel())
            } else {
                assertIs<LpBoundBatchResult.Declined>(result)
                assertSame(before, trail.state)
            }
        }
    }

    @Test
    fun `unprojectable adoption preserves an exact continuation retry`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10, cost = 1) }.build(Sense.MINIMIZE).trailModel())
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 2048, BigInteger.ONE))
        val invalid = LpExactState(
            source.copy(
                columns = listOf(
            source.column(0).copy(bounds = ExactLpBounds(source.column(0).bounds.lower, ExactLpSide(huge))),
        )
            )
        )
        RevisedSimplex(model).use { solver ->
            assertNotNull(solver.solve())
            assertNotNull(solver.continuationBasis(model))

            assertFalse(solver.adopt(invalid))

            val retained = assertNotNull(solver.continuationBasis(model))
            assertNotNull(continueExactLp(model, retained).witness)
        }
    }

    @Test
    fun `ordered batches retain equal and weaker witnesses through backjump`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).trailModel())
        val batch = LpBoundTrail(source)
        val sequential = LpBoundTrail(source)
        assertTrue(batch.push())
        assertTrue(sequential.push())
        val updates = listOf(
            LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(0L)), 0, 1),
            LpBoundAssertion(0, true, ExactLpSide(ExactLpNumber.of(8L)), 1, 1),
            LpBoundAssertion(0, true, ExactLpSide(ExactLpNumber.of(8L)), 2, 1),
            LpBoundAssertion(0, true, ExactLpSide(ExactLpNumber.of(9L)), 3, 1),
        )
        for (update in updates) {
            assertTrue(sequential.assertBound(update.column, update.upper, update.side, update.witness))
        }

        assertEquals(LpBoundBatchResult.Applied(4), batch.assertBounds(updates))

        assertTrue(batch.state.fullAuthorityEquals(sequential.state))
        assertEquals(1L, batch.state.activeSide(0, true)?.witness)
        assertEquals(listOf(0L, 1L, 2L, 3L), batch.state.assertions.map { it.witness })
        assertEquals(4L, batch.state.boundRevision)
        assertTrue(batch.pop(0))
        assertTrue(sequential.pop(0))
        assertTrue(batch.state.fullAuthorityEquals(sequential.state))
    }

    @Test
    fun `first conflicting prefix retains exact strict witnesses and ignores malformed tail`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).trailModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        val premise = ExactLpPremises(emptyList(), listOf(7))
        val updates = listOf(
            LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(5L), strict = true, premises = premise), 0, 1),
            LpBoundAssertion(0, true, ExactLpSide(ExactLpNumber.of(5L)), 1, 1),
            LpBoundAssertion(99, false, ExactLpSide(ExactLpNumber.of(0L)), -1, 9),
        )

        val result = assertIs<LpBoundBatchResult.Conflict>(trail.assertBounds(updates))

        assertEquals(2, result.count)
        assertEquals(0L, result.reason.lower.witness)
        assertEquals(1L, result.reason.upper.witness)
        assertEquals(premise, result.reason.lower.side.premises)
        val before = trail.state
        assertEquals(LpBoundBatchResult.Conflict(0, result.reason), trail.assertBounds(updates))
        assertSame(before, trail.state)
        assertTrue(trail.pop(0))
        assertNull(trail.state.conflict)
    }

    @Test
    fun `projection is checked at the first active prefix rather than only final bounds`() {
        val zero = ExactLpNumber.of(0L)
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 2048, BigInteger.ONE))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(source)
        val before = trail.state

        val result = trail.assertBounds(
            listOf(
                LpBoundAssertion(0, true, ExactLpSide(huge), 0, 0),
                LpBoundAssertion(0, true, ExactLpSide(zero), 1, 0),
            ),
        )

        assertIs<LpBoundBatchResult.Declined>(result)
        assertSame(before, trail.state)
        assertEquals(
            LpBoundBatchResult.Applied(2),
            trail.assertBounds(
                listOf(
                    LpBoundAssertion(0, true, ExactLpSide(zero), 0, 0),
                    LpBoundAssertion(0, true, ExactLpSide(huge), 1, 0),
                ),
            ),
        )
        assertEquals(zero, trail.state.model.column(0).bounds.upper?.number)
    }

    @Test
    fun `cancelled preparation leaves every ordered assertion unpublished`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).trailModel())
        val updates = listOf(
            LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(2L)), 0, 0),
            LpBoundAssertion(0, true, ExactLpSide(ExactLpNumber.of(8L)), 1, 0),
        )
        for (stop in 1..7) {
            val trail = LpBoundTrail(source)
            val before = trail.state
            var polls = 0

            assertIs<LpBoundBatchResult.Declined>(trail.assertBounds(updates, Cancellation { ++polls >= stop }))

            assertSame(before, trail.state)
        }
    }

    @Test
    fun `duplicate witnesses and revision overflow reject the entire batch`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).trailModel())
        val update = LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(2L)), 0, 0)
        for (overflow in listOf(false, true)) {
            val initial = LpExactState(source, boundRevision = if (overflow) Long.MAX_VALUE - 1 else 0L)
            val trail = LpBoundTrail(initial)
            val second = update.copy(
                upper = true,
                side = ExactLpSide(ExactLpNumber.of(8L)),
                witness = if (overflow) 1 else 0,
            )

            assertIs<LpBoundBatchResult.Declined>(trail.assertBounds(listOf(update, second)))

            assertSame(initial, trail.state)
        }
    }

    @Test
    fun `propagator skips equal active bounds without spending witness identities`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).trailModel())
        LpPropagator(object : LpSearchPolicy {}).use { core ->
            assertTrue(core.install(Any(), source))
            val lower = listOf(ExactLpSide(ExactLpNumber.of(0L)))
            val upper = listOf(ExactLpSide(ExactLpNumber.of(8L)))

            assertEquals(LpBoundBatchResult.Applied(1), core.assertBounds(lower, upper))
            val before = assertNotNull(core.state)
            assertEquals(LpBoundBatchResult.Applied(0), core.assertBounds(lower, upper))

            assertSame(before, core.state)
            assertEquals(0L, before.assertions.single().witness)
            assertTrue(core.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L))))
            assertEquals(listOf(0L, 1L), assertNotNull(core.state).assertions.map { it.witness })
        }
    }

    @Test
    fun `cancelled adoption preserves the continuation target and successful adoption retires it`() {
        val source = assertNotNull(
            LpBuilder().apply {
            addVar(0, 10, cost = 1)
            addRow(intArrayOf(0), longArrayOf(1), Relation.EQ, 1)
        }.build(Sense.MINIMIZE).trailModel()
        )
        for (stop in listOf(1, 2)) {
            val initial = LpExactState(source)
            val model = assertNotNull(initial.toWorkingModel())
            RevisedSimplex(model).use { solver ->
                assertNotNull(solver.solve())
                val basis = assertNotNull(solver.continuationBasis(model))
                var polls = 0

                assertFalse(solver.adopt(LpExactState(source), Cancellation { ++polls >= stop }))

                val retained = assertNotNull(solver.continuationBasis(model))
                assertContentEquals(basis.basicVars, retained.basicVars)
                assertContentEquals(basis.status, retained.status)
                assertNotNull(continueExactLp(model, retained).witness)
                assertTrue(solver.adopt(LpExactState(source)))
                assertNull(solver.continuationBasis(model))
            }
        }
    }

    @Test
    fun `failed numerical refresh closes the owner without publishing exact bounds`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10, cost = 1) }.build(Sense.MINIMIZE).trailModel())
        val primary = IllegalStateException("refresh failure")
        val cleanup = IllegalStateException("cleanup failure")
        var fail = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                        val accepted = delegate.adopt(state, token)
                        if (fail) throw primary
                        return accepted
                    }
                    override fun close() {
                        delegate.close()
                        if (fail) throw cleanup
                    }
                }
            }
        }
        LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { owner ->
            assertNotNull(owner.solve())
            val before = owner.state
            fail = true

            val failure = assertFailsWith<IllegalStateException> {
                owner.assertBounds(listOf(LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(2L)), 0, 0)))
            }

            assertSame(primary, failure)
            assertSame(cleanup, failure.suppressedExceptions.single())
            assertSame(before, owner.state)
            assertNull(owner.lastResult)
            assertNull(owner.solve())
            assertEquals(0L, owner.metrics.currentOwners)
        }
    }
}
