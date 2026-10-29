package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpSolveSessionTest {
    @Test
    fun `component cursors should reuse unchanged child results without resumable support`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 5.0, cost = 1.0)
        val y = builder.addRealVar(0.0, 5.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        builder.addRealRow(intArrayOf(y), doubleArrayOf(1.0), Relation.GE, 2.0)
        val model = assertNotNull(
            LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())).ownerWorkingModel(),
        )
        var cold = 0
        var retained = 0
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
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun solve(warm: Basis?): FloatLpResult? {
                        cold++
                        return delegate.solve(warm)
                    }

                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        retained++
                        return delegate.resolveBounds(allowance)
                    }
                }
            }
        }

        LpSolveSession(LpSolveContext(factory)).use { owner ->
            repeat(2) {
                owner.openSolve(model).use { cursor ->
                    val result = assertIs<LpSolveStep.Completed<CertifiedLpResult>>(cursor.advance()).value
                    assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
                }
            }
        }

        assertEquals(2, cold)
        assertEquals(0, retained)
    }

    @Test
    fun `paused solve work should be charged once before certification`() {
        for (split in listOf(false, true)) {
            val builder = LpBuilder()
            val x = builder.addRealVar(0.0, 5.0, cost = 1.0)
            val y = builder.addRealVar(0.0, 5.0, cost = 1.0)
            builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
            builder.addRealRow(intArrayOf(y), doubleArrayOf(1.0), Relation.GE, 2.0)
            val model = assertNotNull(
                LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())).ownerWorkingModel(),
            )
            val baseline = LpStatsSink(LpRoute.STANDALONE)
            val expected = LpSolveSession(componentSplit = split).use { owner ->
                owner.solve(model, observer = baseline.certificationObserver())
            }
            val sink = LpStatsSink(LpRoute.STANDALONE)

            LpSolveSession(componentSplit = split).use { owner ->
                owner.openSolve(model, observer = sink.certificationObserver()).use { cursor ->
                    var step = cursor.advance(1L)
                    assertIs<LpSolveStep.Paused>(step)
                    assertTrue(sink.standaloneWork > 0L)
                    assertEquals(0.0, sink.snapshot().standalonePasses.sum + sink.snapshot().componentPasses.sum)
                    var pauses = 0
                    while (step is LpSolveStep.Paused && pauses++ < 64) step = cursor.advance(1L)
                    val actual = assertIs<LpSolveStep.Completed<CertifiedLpResult>>(step).value

                    assertEquals(expected.verdict, actual.verdict)
                    assertEquals(baseline.standaloneWork, sink.standaloneWork)
                    assertEquals(baseline.snapshot().standalonePasses, sink.snapshot().standalonePasses)
                    assertEquals(baseline.snapshot().componentPasses, sink.snapshot().componentPasses)
                }
            }
        }
    }

    @Test
    fun `retained fractional equalities should reject a rounded product`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.1)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(0.1), Relation.EQ, 0.010000000000000002)
        val model = assertNotNull(
            LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel(),
        )
        val fresh = solveAndCertify(model)

        LpSolveSession().use { owner ->
            val actual = owner.solve(model)

            assertEquals(LpVerdict.INFEASIBLE, fresh.verdict)
            assertEquals(
                fresh.verdict,
                actual.verdict,
                "continuation=${actual.continuation}, refinement=${actual.refinement}",
            )
        }
    }

    @Test
    fun `retained solves should use a per call work allowance independent of construction`() {
        val model = assertNotNull(
            LpExactState(
                assertNotNull(
                    LpBuilder().apply {
                        val x = addRealVar(0.0, 5.0, cost = 1.0)
                        addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
                    }.build(Sense.MINIMIZE).authoritativeModel(),
                ),
            ).ownerWorkingModel(),
        )
        val allowances = ArrayList<LpFloatAllowance?>()
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
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        allowances += allowance
                        return delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        LpSolveSession(LpSolveContext(factory)).use { owner ->
            owner.solve(model, workLimit = 1L)
            val actual = owner.solve(model, workLimit = 1000L)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, actual.verdict)
            assertNotNull(actual.float)
            owner.solve(model, workLimit = 1L)
        }

        assertEquals(listOf<LpFloatAllowance?>(LpFloatAllowance(1000L, 0), LpFloatAllowance(1L, 0)), allowances)
    }

    @Test
    fun `solve failures should preserve the primary error and retire owned factors`() {
        val model = assertNotNull(
            LpExactState(
                assertNotNull(
                    LpBuilder().apply {
                        val x = addRealVar(0.0, 2.0)
                        addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
                    }.build(Sense.MINIMIZE).authoritativeModel(),
                ),
            ).ownerWorkingModel(),
        )
        val primary = IllegalArgumentException("solve")
        val cleanup = IllegalStateException("close")
        var closed = 0
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
                    override fun solve(warm: Basis?): FloatLpResult? = throw primary
                    override fun close() {
                        closed++
                        delegate.close()
                        throw cleanup
                    }
                }
            }
        }
        val owner = LpSolveSession(LpSolveContext(factory))

        val failure = assertFailsWith<IllegalArgumentException> { owner.solve(model) }
        owner.close()

        assertSame(primary, failure)
        assertEquals(listOf(cleanup), failure.suppressedExceptions)
        assertEquals(1, closed)
    }

    @Test
    fun `retained solves should match fresh proofs across bounds and pops`() {
        for (split in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(2) {
                val x = builder.addRealVar(0.0, 5.0, cost = 1.0)
                builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
            }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val factory = RecordingLpEngineFactory()
            LpSolveSession(LpSolveContext(factory), componentSplit = split).use { owner ->
                assertEquals(
                    BigFraction.ofLong(2L),
                    owner.solve(assertNotNull(trail.state.ownerWorkingModel())).lowerBound,
                )
                assertTrue(trail.push())
                assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
                for (depth in listOf(1, 0)) {
                    if (depth < 1) assertTrue(trail.pop(depth))
                    val model = assertNotNull(trail.state.ownerWorkingModel())
                    val actual = owner.solve(model)
                    val fresh = solveAndCertify(model, componentSplit = split)

                    assertEquals(fresh.verdict, actual.verdict)
                    assertEquals(fresh.lowerBound, actual.lowerBound)
                    assertEquals(fresh.exactPrimal, actual.exactPrimal)
                    assertSame(trail.state, assertNotNull(actual.float).exactState)
                    assertEquals(0, actual.float.refactorizations)
                    assertTrue(actual.float.warmStarted)
                }
            }
            assertEquals(if (split) 2 else 1, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
            assertEquals(if (split) 1 else 0, factory.calls.count { it.kind == EngineConstruction.COMPONENT })
            assertEquals(0, factory.calls.count { it.kind == EngineConstruction.GENERAL })
        }
    }

    @Test
    fun `release should retire factors and permit a fresh numerical lifetime`() {
        val model = assertNotNull(
            LpExactState(
                assertNotNull(
                    LpBuilder().apply {
                        val x = addRealVar(0.0, 2.0, cost = 1.0)
                        addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
                    }.build(Sense.MINIMIZE).authoritativeModel(),
                ),
            ).ownerWorkingModel(),
        )
        var opened = 0
        var closed = 0
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
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                opened++
                return object : PersistentLpSolver by delegate {
                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
        }
        val owner = LpSolveSession(LpSolveContext(factory))
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, owner.solve(model).verdict)
        owner.releaseSolvers()
        assertEquals(1, closed)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, owner.solve(model).verdict)
        owner.close()
        owner.close()

        assertEquals(2, opened)
        assertEquals(opened, closed)
        assertFailsWith<IllegalStateException> { owner.solve(model) }
    }
}
