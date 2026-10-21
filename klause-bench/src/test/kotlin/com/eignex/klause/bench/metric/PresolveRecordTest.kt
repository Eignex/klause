package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.cli.main
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresolveRecordTest {
    @ParameterizedTest
    @CsvSource("smt2,false", "smt2,true", "fzn,false", "fzn,true")
    fun `CLI observations should survive solve records`(extension: String, interrupted: Boolean) {
        val model = File.createTempFile("presolve-record", ".$extension", CorpusFetcher.workspaceRoot())
        try {
            model.writeText(if (extension == "smt2") {
                "(set-logic QF_LRA) (declare-const x Real) (assert (<= x 7)) (check-sat)"
            } else {
                "var 0..10: x; constraint int_lin_le([1], [x], 7); solve satisfy;"
            })
            val dialect = if (extension == "smt2") {
                SolverInvocation.Dialect.SMT_LIB
            } else SolverInvocation.Dialect.MINIZINC
            fun invoke(limit: Long): SolverInvocation.Result {
                val output = ByteArrayOutputStream()
                val previous = System.out
                try {
                    System.setOut(PrintStream(output))
                    main(arrayOf("-s", "-e", "fixed", "--presolve", "off,+strengthen,+bve",
                        "--param", "open-bound-proof=false", "--param", "presolve-work-limit=$limit",
                        model.absolutePath))
                } finally {
                    System.setOut(previous)
                }
                return SolverInvocation.invoke(
                    listOf("sh", "-c", "printf '%s' \"\$1\"", "sh", output.toString()), dialect,
                )
            }
            val complete = invoke(1_000_000)
            val result = if (interrupted) {
                val beforeFinite = complete.stats.filterKeys {
                    it.startsWith("presolveInvocation_SOURCE_") && it.endsWith("_Work") ||
                        it.startsWith("presolveStage_FINITE_") && it.endsWith("_Work") &&
                        ("_ROOT_BAKE_" in it || "_PRE_BAKE_LP_" in it)
                }.values.sumOf { it.toLong() }
                invoke(if (extension == "smt2") 1L else beforeFinite + 1L)
            } else complete
            val format = if (extension == "smt2") Format.SMTLIB else Format.IN_CODE
            val ref = ProblemRef("presolve", format, ProblemSource.Vendored(model.name), Category.CSP, Expected.Unknown)
            val (record, _) = SolveMetric.solve(
                ResolvedProblem(ref, lazy { error("subprocess input stays lazy") }), SolverInvocation.KLAUSE,
                SolverInvocation.Settings(engine = "fixed"), Budget(1000),
                UUID.randomUUID().toString(), "time", "revision",
                installedBuild = { null }, invoke = { _, _, _, _, _ -> result },
            )
            val restored = Reports.json.decodeFromString<SolveRecord>(Reports.json.encodeToString(record))
            val stats = restored.stats
            val outcome = if (interrupted) "INTERRUPTED" else "COMPLETE_MISS"

            assertEquals(result.stats, stats)
            val lane = if (extension == "smt2") "SOURCE" else "FINITE"
            assertTrue(stats.getValue("presolveInvocation_${lane}_strengthen_${outcome}_Calls").toInt() > 0)
            if (extension == "smt2") {
                assertTrue(stats.getValue("presolveStageNativeWork").toLong() > 0L)
                assertEquals("0", stats["presolveStage_SOURCE_SOURCE_ENCODER_COMPLETE_Work"])
            }
            if (!interrupted && extension == "fzn") {
                assertTrue(stats.getValue("presolveInvocation_FINITE_strengthen_COMPLETE_MISS_Calls").toInt() > 0)
                assertTrue(stats.getValue("presolveStage_FINITE_ROOT_BAKE_COMPLETE_Calls").toInt() > 0)
            }
            assertEquals(stats.getValue("presolveWork").toLong(),
                stats.getValue("presolveInvocationWork").toLong() + stats.getValue("presolveStageWork").toLong() +
                    stats.getValue("presolveUnattributedWork").toLong())
        } finally {
            model.delete()
        }
    }
}
