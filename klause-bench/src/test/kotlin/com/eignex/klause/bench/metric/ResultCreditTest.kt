package com.eignex.klause.bench.metric

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResultCreditTest {
    private val dir = Files.createTempDirectory("credit").toFile()

    private fun row(
        problem: String,
        objective: Double?,
        feasible: Boolean?,
        proven: Boolean = false,
        ms: Long = 100,
        maximize: Boolean = false,
        structure: String = "global",
    ) = ReferenceEntry(
        suite = "s",
        problem = problem,
        maximize = maximize,
        objective = objective,
        feasible = feasible,
        proven = proven,
        elapsedMs = ms,
        solver = "x",
        budgetMs = 10_000,
        format = "minizinc",
        structure = structure,
    )

    private fun csv(name: String, rows: List<ReferenceEntry>): File =
        File(dir, "$name.csv").also { ReferenceStore.writeCsv(it, rows) }

    @Test
    fun `COP winner is the best objective, direction-aware, and a proven optimum breaks equal-value ties`() {
        // minimize: lower wins. p1/p2 → A (10<20); p3 → B (15<30); p4 tie at 5 but B proved it.
        val a = csv(
            "A",
            listOf(row("p1", 10.0, true), row("p2", 10.0, true), row("p3", 30.0, true), row("p4", 5.0, true)),
        )
        val b = csv(
            "B",
            listOf(
                row("p1", 20.0, true),
                row("p2", 20.0, true),
                row("p3", 15.0, true),
                row("p4", 5.0, true, proven = true),
            ),
        )
        val report = ResultCredit.report(listOf(a, b))
        val wins = report.scores.associate { it.arm to it.wins }
        assertEquals(2, wins["A"], "A wins p1, p2")
        assertEquals(2, wins["B"], "B wins p3 and p4 (proven optimum breaks the tie)")
        assertEquals(4, report.totalWon)
    }

    @Test
    fun `CSP winner is the fastest run that decided the instance`() {
        val a = csv("A", listOf(row("q1", null, true, ms = 100), row("q2", null, true, ms = 300)))
        val b = csv("B", listOf(row("q1", null, true, ms = 200), row("q2", null, true, ms = 50)))
        val wins = ResultCredit.report(listOf(a, b)).scores.associate { it.arm to it.wins }
        assertEquals(1, wins["A"], "A is faster on q1")
        assertEquals(1, wins["B"], "B is faster on q2")
    }

    @Test
    fun `by-structure slices the credit into per-structure sections`() {
        val a = csv("A", listOf(row("g1", 5.0, true, structure = "global"), row("l1", 9.0, true, structure = "linear")))
        val b = csv("B", listOf(row("g1", 8.0, true, structure = "global"), row("l1", 4.0, true, structure = "linear")))
        val out = ResultCredit.credit(listOf(a, b), by = "structure")
        assertTrue("structure=global" in out, "a global section is rendered")
        assertTrue("structure=linear" in out, "a linear section is rendered")
    }
}
