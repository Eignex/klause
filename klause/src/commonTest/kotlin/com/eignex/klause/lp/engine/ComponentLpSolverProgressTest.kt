package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentLpSolverProgressTest {
    @Test
    fun `paused numerical children expose only their returned predecessors`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel()))).use { owner ->
            var polls = 0
            owner.openSolve().use { cursor ->
                assertIs<LpSolveStep.Paused>(cursor.advance(shouldPause = {
                    owner.lastMetrics.componentSolves >= 2 && ++polls >= 2
                }))
                val paid = owner.lastMetrics

                val guidance = assertNotNull(owner.partialPrimalGuidance())

                assertEquals(listOf(true, false, false), guidance.known.toList())
                assertEquals(0.5, guidance.coordinates[0])
                assertEquals(paid, owner.lastMetrics)
            }
        }
    }

    @Test
    fun `paused child guidance snapshots own their coordinate buffers`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel()),
            factory = RecordingLpEngineFactory())).use { owner ->
            owner.openSolve().use { cursor ->
                assertIs<LpSolveStep.Paused>(cursor.advance(shouldPause = { owner.lastMetrics.componentSolves >= 2 }))
                val first = assertNotNull(owner.partialPrimalGuidance())
                val coordinates = first.coordinates.toList()
                val known = first.known.toList()
                first.coordinates.fill(5.0)
                first.known.fill(false)

                val next = assertNotNull(owner.partialPrimalGuidance())

                assertEquals(known, next.known.toList())
                assertEquals(coordinates, next.coordinates.toList())
                assertNull(owner.partialPrimalGuidance(ExecutionControl { true }))
            }
        }
    }

    @Test
    fun `partial guidance marks only returned child coordinates`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(1), longArrayOf(2L), Relation.GE, 1L)
        builder.addRow(intArrayOf(2), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel()))).use { owner ->
            assertNull(owner.resolveBounds(LpFloatAllowance(work = 0L, iterations = 2)))

            val guide = assertNotNull(owner.partialPrimalGuidance())

            assertEquals(listOf(false, true, true), guide.known.toList())
            assertTrue(guide.coordinates.filterIndexed { index, _ -> guide.known[index] }.all { it.isFinite() })
        }
    }

    @Test
    fun `partial guidance snapshots own their coordinate buffers`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(1), longArrayOf(2L), Relation.GE, 1L)
        builder.addRow(intArrayOf(2), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel()))).use { owner ->
            assertNull(owner.resolveBounds(LpFloatAllowance(work = 0L, iterations = 2)))
            val first = assertNotNull(owner.partialPrimalGuidance())
            val coordinates = first.coordinates.toList()
            val known = first.known.toList()
            first.coordinates.fill(5.0)
            first.known.fill(false)

            val next = assertNotNull(owner.partialPrimalGuidance())

            assertEquals(known, next.known.toList())
            assertEquals(coordinates, next.coordinates.toList())
        }
    }

    @Test
    fun `cancelled partial guidance withholds coordinates`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(1), longArrayOf(2L), Relation.GE, 1L)
        builder.addRow(intArrayOf(2), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel()))).use { owner ->
            assertNull(owner.resolveBounds(LpFloatAllowance(work = 0L, iterations = 2)))
            for (stopAt in listOf(1, 4)) {
                var polls = 0

                val guide = owner.partialPrimalGuidance(ExecutionControl { ++polls >= stopAt })

                assertNull(guide)
            }
        }
    }

    @Test
    fun `partial guidance is withheld after a bound authority edit`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(1), longArrayOf(2L), Relation.GE, 1L)
        builder.addRow(intArrayOf(2), longArrayOf(2L), Relation.GE, 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(trail.state.ownerWorkingModel()))).use { owner ->
            assertNull(owner.resolveBounds(LpFloatAllowance(work = 0L, iterations = 2)))
            assertNotNull(owner.partialPrimalGuidance())
            assertTrue(trail.assertBound(1, false, ExactLpSide(ExactLpNumber.of(1L)), 7L))

            assertTrue(owner.adopt(trail.state))

            assertNull(owner.partialPrimalGuidance())
        }
    }

    @Test
    fun `a completed logical child withholds a point on a strict boundary`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        builder.addRow(intArrayOf(), longArrayOf(), Relation.LE, 0L)
        val integer = builder.build(Sense.MINIMIZE)
        integer.rowStrict[2] = true
        val state = LpExactState(assertNotNull(integer.authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))

        owner.use {
            assertTrue(assertNotNull(it.solve()).optimal)

            assertNull(it.exactWitness())
        }
    }

    @Test
    fun `unfinished components require every minimizing bound side`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, null, cost = -1.0)
        builder.addVar(0L, 5L, cost = 1L)
        builder.addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.GE, 1.0)
        builder.addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.GE, 2.0)
        builder.addRow(intArrayOf(1), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))
        owner.use {
            assertNull(it.resolveBounds(LpFloatAllowance(work = 0L, iterations = 1)))

            val bound = it.exactBound()

            assertNull(bound)
        }
    }

    @Test
    fun `completed children supply one exact aggregate without a full point`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(1), longArrayOf(2L), Relation.GE, 1L)
        builder.addRow(intArrayOf(2), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))
        owner.use {
            val raw = it.resolveBounds(LpFloatAllowance(work = 0L, iterations = 2))
            val bound = assertNotNull(it.exactBound())

            assertNull(raw)
            assertEquals(LpFloatTermination.PIVOTS, it.lastTermination)
            assertEquals(BigFraction.ONE, bound.value)
            assertSame(state, assertNotNull(bound.support).state)
            assertNull(it.exactWitness())
            assertTrue(it.gomoryCuts(3).isEmpty())
        }
    }

    @Test
    fun `partial component bounds honor certificate refusal`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0L, 5L, cost = 1L) }
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(1), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))
        owner.use {
            assertNull(it.resolveBounds(LpFloatAllowance(work = 0L, iterations = 1)))

            val bound = it.exactBound(policy = LpCertificationPolicy { _, _ -> false })

            assertNull(bound)
        }
    }

    @Test
    fun `partial component adoption should retire every child and clear old proof evidence`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        var acquired = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: ExecutionControl,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val decline = acquired++ == 1
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: ExecutionControl): Boolean =
                        !decline && delegate.adopt(state, token)
                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
        }
        val owner = assertIs<RetainedComponentLpSolverCapability>(
            newRetainedLpSolver(
                assertNotNull(trail.state.ownerWorkingModel()),
                factory = factory,
            ),
        )
        assertNotNull(owner.solve())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
        assertTrue(trail.assertBound(1, false, ExactLpSide(ExactLpNumber.of(3L)), 8L))

        assertFalse(owner.adopt(trail.state))

        assertEquals(2, closed)
        assertNull(owner.solvedExactState)
        assertNull(owner.exactBound())
        assertFailsWith<IllegalStateException> { owner.resolveBounds() }
        owner.close()
        assertEquals(2, closed)
    }
}
