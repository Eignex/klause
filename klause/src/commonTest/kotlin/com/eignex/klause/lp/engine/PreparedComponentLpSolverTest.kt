package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedComponentLpSolverTest {
    @Test
    fun `logical preparation should preserve original row order without certifying a point`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L)
        val y = builder.addVar(0L, 5L)
        builder.addVar(0L, 7L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 4L)
        builder.addRow(intArrayOf(), longArrayOf(), Relation.EQ, 0L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())

        assertIs<PreparedComponentLpSolver>(newPreparedLpSolver(model)).use { owner ->
            val basis = assertNotNull(owner.prepareLogicals())

            assertContentEquals(IntArray(model.m) { model.n + it }, basis.basicVars)
            assertTrue(basis.validFor(state.model))
            assertNull(owner.solvedExactState)
            assertNull(owner.exactWitness())
            assertNull(owner.exactBound())
        }
    }

    @Test
    fun `a permuted warm heading should retain every global basis coordinate`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        val warm = Basis(
            intArrayOf(3, 2), arrayOf(VarStatus.AT_LOWER, VarStatus.AT_LOWER, VarStatus.BASIC, VarStatus.BASIC),
        )

        assertIs<PreparedComponentLpSolver>(newPreparedLpSolver(model)).use { owner ->
            val prepared = assertNotNull(owner.prepareBasis(warm))

            assertContentEquals(warm.basicVars, prepared.basicVars)
            assertTrue(prepared.validFor(state.model))
            val raw = assertNotNull(owner.resolveBounds())
            assertTrue(raw.basis.validFor(state.model))
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, certifyLpResult(model, owner, raw).verdict)
        }
    }

    @Test
    fun `isolated objective columns should retain their exact contribution`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val isolated = builder.addVar(0L, 7L, cost = -2L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val result = assertNotNull(owner.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(BigFraction.ofLong(-12L), result.lowerBound)
            assertEquals(BigFraction.ofLong(7L), result.exactPrimal?.get(isolated))
            assertIs<ComponentLpSolverCapability>(assertNotNull(owner.solveFloat()).first)
        }
    }

    @Test
    fun `an impossible empty row should retain an exact refutation`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        builder.addRow(intArrayOf(), longArrayOf(), Relation.EQ, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))

        LpScopedSolver(state).use { owner ->
            val result = assertNotNull(owner.solve())

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        }
    }

    @Test
    fun `appended rows should retract and compact without changing the remaining optimum`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 10L, cost = 1L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val row = LpScopedRow(
            2L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-4L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
        )

        LpScopedSolver(state).use { owner ->
            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            assertTrue(owner.append(row, true))
            assertEquals(BigFraction.ofLong(5L), assertNotNull(owner.solve()).lowerBound)

            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(3, owner.state.rows.size)
            assertEquals(2, owner.state.rows.activeCount)
            assertTrue(owner.compact())
            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(2, owner.state.rows.size)
            assertEquals(1L, owner.metrics.currentOwners)
        }
    }

    @Test
    fun `a declined child adoption should preserve the trail and rebuild a complete owner`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        var acquired = 0
        var reject = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val declines = acquired++ == 1
                val child = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : PersistentLpSolver by child {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !(reject && declines) && child.adopt(state, token)
                }
            }
        }

        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            val previous = owner.state
            reject = true

            assertFalse(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))

            assertSame(previous, owner.state)
            reject = false
            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(2L, owner.metrics.createdOwners)
            assertEquals(1L, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `exhausted work should stop before starting another component`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        var calls = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val child = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                var charged: Long? = null
                return object : PersistentLpSolver by child {
                    override val lastWorkOps: Long get() = charged ?: child.lastWorkOps
                    override val lastMetrics: LpSolveMetrics get() = child.lastMetrics.copy(workOps = lastWorkOps)
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        calls++
                        charged = assertNotNull(allowance).work
                        return child.resolveBounds(LpFloatAllowance(0L, 100))
                    }
                }
            }
        }

        assertIs<PreparedComponentLpSolver>(newPreparedLpSolver(model, factory = factory)).use { owner ->
            assertNotNull(owner.prepareLogicals())
            val result = owner.resolveBounds(LpFloatAllowance(10L, 100))

            assertNull(result)
            assertEquals(1, calls)
            assertEquals(10L, owner.lastMetrics.workOps)
            assertEquals(LpFloatTermination.WORK, owner.lastTermination)
            assertNull(owner.exactWitness())
        }
    }

    @Test
    fun `exhausted iterations should not become a size derived child allowance`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        var calls = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val child = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                var used = 0L
                return object : PersistentLpSolver by child {
                    override val lastIterations: Long get() = used
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        calls++
                        used = assertNotNull(allowance).iterations.toLong()
                        return child.resolveBounds(LpFloatAllowance(0L, 100))
                    }
                }
            }
        }

        assertIs<PreparedComponentLpSolver>(newPreparedLpSolver(model, factory = factory)).use { owner ->
            assertNotNull(owner.prepareLogicals())
            assertNull(owner.resolveBounds(LpFloatAllowance(0L, 3)))

            assertEquals(1, calls)
            assertEquals(3L, owner.lastIterations)
            assertEquals(LpFloatTermination.PIVOTS, owner.lastTermination)
        }
    }

    @Test
    fun `a cancelled partition should not publish a partial numerical owner`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val factory = RecordingLpEngineFactory()
        var polls = 0
        val token = Cancellation { ++polls >= 8 }

        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            assertFalse(owner.prepare(token))

            assertEquals(0L, owner.metrics.currentOwners)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `failed child construction should close every acquired numerical engine`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        var acquired = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                check(acquired++ == 0) { "construction failure" }
                val child = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : PersistentLpSolver by child {
                    override fun close() {
                        closed++
                        child.close()
                    }
                }
            }
        }

        assertFailsWith<IllegalStateException> { newPreparedLpSolver(model, factory = factory) }

        assertEquals(1, closed)
    }

    @Test
    fun `working components should restore the source objective after closing`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val negative = ExactLpNumber.of(-1L)
        val zero = ExactLpNumber.of(0L)

        LpScopedSolver(state).use { owner ->
            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            val working = LpWorkingModel.overrides(
                owner.state, ExactLpObjective(listOf(negative, negative, zero, zero)),
            )
            owner.withWorkingModel(working) { scope ->
                assertEquals(BigFraction.ofLong(-10L), assertNotNull(scope.solve()).lowerBound)
            }

            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.currentOwners)
            assertNotNull(owner.lastWorkingMetrics)
        }
    }
}
