package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class PresolveRecordTest {
    @ParameterizedTest
    @CsvSource("SOURCE,COMPLETE_MISS", "SOURCE,INTERRUPTED", "FINITE,COMPLETE_MISS", "FINITE,INTERRUPTED")
    fun `CLI presolve stat pairs should survive solve records`(lane: String, outcome: String) {
        val pass = "presolveInvocation_${lane}_strengthen_${outcome}_"
        val stage = "presolveStage_${lane}_SOURCE_ENCODER_COMPLETE_"
        val expected = linkedMapOf(
            "presolveWork" to "15",
            "presolveInvocationWork" to "5",
            "presolveStageWork" to "7",
            "presolveUnattributedWork" to "3",
            "presolveInvocationNativeWork" to "11",
            "presolveStageNativeWork" to "13",
            pass + "Calls" to "1",
            pass + "Ns" to "19",
            pass + "Work" to "5",
            pass + "RebuildWork" to "2",
            pass + "NativeWork" to "11",
            pass + "InputFactors" to "1",
            pass + "OutputFactors" to "1",
            pass + "InputOccurrenceCalls" to "0",
            pass + "FixedColumns" to "0",
            pass + "NarrowedColumns" to "0",
            stage + "Calls" to "1",
            stage + "Ns" to "23",
            stage + "Work" to "7",
            stage + "NativeWork" to "13",
            "presolveInvocation_${lane}_bve_ELIGIBILITY_SKIP_Calls" to "1",
        )
        val lines = expected.entries.joinToString("\n") { (key, value) -> "%%%mzn-stat: $key=$value" }
        val result = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' '$lines'"), SolverInvocation.Dialect.MINIZINC,
        )
        val ref = ProblemRef("php4", Format.DIMACS, corpus("dimacs/php4.cnf"), Category.CSP, Expected.Unknown)
        val (record, _) = SolveMetric.solve(
            ResolvedProblem(ref, lazy { error("subprocess input stays lazy") }), SolverInvocation.Settings(engine = "fixed"), Budget(1000),
            "time", "revision",
            installedBuild = { null }, invoke = { _, _, _, _ -> result },
        )

        val restored = Reports.json.decodeFromString<SolveRecord>(Reports.json.encodeToString(record))

        assertEquals(expected + ("exit" to "0"), restored.stats)
        assertEquals("revision", restored.gitSha)
        assertEquals("php4", restored.problem)
        assertEquals(1000L, restored.budgetMs)
    }
}
