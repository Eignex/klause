package com.eignex.klause.cli

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliInvocationStatsTest {
    @Test
    fun `unmeasured frontend phases and backend remain absent`() {
        val pairs = cliInvocationStats(CommonOptions(), SolvablePipeline.Refuted).pairs(SolveStats.EMPTY).toMap()

        assertEquals(mapOf("solvePipeline" to "refuted"), pairs)
    }

    @Test
    fun `interrupted source admission reports load without routing or a solve backend`() {
        val common = CommonOptions().apply { loadElapsedMs = 7L }
        val pipeline = SolvablePipeline.Interrupted(TerminationReason.Timeout)

        val pairs = cliInvocationStats(common, pipeline).pairs(SolveStats.EMPTY).toMap()

        assertEquals(mapOf("frontendLoadMs" to "7", "solvePipeline" to "interrupted"), pairs)
    }

    @Test
    fun `routing snapshots retain measured zero and stay owned by their invocation`() {
        val common = CommonOptions().apply {
            loadElapsedMs = 7L
            logRoute(SourceProblemRoute.Refuted, 0L)
        }
        val first = cliInvocationStats(common, SolvablePipeline.Refuted)

        common.resetInvocationStatistics()
        val second = cliInvocationStats(common, SolvablePipeline.FiniteCp)

        assertEquals("7", first.pairs(SolveStats.EMPTY).toMap()["frontendLoadMs"])
        assertEquals("0", first.pairs(SolveStats.EMPTY).toMap()["frontendRoutingMs"])
        assertEquals("refuted", first.pairs(SolveStats.EMPTY).toMap()["frontendRoutingOutcome"])
        assertEquals(mapOf("solvePipeline" to "finite-cp"), second.pairs(SolveStats.EMPTY).toMap())
    }

    @Test
    fun `declared theory and reported backend stay distinct from the selected pipeline`() {
        val model = Problem(
            0, 0, emptyArray(), emptyList(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val pipeline = SolvablePipeline.OpenTheory(OpenTheoryRequest(model)) { "" }

        val pairs = cliInvocationStats(CommonOptions(), pipeline)
            .pairs(SolveStats(run = RunStats(backend = "mixed"))).toMap()

        assertEquals("open-theory", pairs["solvePipeline"])
        assertEquals("exact_lra", pairs["declaredTheoryRoute"])
        assertEquals("mixed", pairs["reportedBackend"])
    }

    @Test
    fun `open local search does not acquire a declared complete theory`() {
        val model = Problem(0, 0, emptyArray(), emptyList())
        val pipeline = SolvablePipeline.OpenLocalSearch(model, { "" })

        val pairs = cliInvocationStats(CommonOptions(), pipeline).pairs(SolveStats.EMPTY).toMap()

        assertEquals(mapOf("solvePipeline" to "open-local-search"), pairs)
    }

    @Test
    fun `open search deductions alone do not imply native CP phase measurements`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira"),
            search = SearchStats(propagations = SumResult(13.0)),
        )

        val pairs = cliInvocationStats(CommonOptions(), SolvablePipeline.FiniteCp).pairs(stats).toMap()

        assertFalse(pairs.keys.any { it.startsWith("nativeCp") })
    }

    @Test
    fun `native CP phases preserve their aggregate and root subset scopes`() {
        val stats = SolveStats(search = SearchStats(
            propagationWork = SumResult(17.0), rootPropagationWork = SumResult(5.0),
            propagationNanos = SumResult(3_500_000.0), rootPropagationNanos = SumResult(500_000.0),
        ))

        val pairs = cliInvocationStats(CommonOptions(), SolvablePipeline.FiniteCp).pairs(stats).toMap()

        assertEquals("17", pairs["nativeCpPropagationWork"])
        assertEquals("5", pairs["nativeCpRootPropagationWork"])
        assertEquals("3", pairs["nativeCpPropagationMs"])
        assertEquals("0", pairs["nativeCpRootPropagationMs"])
    }
}
