package com.eignex.klause.lp.bounding

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.ProductionLpCertificationPolicy
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.nodeDualCertificate
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LpCutAttributionTest {
    @Test
    fun `a stronger safe bound should use its own rows instead of a weaker certificate`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 4)), emptyArray())
        val model = LpBuilder().apply {
            addVar(0L, 4L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val map = CutSourceMap(problem, 0L, listOf(null), parentRows = mapOf(
            0 to CutProvenance(problem, 0L, emptyList(), family = CutFamily.GOMORY),
            1 to CutProvenance(problem, 0L, emptyList(), family = CutFamily.MIR),
        ))
        val relaxation = LpRelaxation(model, intArrayOf(0), BooleanArray(1), 0L, intArrayOf(0), intArrayOf(),
            sourceMap = map)
        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(-1.0, 0.0)))
        assertEquals(BigFraction.ONE, certificate.exactObjectiveBound())
        val duals = doubleArrayOf(0.0, -1.0)
        val sink = SolveStatsSink(backend = "cut-attribution")
        val lower = assertNotNull(certifiedTightObjectiveLowerBound(model, duals, certificate,
            sink.lp.certificationObserver(), ProductionLpCertificationPolicy))
        assertEquals(2.0, lower)

        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)), LpParams(), sink).use { engine ->
            engine.observeAcceptedCutBoundUse(relaxation, certificate, duals,
                assertNotNull(BigFraction.ofDouble(lower)), "BOUND_PRUNE")
        }

        val phases = sink.lp.snapshot().phases
        assertNull(phases["CUT_NODE_GOMORY_CHECKED_USE"])
        assertEquals(1L, phases.getValue("CUT_NODE_MIR_CHECKED_USE").outcomes["SAFE_BOUND_PRUNE"])
    }
}
