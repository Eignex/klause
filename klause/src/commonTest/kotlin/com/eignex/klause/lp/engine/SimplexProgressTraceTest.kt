package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
