package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LpTreeSearchDualizationTest {
    @Test
    fun `a failed root charges completed dual preparation once to its parent`() {
        val sink = SolveStatsSink(backend = "dual-parent")
        val engine = LpEngine(Problem(0, 0, emptyArray(), emptyArray()), LinearObjective(), LpParams(), sink)

        engine.use {
            assertNull(it.solveRootNodeWithCrash(null, { null }, { error("no solve owner") }, { LpSolveMetrics(workOps = 41L) }))
        }

        assertEquals(41L, engine.totalSolveWork())
        assertEquals(0L, engine.pendingNodeSolveWork())
        assertEquals(41.0, sink.snapshot().lp.rootWorkOps.sum)
    }

    @Test
    fun `throwing auxiliary work is accounted without masking the failure`() {
        val observed = ArrayList<Long>()

        val failure = assertFailsWith<IllegalStateException> {
            solveRootNodeWithCrash(null, { error("auxiliary failure") }, { error("no owner") }, { _, metrics -> observed += metrics.workOps }, { LpSolveMetrics(workOps = 23L) })
        }

        assertEquals("auxiliary failure", failure.message)
        assertEquals(listOf(23L), observed)
    }

    @Test
    fun `successful root combines auxiliary and source work once`() {
        val observed = ArrayList<Long>()
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = null
            override fun solvePrimal(warm: Basis?) = null
        }

        solveRootNodeWithCrash(null, { solver to null }, { LpSolveMetrics(workOps = 17L) }, { _, metrics -> observed += metrics.workOps }, { LpSolveMetrics(workOps = 23L) })

        assertEquals(listOf(40L), observed)
    }

}
