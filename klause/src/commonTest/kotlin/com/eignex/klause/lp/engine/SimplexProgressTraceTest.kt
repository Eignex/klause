package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimplexProgressTraceTest {
    @Test
    fun `trace should distinguish feasibility and objective iterations`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        RevisedSimplex(builder.build(Sense.MINIMIZE)).use { solver ->
            solver.enableProgressTrace()

            val result = assertNotNull(solver.solvePrimal())

            assertEquals(1.0, result.objective, 1e-9)
            val samples = assertNotNull(solver.progressTrace).samples
            assertTrue(samples.first { it.phase == SimplexPhase.PRIMAL_FEASIBILITY }.scaledInfeasibility > 0.0)
            assertEquals(0.0, samples.first { it.phase == SimplexPhase.PRIMAL_OBJECTIVE }.scaledInfeasibility)
        }
    }

    @Test
    fun `trace should bound samples while counting all iterations`() {
        val trace = SimplexProgressTrace()

        repeat(5000) {
            if (trace.iteration(SimplexPhase.PRIMAL_FEASIBILITY)) {
                trace.samples.add(SimplexProgressSample(SimplexPhase.PRIMAL_FEASIBILITY, it, 0L,
                    0.0, 0.0, 0.0, 0))
            }
        }

        assertEquals(256, trace.samples.size)
        assertEquals(5000, trace.iterations[SimplexPhase.PRIMAL_FEASIBILITY])
    }

    @Test
    fun `profiling should preserve the certified result and charged work`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addVar(0L, 10L, cost = 2L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 4L)
        }.build(Sense.MINIMIZE)
        val control = RevisedSimplex(model).use { solver ->
            assertNotNull(solver.solve()) to solver.lastMetrics
        }

        RevisedSimplex(model).use { solver ->
            solver.enableProgressTrace(profile = true)
            val result = assertNotNull(solver.solve())

            assertEquals(control.first.objective, result.objective)
            assertEquals(control.second.workOps, solver.lastMetrics.workOps)
            assertEquals(control.first.primal.toList(), result.primal.toList())
            assertEquals(integerDualLowerBoundCeil(model, control.first.duals),
                integerDualLowerBoundCeil(model, result.duals))
            val trace = assertNotNull(solver.progressTrace)
            for (phase in listOf(SimplexProfilePhase.PRICING, SimplexProfilePhase.FTRAN,
                SimplexProfilePhase.BTRAN, SimplexProfilePhase.REFACTOR, SimplexProfilePhase.OUTPUT)) {
                assertTrue(trace.phaseCalls.getValue(phase) > 0L)
                assertTrue(trace.phaseNanos.getValue(phase) >= 0L)
            }
        }
    }

    @Test
    fun `ordinary progress capture should perform no phase timing`() {
        val model = LpBuilder().apply { addVar(0L, 1L, cost = 1L) }.build(Sense.MINIMIZE)
        RevisedSimplex(model).use { solver ->
            solver.enableProgressTrace()

            assertNotNull(solver.solve())

            assertTrue(assertNotNull(solver.progressTrace).phaseNanos.isEmpty())
        }
    }

    @Test
    fun `primal repair should report its feasibility scans`() {
        val model = LpBuilder().apply {
            addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        RevisedSimplex(model).use { solver ->
            solver.enableProgressTrace(profile = true)

            val result = assertNotNull(solver.solvePrimal())

            assertEquals(1.0, result.objective, 1e-9)
            val phases = assertNotNull(solver.progressTrace).phaseCalls
            assertTrue(phases.getValue(SimplexProfilePhase.FEASIBILITY_REPAIR) > 0L)
        }
    }

    @Test
    fun `profiled retained bound and row edits should preserve cold certificates`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addVar(0L, 10L, cost = 2L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 4L)
        }.build(Sense.MINIMIZE)
        val engines = mutableListOf<RevisedSimplex>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean,
                pricing: LpPricingOptions): PersistentLpSolver =
                RevisedSimplex(model, cancellation, refactorUpdateLimit = refactorUpdateLimit,
                    iterationLimit = iterationLimit, workLimit = workLimit, pricing = pricing).also {
                    it.enableProgressTrace(profile = true)
                    engines.add(it)
                }
        }
        LpScopedSolver(LpExactState(requireNotNull(model.authoritativeModel())),
            context = LpSolveContext(engineFactory = factory)).use { owner ->
            for (stage in 0..4) {
                when (stage) {
                    1 -> {
                        assertTrue(owner.push())
                        assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(5L)), 1L))
                    }
                    2, 4 -> assertTrue(owner.pop(0))
                    3 -> {
                        assertTrue(owner.push())
                        assertTrue(owner.append(LpScopedRow(1L,
                            listOf(0 to ExactLpNumber.of(-1L), 1 to ExactLpNumber.of(-1L)),
                            ExactLpNumber.of(-6L),
                            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))), true))
                    }
                }

                val retained = assertNotNull(owner.solve())
                val cold = LpScopedSolver(owner.state).use { assertNotNull(it.solve()) }

                assertEquals(cold.verdict, retained.verdict)
                assertEquals(cold.lowerBound, retained.lowerBound)
                assertNotNull(retained.exactPrimal)
                assertTrue(assertNotNull(engines.last().progressTrace).phaseCalls.isNotEmpty())
            }
        }
    }

    @Test
    fun `profiling should preserve cancellation without publishing a candidate`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 4L)
        }.build(Sense.MINIMIZE)
        RevisedSimplex(model, Cancellation { true }).use { solver ->
            solver.enableProgressTrace(profile = true)

            assertNull(solver.solve())

            assertEquals(LpFloatTermination.CANCELLED, solver.lastTermination)
            assertNotNull(solver.progressTrace)
        }
    }
}
