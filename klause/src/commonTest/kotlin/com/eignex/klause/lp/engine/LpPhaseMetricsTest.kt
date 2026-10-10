package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPhaseMetricsTest {
    @Test
    fun `an accepted source optimum allocates no authoritative state`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 2.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        }.build(Sense.MINIMIZE)
        val events = ArrayList<LpPhaseMetrics>()
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = solveAndCertify(model, observer = phaseObserver(events), context = vetoed, floatAccept = { true })

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, result.verdict)
        assertNull(assertNotNull(result.floatOptimum).exactState)
        assertEquals(listOf(LpSolvePhase.SOURCE_FLOAT_ACCEPTANCE), events.map { it.phase })
        assertEquals("ACCEPTED", events.single().outcome)
    }

    @Test
    fun `a source coefficient outside exact binary64 range imports authority before accepting`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 2L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(9_007_199_254_740_993L), Relation.GE, 9_007_199_254_740_993L)
        }.build(Sense.MINIMIZE)
        val events = ArrayList<LpPhaseMetrics>()

        val result = solveAndCertify(model, observer = phaseObserver(events), floatAccept = { true })

        assertEquals(LpSolvePhase.AUTHORITATIVE_IMPORT, events.first().phase)
        assertNotNull(assertNotNull(result.float).exactState)
        assertTrue(events.none { it.phase == LpSolvePhase.SOURCE_FLOAT_ACCEPTANCE })
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
    fun `cancellation during source acceptance withholds the optimum without importing authority`() {
        val model = LpBuilder().apply { addRealVar(0.0, 2.0, cost = 1.0) }.build(Sense.MINIMIZE)
        val events = ArrayList<LpPhaseMetrics>()
        var cancelled = false

        val result = solveAndCertify(model, cancellation = Cancellation { cancelled },
            observer = phaseObserver(events), floatAccept = { cancelled = true; true })

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertTrue(events.none { it.phase == LpSolvePhase.AUTHORITATIVE_IMPORT })
    }

    private fun phaseObserver(events: MutableList<LpPhaseMetrics>) = object : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
        override fun observeExactInput(accepted: Boolean) = Unit
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        override fun observePhase(metrics: LpPhaseMetrics) { events += metrics }
    }
}
