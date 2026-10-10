package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RevisedSimplexResidualsTest {
    @Test
    fun `direct float solves retain source diagnostics`() {
        val model = LpBuilder().apply { addVar(0L, 2L, cost = 1L) }.build(Sense.MINIMIZE)

        RevisedSimplex(model).use { solver ->
            assertNotNull(solver.solve())
            assertEquals(1L, solver.lastMetrics.sourceResidualCalls)
        }
    }

    @Test
    fun `deferred diagnostics retain scaled source arithmetic validation`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 2L, cost = -1L)
            addRow(intArrayOf(x), longArrayOf(1_000_000L), Relation.LE, 1_500_000L)
        }.build(Sense.MINIMIZE)

        RevisedSimplex(model).use { solver ->
            solver.deferUnscaledSourceDiagnostics()
            val result = assertNotNull(solver.solve())

            assertEquals(1.5, result.primal.single(), 1e-9)
            assertTrue(solver.scalingMetrics.applied)
            assertEquals(1L, solver.lastMetrics.sourceResidualCalls)
            assertEquals(1L, solver.lastMetrics.sourceResidualScaledCalls)
        }
    }
}
