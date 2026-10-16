package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
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

class LpBoundTrailTest {
    @Test
    fun `row appends should retain suspended and retired logical semantics across pops`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE).authoritativeModel())
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = true)
        for (suspended in listOf(false, true)) {
            val trail = LpBoundTrail(source)
            assertTrue(trail.append(LpScopedRow(0L, listOf(0 to one), one, logical,
                ExactLpRow(strict = true)), scoped = false))
            assertTrue(trail.push())
            assertTrue(if (suspended) trail.suspend(setOf(0L)) else trail.deactivate(0L))
            val before = trail.state

            assertTrue(trail.append(emptyList(), listOf(LpScopedRow(1L, listOf(0 to one), zero, logical)),
                scoped = true, permanentRows = setOf(1L)))

            val state = trail.state
            val fresh = LpExactState(state.baseModel, state.assertions, state.scopes, rows = state.rows)
            assertTrue(state.model.sameAuthority(fresh.model))
            assertEquals(fresh.rows.entries(), state.rows.entries())
            assertNull(state.activeSide(1, false))
            assertFalse(state.model.row(0).strict)
            assertFalse(state.model.column(1).integral)
            assertNull(state.rows.row(1).depth)
            assertFalse(before.rows.row(0).active)
            assertEquals(1, before.model.m)
            assertTrue(trail.pop(0))
            assertEquals(suspended, trail.state.rows.row(0).active)
            assertEquals(suspended, trail.state.model.row(0).strict)
            assertEquals(suspended, trail.state.model.column(1).integral)
            assertEquals(suspended, trail.state.activeSide(1, false) != null)
            assertTrue(trail.state.rows.row(1).active)
        }
    }

    @Test
    fun `row append validation should use one captured addition and permanent set`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        val before = trail.state
        val one = ExactLpNumber.of(1L)
        val additions = mutableListOf(LpScopedRow(5L, listOf(0 to one), one, ExactLpColumn(ExactLpBounds())))
        val permanent = mutableSetOf(5L)
        var polls = 0
        val token = Cancellation {
            if (++polls == 2) {
                additions.clear()
                additions += LpScopedRow(9L, listOf(99 to one), one, ExactLpColumn(ExactLpBounds()))
                permanent.clear()
            }
            false
        }

        assertTrue(trail.append(emptyList(), additions, scoped = true, token = token, permanentRows = permanent))

        assertEquals(listOf(5L), trail.state.rows.entries().map { it.id })
        assertNull(trail.state.rows.row(0).depth)
        assertEquals(listOf(ExactLpEntry(0, one)), trail.state.model.entries(0))
        assertEquals(0, before.model.m)
        assertTrue(trail.pop(0))
        assertTrue(trail.state.rows.row(0).active)
    }

    @Test
    fun `cancelled and unprojectable row appends should preserve the exact prefix for retry`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE).authoritativeModel())
        val one = ExactLpNumber.of(1L)
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 2048, BIG_ONE))
        for (cancelled in listOf(false, true)) {
            val trail = LpBoundTrail(source)
            assertTrue(trail.assertBound(0, true, ExactLpSide(one), 1L))
            val before = trail.state
            var polls = 0
            val token = Cancellation { cancelled && ++polls >= 3 }
            val row = LpScopedRow(0L, listOf(0 to if (cancelled) one else huge), one,
                ExactLpColumn(ExactLpBounds()))

            assertFalse(trail.append(row, scoped = false, token = token))

            assertSame(before, trail.state)
            assertEquals(1L, trail.state.activeSide(0, true)?.witness)
            assertTrue(trail.append(LpScopedRow(0L, listOf(0 to one), one,
                ExactLpColumn(ExactLpBounds())), scoped = false))
            assertEquals(1, trail.state.model.m)
            assertEquals(0, before.model.m)
        }
    }

    @Test
    fun `weaker active witnesses should survive arbitrary backjumps`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), 1L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 2L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero), 3L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 4L))
        val deepest = trail.state

        assertTrue(trail.pop(1))

        assertEquals(listOf(1L, 2L), trail.state.assertions.map { it.witness })
        assertEquals(2L, trail.state.activeSide(0, false)?.witness)
        assertEquals(4, deepest.assertions.size)
        assertEquals(4L, deepest.activeSide(0, false)?.witness)
        assertEquals(1, trail.state.depth)
        assertEquals(5L, trail.state.boundRevision)
        assertEquals(1L, trail.state.popRevision)
        assertTrue(trail.pop(0))
        assertEquals(1L, trail.state.activeSide(0, false)?.witness)
    }

    @Test
    fun `strict equal endpoint conflicts should retain both exact witnesses`() {
        val zero = ExactLpNumber.of(0L)
        val premise = ExactLpPremises(listOf(ExactLpPremise(7, false, zero)), listOf(5))
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        assertTrue(trail.push())

        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true, premises = premise), 9L))

        val conflict = assertNotNull(trail.state.conflict)
        assertEquals(9L, conflict.lower.witness)
        assertEquals(-2L, conflict.upper.witness)
        assertEquals(premise, conflict.lower.side.premises)
        assertTrue(conflict.lower.side.strict)
        assertTrue(trail.pop(0))
        assertNull(trail.state.conflict)
        assertNull(trail.state.model.column(0).bounds.lower)
    }

    @Test
    fun `bounds sharing a double should select by exact value and never manufacture fixedness`() {
        val low = ExactLpNumber.of(9007199254740992L)
        val high = ExactLpNumber.of(9007199254740993L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(low), ExactLpSide(high)))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(0L))),
        )
        val trail = LpBoundTrail(model)
        assertEquals(low.value.toDouble(), high.value.toDouble())
        assertFalse(trail.state.model.column(0).bounds.fixed)

        assertTrue(trail.assertBound(0, false, ExactLpSide(high), 1L))

        assertTrue(trail.state.model.column(0).bounds.fixed)
        assertEquals(1L, trail.state.activeSide(0, false)?.witness)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, true, ExactLpSide(low), 2L))
        assertEquals(1L, assertNotNull(trail.state.conflict).lower.witness)
        assertEquals(2L, assertNotNull(trail.state.conflict).upper.witness)
    }

    @Test
    fun `invalid and cancelled operations should leave the complete state unchanged`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(model)
        val before = trail.state
        var polls = 0
        val during = Cancellation { ++polls >= 3 }

        assertFalse(trail.assertBound(0, false, ExactLpSide(zero), 1L, during))

        assertSame(before, trail.state)
        assertEquals(3, polls)
        assertFalse(trail.assertBound(2, false, ExactLpSide(zero), 1L))
        assertFalse(trail.assertBound(0, false, ExactLpSide(zero), -1L))
        assertFalse(trail.pop(1))
        assertFalse(trail.recenter(emptyList()))
        assertFalse(trail.replaceObjective(ExactLpObjective(listOf(zero))))
        assertFalse(trail.push(Cancellation { true }))
        assertSame(before, trail.state)
    }

    @Test
    fun `objective replacement and recenter should persist through pop with source witnesses`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.of(8L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val trail = LpBoundTrail(model)
        val premise = ExactLpPremises(listOf(ExactLpPremise(42, false, ExactLpNumber.of(2L))))
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L), premises = premise), 1L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(4L)), 2L))
        assertTrue(trail.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(3L), zero))))

        assertTrue(trail.recenter(listOf(one)))
        assertTrue(trail.pop(0))

        assertEquals(one, trail.state.model.column(0).origin)
        assertEquals(one, trail.state.model.column(0).bounds.lower?.number)
        assertEquals(premise, trail.state.model.column(0).bounds.lower?.premises)
        assertEquals(ExactLpNumber.of(7L), trail.state.model.rhs(0))
        assertEquals(ExactLpNumber.of(3L), trail.state.model.objective.constant)
        assertEquals(ExactLpNumber.of(3L), trail.state.model.objective.cost(0))
        assertEquals(2L, trail.state.objectiveRevision)
    }

    @Test
    fun `IEEE assertions and premises should reject changed origins without erasing authority`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val sides = listOf(
            ExactLpSide(ExactLpNumber.ofIeee(0.5)),
            ExactLpSide(zero, premises = ExactLpPremises(listOf(ExactLpPremise(3, true, ExactLpNumber.ofIeee(0.5))))),
        )
        for (side in sides) {
            val trail = LpBoundTrail(model)
            assertTrue(trail.assertBound(0, false, side, 1L))
            val before = trail.state

            assertFalse(trail.recenter(listOf(ExactLpNumber.of(1L))))

            assertSame(before, trail.state)
            assertTrue(trail.recenter(listOf(zero)))
            assertEquals(side, trail.state.assertions.single().side)
        }
    }

    @Test
    fun `rejected batch adoption should leave staged witnesses available for retry`() {
        val source = assertNotNull(
            LpBuilder().apply { addVar(0, 10, cost = 1) }.build(Sense.MINIMIZE).authoritativeModel(),
        )
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
    fun `unprojectable donors should be repaired by the first accepted assertion`() {
        val zero = ExactLpNumber.of(0L)
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 2048, BIG_ONE))
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
    fun `ordered batches should retain equal and weaker witnesses through backjump`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).authoritativeModel())
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
    fun `the first conflicting prefix should retain exact strict witnesses and ignore a malformed tail`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).authoritativeModel())
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
    fun `cancelled preparation should leave every ordered assertion unpublished`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).authoritativeModel())
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
    fun `duplicate witnesses and revision overflow should reject the entire batch`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 10) }.build(Sense.MINIMIZE).authoritativeModel())
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
    fun `cancelled adoption should preserve the continuation target and successful adoption retire it`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addVar(0, 10, cost = 1)
                addRow(intArrayOf(0), longArrayOf(1), Relation.EQ, 1)
            }.build(Sense.MINIMIZE).authoritativeModel(),
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
    fun `failed numerical refresh should close the owner without publishing exact bounds`() {
        val source = assertNotNull(
            LpBuilder().apply { addVar(0, 10, cost = 1) }.build(Sense.MINIMIZE).authoritativeModel(),
        )
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
