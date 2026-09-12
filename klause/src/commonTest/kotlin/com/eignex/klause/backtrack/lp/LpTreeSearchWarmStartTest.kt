package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CrashBasisAttempt
import com.eignex.klause.lp.engine.CrashBasisDecline
import com.eignex.klause.lp.engine.CrashBasisMetrics
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LpTreeSearchWarmStartTest {
    private val solver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override fun solve(warm: Basis?) = null
        override fun solvePrimal(warm: Basis?) = null
    }

    @Test
    fun `the root crash budget is bounded within node work`() {
        assertEquals(100_000L, rootCrashWorkLimit(0L))
        assertEquals(10L, rootCrashWorkLimit(80L))
        assertEquals(1L, rootCrashWorkLimit(1L))
        assertEquals(100_000L, rootCrashWorkLimit(Long.MAX_VALUE))
    }

    @Test
    fun `a declined root solve still charges crash construction`() {
        val crash = CrashBasisAttempt(
            null,
            CrashBasisMetrics(
                workOps = 37L,
                candidates = 1,
                selected = 0,
                passes = 1,
                decline = CrashBasisDecline.RESOURCE_LIMIT,
            ),
        )
        val observed = ArrayList<LpSolveMetrics>()

        val result = solveRootNodeWithCrash(
            crash,
            solve = { null },
            solveMetrics = { error("a declined solve has no metrics owner") },
            observe = { _, metrics -> observed += metrics },
        )

        assertNull(result)
        assertEquals(listOf(37L), observed.map { it.workOps })
    }

    @Test
    fun `a throwing root solve still charges crash construction`() {
        val crash = CrashBasisAttempt(
            null,
            CrashBasisMetrics(41L, 1, 0, 1, CrashBasisDecline.CANCELLED),
        )
        val observed = ArrayList<LpSolveMetrics>()

        assertFailsWith<IllegalStateException> {
            solveRootNodeWithCrash(
                crash,
                solve = { error("injected solve failure") },
                solveMetrics = { error("a throwing solve has no metrics owner") },
                observe = { _, metrics -> observed += metrics },
            )
        }

        assertEquals(listOf(41L), observed.map { it.workOps })
    }

    @Test
    fun `a completed root solve charges crash construction once`() {
        val crash = CrashBasisAttempt(null, CrashBasisMetrics(37L, 1, 1, 1))
        val observed = ArrayList<LpSolveMetrics>()

        solveRootNodeWithCrash(
            crash,
            solve = { solver to null },
            solveMetrics = { LpSolveMetrics(workOps = 11L) },
            observe = { _, metrics -> observed += metrics },
        )

        assertEquals(listOf(48L), observed.map { it.workOps })
    }

    @Test
    fun `a declined root solve charges parent root work only`() {
        val sink = SolveStatsSink(backend = "crash-parent")
        val engine = LpEngine(
            Problem(0, 0, emptyArray(), emptyArray()),
            LinearObjective(),
            LpParams(),
            sink,
        )
        val crash = CrashBasisAttempt(
            null,
            CrashBasisMetrics(43L, 1, 0, 1, CrashBasisDecline.RESOURCE_LIMIT),
        )

        val result = engine.use {
            it.solveRootNodeWithCrash(
                crash,
                solve = { null },
                solveMetrics = { error("a declined solve has no metrics owner") },
            )
        }

        assertNull(result)
        assertEquals(43L, engine.totalSolveWork())
        assertEquals(0L, engine.pendingNodeSolveWork())
        assertEquals(43.0, sink.snapshot().lp.rootWorkOps.sum)
    }
}
