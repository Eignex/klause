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
    fun `certified unscaled optima omit redundant source scans`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 2.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        }.build(Sense.MINIMIZE)
        var scans = 0L
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) {
                scans += metrics.sourceResidualCalls
            }
        }

        val result = solveAndCertify(model, observer = observer)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(0L, scans)
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
