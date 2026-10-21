package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Needs `minizinc` with Gecode on `PATH`; skipped where it is not installed. */
class MiniZincModelWitnessTest {
    private val relative = "klause-bench/build/tmp/model-witness/${UUID.randomUUID()}"
    private val dir = File(CorpusFetcher.workspaceRoot(), relative)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a Klause MiniZinc witness should be recorded at model level and accepted by MiniZinc`() {
        assumeTrue(gecodeAvailable(), "minizinc with Gecode is not installed")
        val ref = model()
        val flat = flatSolution(ref)
        assertTrue(INTRODUCED in flat, flat)
        val raw = flat.replace("----------", "% klause-float-component: none\n----------")

        val record = solve(ref, raw) { r, solution -> MiniZincRunner().modelOutput(r, solution) }

        val witness = checkNotNull(record.finalWitness)
        assertEquals("model", record.stats["finalWitnessLevel"])
        assertFalse(INTRODUCED in witness, witness)
        assertTrue(Regex("\\b(Red|Green|Blue)\\b").containsMatchIn(witness), witness)
        assertTrue(witness.endsWith("\n% klause-float-component: none"), witness)
        val objective = Regex("(?m)^_objective = (-?[0-9]+);").find(witness)?.groupValues?.get(1)
        val data = File(dir, "witness.dzn").apply {
            writeText(witness.lineSequence().filterNot { it.startsWith("%") || it.startsWith("_objective") }
                .joinToString("\n"))
        }
        val checked = run("--solver", "gecode", "--allow-multiple-assignments", "--output-objective",
            File(dir, "m.mzn").path, data.path)
        assertTrue("----------" in checked && "_objective = $objective;" in checked, checked)
    }

    @Test
    fun `a witness the output mapping cannot read should keep its FlatZinc text and say why`() {
        assumeTrue(gecodeAvailable(), "minizinc with Gecode is not installed")
        val ref = model()
        val flat = flatSolution(ref)

        val record = solve(ref, flat) { _, _ -> error("mapping unavailable") }

        assertEquals("flatzinc", record.stats["finalWitnessLevel"])
        assertEquals("mapping unavailable", record.stats["finalWitnessMappingError"])
        assertEquals(SolveEvidence.finalWitness(Format.MINIZINC, flat), record.finalWitness)
    }

    private fun model(): ProblemRef {
        dir.mkdirs()
        File(dir, "m.mzn").writeText(MODEL)
        return ProblemRef("model-witness/m", Format.MINIZINC, ProblemSource.Vendored("$relative/m.mzn"),
            Category.OPTIMIZATION, Expected.Unknown)
    }

    /** A FlatZinc solver's own output for [ref]'s compiled FlatZinc, as Klause would print it. */
    private fun flatSolution(ref: ProblemRef): String =
        run("--solver", "gecode", MiniZincRunner().compileFzn(ref, fresh = true).path)

    private fun solve(ref: ProblemRef, raw: String, modelOutput: (ProblemRef, String) -> String): SolveRecord {
        val entry = ResolvedProblem(ref, lazy { error("source parsing must stay lazy") })
        return SolveMetric.solve(entry, SolverInvocation.KLAUSE, SolverInvocation.Settings(), Budget(),
            UUID.randomUUID().toString(), "now", "fixture", installedBuild = { null },
            invoke = { _, _, _, _, _ ->
                SolverInvocation.Result(true, null, null, proven = false, stats = emptyMap(), rawOutput = raw,
                    command = "cli")
            }, modelOutput = modelOutput).first
    }

    private fun run(vararg args: String): String {
        val proc = ProcessBuilder(listOf("minizinc") + args).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && proc.exitValue() == 0) { text }
        return text
    }

    private fun gecodeAvailable(): Boolean = runCatching { "org.gecode.gecode" in run("--solvers") }.getOrDefault(false)

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val INTRODUCED = "X_INTRODUCED_"
        val MODEL = """
            enum Color = {Red, Green, Blue};
            array[1..3] of var Color: c;
            array[1..2, 1..2] of var Color: g;
            var 0..20: total;
            constraint forall(i in 1..2)(c[i] != c[i + 1]);
            constraint g[1, 1] != g[2, 2] /\ g[1, 2] = c[2];
            constraint total = sum(i in 1..3)(i * (c[i] = Blue)) + sum(i, j in 1..2)(g[i, j] = Red);
            solve maximize total;
        """.trimIndent()
    }
}
