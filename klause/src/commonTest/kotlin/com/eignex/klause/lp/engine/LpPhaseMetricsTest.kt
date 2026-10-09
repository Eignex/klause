package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPhaseMetricsTest {
    @Test
    fun `an accepted degenerate optimum records exact dual recovery without cleanup or ladder work`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, null, cost = 1.0)
            val y = addRealVar(0.0, null, cost = 1.0)
            addRealRow(intArrayOf(x, y), doubleArrayOf(5.0, 5.0), Relation.GE, 1.0)
        }.build(Sense.MINIMIZE)
        val events = ArrayList<LpPhaseMetrics>()
        val observer = phaseObserver(events)

        val result = solveAndCertify(model, observer = observer, floatAccept = { true })

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, result.verdict)
        assertEquals(listOf(LpSolvePhase.AUTHORITATIVE_IMPORT, LpSolvePhase.EXACT_DUALS,
            LpSolvePhase.FLOAT_ACCEPTANCE), events.map { it.phase })
        assertEquals("ACCEPTED", events.last().outcome)
        assertEquals("ACCEPTED", events.single { it.phase == LpSolvePhase.EXACT_DUALS }.outcome)
        assertTrue(events.all { it.nanos >= 0L })
    }

    @Test
    fun `cleanup costs remain visible when acceptance falls through to the exact ladder`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, null, cost = -9e-13)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1e18)
        }.build(Sense.MINIMIZE)
        val events = ArrayList<LpPhaseMetrics>()

        val result = solveAndCertify(model, observer = phaseObserver(events), floatAccept = { true })

        assertNull(result.floatOptimum)
        val cleanup = events.single { it.phase == LpSolvePhase.CLEANUP }
        assertEquals("OPTIMAL_CANDIDATE", cleanup.outcome)
        assertEquals(0, cleanup.pivots)
        assertTrue(cleanup.work > 0L)
        assertEquals(result.verdict.name, events.single { it.phase == LpSolvePhase.EXACT_LADDER }.outcome)
    }

    @Test
    fun `a source refusal records the exact ladder verdict`() {
        val model = LpBuilder().apply { addRealVar(0.0, 2.0, cost = 1.0) }.build(Sense.MINIMIZE)
        val events = ArrayList<LpPhaseMetrics>()

        val result = solveAndCertify(model, observer = phaseObserver(events), floatAccept = { false })

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals("REFUSED", events.single { it.phase == LpSolvePhase.FLOAT_ACCEPTANCE }.outcome)
        assertEquals("ATTAINED_OPTIMUM", events.single { it.phase == LpSolvePhase.EXACT_LADDER }.outcome)
    }

    private fun phaseObserver(events: MutableList<LpPhaseMetrics>) = object : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
        override fun observeExactInput(accepted: Boolean) = Unit
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        override fun observePhase(metrics: LpPhaseMetrics) { events += metrics }
    }
}
