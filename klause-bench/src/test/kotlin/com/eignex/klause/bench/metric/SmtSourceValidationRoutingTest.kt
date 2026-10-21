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
import kotlin.test.assertTrue

class SmtSourceValidationRoutingTest {
    @Test
    fun `SMT opt in should validate cached raw output without changing solver controls`() {
        val ref = ProblemRef(
            "lia-basic",
            Format.SMTLIB,
            corpus("smtlib/lia-basic.smt2"),
            Category.SAT,
            Expected.Unknown,
        )
        val entry = ResolvedProblem(ref, lazy { error("validation must read original bytes") })
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true", "arm=cp"))
        val raw = "sat\n(\n(define-fun x () Int 1)\n)\n"
        var solves = 0
        var checks = 0
        val tag = UUID.randomUUID().toString()

        repeat(2) {
            val (record, retained) = SolveMetric.solve(
                entry, SolverInvocation.KLAUSE, settings,
                Budget(10_000), tag, "t", "producer", installedBuild = { null }, invoke = { _, _, actual, budget, _ ->
                    solves++
                    assertEquals(listOf("arm=cp"), actual.params)
                    assertEquals(10_000, budget.timeoutMillis)
                    SolverInvocation.Result(
                        true,
                        null,
                        5,
                        proven = false,
                        stats = mapOf("exit" to "0"),
                        rawOutput = raw,
                        command = "cli",
                    )
                }, validateSmt = { actualRef, reported, output, _ ->
                    checks++
                    assertEquals(ref, actualRef)
                    assertEquals(raw, output)
                    assertEquals(sha256(raw.toByteArray()), reported.sourceOutputSha256)
                    assertEquals("producer", reported.gitSha)
                    SmtSourceValidation.Result(SmtSourceValidation.unknown("unavailable"))
                },
            )

            assertEquals(raw, retained)
            assertEquals(SMT_SOURCE_POLICY, record.validationPolicy)
            assertNull(record.feasible)
            assertFalse(record.proven)
            assertEquals("true", record.stats["reportedFeasible"])
        }
        assertEquals(1, solves)
        assertEquals(2, checks)
    }

    @Test
    fun `SMT opt in should keep a reported refutation without running an external tool`() {
        val ref = ProblemRef(
            "lia-basic",
            Format.SMTLIB,
            corpus("smtlib/lia-basic.smt2"),
            Category.SAT,
            Expected.Unknown,
        )
        val entry = ResolvedProblem(ref, lazy { error("validation must read original bytes") })
        val settings = SolverInvocation.Settings(params = listOf("source-validation=true"))

        val (record, _) = SolveMetric.solve(
            entry, SolverInvocation.KLAUSE, settings,
            Budget(10_000), UUID.randomUUID().toString(), "t", "producer", installedBuild = { null },
            invoke = { _, _, _, _, _ ->
                SolverInvocation.Result(false, null, null, proven = true, stats = mapOf("exit" to "0"),
                    rawOutput = "unsat\n", command = "cli")
            },
        )

        assertEquals(false, record.feasible)
        assertTrue(record.proven)
        assertEquals("unknown", record.stats["sourceValidation"])
    }
}
