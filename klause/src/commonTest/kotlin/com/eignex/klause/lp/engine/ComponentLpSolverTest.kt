package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentLpSolverTest {
    @Test
    fun `a rounded component optimum preserves its exact fractional contradiction`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        val y = builder.addRealVar(0.0, 3.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.1)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(0.1), Relation.EQ, 0.010000000000000002)
        builder.addRealRow(intArrayOf(y), doubleArrayOf(1.0), Relation.GE, 1.0)
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())

        val result = assertIs<RetainedComponentLpSolverCapability>(newRetainedLpSolver(model)).use { solver ->
            val raw = solver.solve()
            certifyLpResult(model, solver, raw)
        }

        assertEquals(LpVerdict.INFEASIBLE, result.verdict, "continuation=${result.continuation}")
        assertTrue(assertNotNull(result.conflictSupport).rows.any { it.first == 1 })
    }

    @Test
    fun `retained components preserve fresh exact bounds across nested scopes and objective changes`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val factory = RecordingLpEngineFactory()
        val owner = assertIs<RetainedComponentLpSolverCapability>(newRetainedLpSolver(
            assertNotNull(trail.state.ownerWorkingModel()), factory = factory,
        ))
        owner.use {
            assertNotNull(it.solve())
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
            assertTrue(trail.push())
            assertTrue(trail.assertBound(1, true, ExactLpSide(ExactLpNumber.of(4L)), 8L))
            for (depth in listOf(2, 1, 0)) {
                if (depth < 2) assertTrue(trail.pop(depth))
                val state = trail.state
                assertTrue(it.adopt(state))
                val raw = assertNotNull(it.resolveBounds())
                val retained = certifyLpResult(assertNotNull(state.ownerWorkingModel()), it, raw)
                val fresh = solveAndCertify(assertNotNull(state.ownerWorkingModel()))
                assertEquals(fresh.lowerBound, retained.lowerBound)
                assertEquals(fresh.verdict, retained.verdict)
                assertSame(state, raw.exactState)
                assertEquals(0, raw.refactorizations)
                assertTrue(raw.warmStarted)
            }
            val zero = ExactLpNumber.of(0L)
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero, zero, zero))))
            assertTrue(it.adopt(trail.state))
            val raw = assertNotNull(it.resolveBounds())
            assertEquals(BigFraction.ofLong(-5L), assertNotNull(it.exactBound()).value)
            assertEquals(0, raw.refactorizations)
        }
        assertEquals(2, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.COMPONENT })
    }

    @Test
    fun `partial component adoption retires every child and clears old proof evidence`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        var acquired = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean,
                pricing: LpPricingOptions): PersistentLpSolver {
                val decline = acquired++ == 1
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation, refactorUpdateLimit,
                    iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !decline && delegate.adopt(state, token)
                    override fun close() { closed++; delegate.close() }
                }
            }
        }
        val owner = assertIs<RetainedComponentLpSolverCapability>(newRetainedLpSolver(
            assertNotNull(trail.state.ownerWorkingModel()), factory = factory,
        ))
        assertNotNull(owner.solve())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))

        assertFalse(owner.adopt(trail.state))

        assertEquals(2, closed)
        assertNull(owner.solvedExactState)
        assertNull(owner.exactBound())
        assertFailsWith<IllegalStateException> { owner.resolveBounds() }
        owner.close()
        assertEquals(2, closed)
    }
    @Test
    fun `a component contradiction certifies against the complete exact row coordinates`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        val y = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertTrue(assertNotNull(result.conflictSupport).rows.any { it.first == 1 })
    }

    @Test
    fun `an unbounded isolated objective retains an exact source direction`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 3L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val isolated = builder.addFreeVar(4L, null, cost = -1L)
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        assertTrue(assertNotNull(result.unboundedness).direction[isolated] > BigFraction.ZERO)
    }

    @Test
    fun `inactive component rows remain free and cannot supply certificate premises`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        val y = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 4L, global = false,
            premises = LpRowPremises(intArrayOf(7), booleanArrayOf(false), longArrayOf(4L)))
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val state = LpExactState(source, rows = LpScopedRows.initial(2).deactivate(setOf(0)))
        val model = assertNotNull(state.ownerWorkingModel())

        val result = assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            val raw = assertNotNull(solver.solve())
            val support = assertNotNull(assertNotNull(solver.exactBound()).support)
            assertTrue(support.rows.none { it.first == 0 })
            assertTrue(support.sides.none { it.column == model.n })
            certifyLpResult(model, solver, raw)
        }

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(2L), result.lowerBound)
        assertEquals(BigFraction.ZERO, result.exactPrimal?.get(x))
    }

    @Test
    fun `zero cost isolated columns receive a witness inside strict one sided bounds`() {
        val one = ExactLpNumber.of(1L)
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), listOf(ExactLpEntry(1, one)), emptyList()),
            listOf(one, one),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(one, strict = true))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow(), ExactLpRow()), ExactLpObjective(List(5) { zero }),
        )
        val model = assertNotNull(LpExactState(source).ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(2, result.float?.blocks)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertTrue(assertNotNull(result.exactPrimal)[2] > one.value)
    }
}
