package com.eignex.klause.solver.result

import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LpCutStatsTest {
    @Test
    fun `a declined cut build should not report active rows`() {
        val sink = LpStatsSink()

        val built: Any? = sink.observeCutBuild(2) { null }

        assertNull(built)
        assertEquals(2.0, sink.snapshot().cutSelected.sum)
        assertEquals(NO_MAX, sink.snapshot().cutActive)
    }

    @Test
    fun `failed exact work should remain shared family cost without consumption`() {
        val sink = LpStatsSink()
        sink.cutLifecycle.families = setOf(CutFamily.GOMORY, CutFamily.MIR)

        sink.certificationObserver().observe(LpCertifier.RATIONAL, false, LpCertifierCost.Metered(19))

        val phases = sink.snapshot().phases
        for (family in listOf("GOMORY", "MIR")) {
            val cost = phases.getValue("CUT_NODE_${family}_CERTIFIER_RATIONAL")
            assertEquals(19L, cost.work)
            assertEquals(1L, cost.outcomes["DECLINED"])
            assertNull(phases["CUT_NODE_${family}_CHECKED_USE"])
        }
    }
}
