package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SourceValidationRoutingTest {
    @Test
    fun `MPS opt in should check raw cached witnesses without changing the solver budget or parameters`() {
        val ref = ProblemRef("original-decimal-cancellation", Format.MPS,
            corpus("mps-linear-source-v1/original-decimal-cancellation.mps"), Category.OPTIMIZATION, Expected.Unknown)
        val entry = ResolvedProblem(ref, lazy { error("the checker must use the original source") })
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true", "arm=cp"))
        val raw = "o 1\ns SATISFIABLE\nv I=1 X=1\n"
        val tag = UUID.randomUUID().toString()
        var checks = 0
        var invocations = 0

        repeat(2) {
            val (checked, retained) = SolveMetric.solve(entry, SolverInvocation.KLAUSE, settings,
                Budget(10_000), tag, "t", "producer", installedBuild = { null },
                invoke = { _, _, actual, budget, _ ->
                    invocations++
                    assertEquals(listOf("arm=cp"), actual.params)
                    assertEquals(10_000, budget.timeoutMillis)
                    SolverInvocation.Result(true, 1.0, 5, proven = true, stats = emptyMap(),
                        rawOutput = raw, command = "cli", exactObjective = "1")
                }, validateMps = { actualRef, record, output, exact, _ ->
                    checks++
                    assertEquals(ref, actualRef)
                    assertEquals("v I=1 X=1", record.finalWitness)
                    assertEquals(sha256(raw.toByteArray()), record.sourceOutputSha256)
                    assertEquals("producer", record.gitSha)
                    assertEquals(raw, output)
                    assertFalse(exact)
                    MpsSourceValidation.Result(MpsSourceValidation.unknown("unavailable"))
                })

            assertEquals(raw, retained)
            assertEquals(MPS_SOURCE_POLICY, checked.validationPolicy)
            assertNull(checked.feasible)
            assertFalse(checked.proven)
            assertEquals("true", checked.stats["reportedFeasible"])
        }
        assertEquals(1, invocations)
        assertEquals(2, checks)
    }
}
