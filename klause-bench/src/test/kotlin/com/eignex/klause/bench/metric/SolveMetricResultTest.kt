package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals

class SolveMetricResultTest {
    private fun rec(feasible: Boolean?, objective: Double?, timeToBestMs: Long?, proven: Boolean) = SolveRecord(
        problem = "fam/inst",
        solver = "klause",
        engine = "cp",
        processors = 1,
        search = "free",
        seed = 3,
        budgetMs = 10_000,
        kind = if (objective != null) "optimize" else "satisfy",
        maximize = false,
        feasible = feasible,
        objective = objective,
        timeToBestMs = timeToBestMs,
        proven = proven,
        gitSha = null,
        timestamp = "t",
        command = "c",
    )

    @Test
    fun `a source rejected result receives no solution or proof credit`() {
        val record = rec(true, 1.001466, 100, true)

        val checked = record.sourceChecked(SourceValidation("invalid", "source contradiction"), approximation = false)
        val row = SolveMetric.resultRow("s", checked, "cfg", null)

        assertEquals(null, row.feasible)
        assertEquals(null, row.objective)
        assertEquals(false, row.proven)
        assertEquals(10_000, row.elapsedMs)
        assertEquals("1.001466", checked.stats["reportedObjective"])
    }

    @Test
    fun `source valid grid witnesses keep feasibility but not optimality credit`() {
        val record = rec(true, 1.0, 100, true)

        val checked = record.sourceChecked(SourceValidation("valid", "grounded"), approximation = true)

        assertEquals(true, checked.feasible)
        assertEquals(1.0, checked.objective)
        assertEquals(false, checked.proven)
        assertEquals(100, checked.timeToBestMs)
    }

    @Test
    fun `unchecked grid witnesses and grid refutations remain undecided`() {
        for (feasible in listOf(true, false)) {
            val record = rec(feasible, if (feasible) 1.0 else null, 100, true)

            val checked = record.sourceChecked(SourceValidation("unknown", "residual"), approximation = true)

            assertEquals(null, checked.feasible)
            assertEquals(null, checked.objective)
            assertEquals(false, checked.proven)
            assertEquals(null, checked.timeToBestMs)
        }
    }

    @Test
    fun `a source valid exact result retains its proof`() {
        val checked = rec(true, 1.0, 100, true)
            .sourceChecked(SourceValidation("valid", "grounded"), approximation = false)

        assertEquals(true, checked.proven)
        assertEquals(true, checked.feasible)
    }

    @Test
    fun `a solved row's elapsed is time-to-best, tagged with the config, features joined`() {
        val ref = ReferenceEntry(
            "s", "fam/inst", false, null, null, false, 0, "cp-sat", 300_000,
            format = "minizinc", structure = "global", numGlobal = 3, numLinear = 1, boolHeavy = false,
        )
        val record = rec(feasible = true, objective = 42.0, timeToBestMs = 250, proven = false)
        val row = SolveMetric.resultRow("s", record, "cfg-A", ref)
        assertEquals("cfg-A", row.solver, "solver column is the config tag")
        assertEquals(250, row.elapsedMs, "time-used proxy = time-to-best when solved")
        assertEquals(42.0, row.objective)
        assertEquals("global", row.structure, "feature joined from the oracle row")
        assertEquals(3, row.numGlobal)
    }

    @Test
    fun `an unsolved row falls back to the budget and blank features when there is no oracle row`() {
        val record = rec(feasible = null, objective = null, timeToBestMs = null, proven = false)
        val row = SolveMetric.resultRow("s", record, "cfg-A", null)
        assertEquals(10_000, row.elapsedMs, "time-used proxy = budget when unsolved")
        assertEquals("", row.structure, "no features when the instance has no oracle row")
    }
}
