package com.eignex.klause.bench.runner

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@Ignore("integration coverage; run explicitly on a host with minizinc installed")
class MznLibPassthroughTest {

    private data class Case(val name: String, val model: String, val mustContain: String, val mustNotContain: String)

    @Test
    fun `array minimum compiles to its native global`() = assertPassthrough(
        Case(
            "array_int_minimum",
            "array[1..4] of var 0..9: a;\nvar int: m = min(a);\nconstraint m >= 2;\nsolve satisfy;\n",
            mustContain = "array_int_minimum",
            mustNotContain = "int_min(",
        ),
    )

    @Test
    fun `array maximum compiles to its native global`() = assertPassthrough(
        Case(
            "array_int_maximum",
            "array[1..4] of var 0..9: a;\nvar int: m = max(a);\nconstraint m <= 7;\nsolve satisfy;\n",
            mustContain = "array_int_maximum",
            mustNotContain = "int_max(",
        ),
    )

    @Test
    fun `cumulative with variable durations compiles to its native global`() = assertPassthrough(
        Case(
            "cumulative-var-durations",
            "include \"globals.mzn\";\narray[1..3] of var 0..9: s;\narray[1..3] of var 1..3: d;\n" +
                "constraint cumulative(s, d, [1,1,1], 2);\nsolve satisfy;\n",
            mustContain = "fzn_cumulative",
            mustNotContain = "int_lin_le_reif",
        ),
    )

    @Test
    fun `diffn with variable sizes compiles to its native global`() = assertPassthrough(
        Case(
            "diffn-var-sizes",
            "include \"globals.mzn\";\narray[1..3] of var 0..9: x;\narray[1..3] of var 0..9: y;\n" +
                "array[1..3] of var 1..3: dx;\narray[1..3] of var 1..3: dy;\n" +
                "constraint diffn(x, y, dx, dy);\nsolve satisfy;\n",
            mustContain = "fzn_diffn",
            mustNotContain = "int_lin_le_reif",
        ),
    )

    @Test
    fun `disjunctive with variable durations compiles to its native global`() = assertPassthrough(
        Case(
            "disjunctive-var-durations",
            "include \"globals.mzn\";\narray[1..3] of var 0..9: s;\narray[1..3] of var 1..3: d;\n" +
                "constraint disjunctive(s, d);\nsolve satisfy;\n",
            mustContain = "fzn_disjunctive",
            mustNotContain = "int_lin_le_reif",
        ),
    )

    private fun assertPassthrough(case: Case) {
        assertTrue(minizincOnPath(), "minizinc must be installed to run this integration test")
        val root = CorpusFetcher.workspaceRoot()
        val msc = File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")
        val libDir = File(root, "klause-mzn-lib/share/minizinc/klause")
        assertTrue(msc.exists(), "klause.msc not found at $msc")
        val mzn = File.createTempFile("passthru-${case.name}-", ".mzn").apply {
            writeText(case.model)
            deleteOnExit()
        }
        val fzn = compile(msc, libDir, mzn)
        val text = fzn.readText()
        assertTrue(
            "constraint ${case.mustContain}" in text,
            "${case.name}: expected native `${case.mustContain}` in compiled FZN — it decomposed instead:\n" +
                text.lines()
                    .filter { it.startsWith("constraint") }
                    .groupingBy { it.substringBefore('(').trim() }
                    .eachCount(),
        )
        assertTrue(
            "constraint ${case.mustNotContain}" !in text,
            "${case.name}: decomposition token `${case.mustNotContain}` leaked into the FZN",
        )
    }

    private fun compile(msc: File, libDir: File, mzn: File): File {
        val out = File.createTempFile("passthru-out-", ".fzn").apply { deleteOnExit() }
        val cmd = listOf(
            "minizinc", "--solver", msc.absolutePath, "-c", "-G", libDir.absolutePath,
            "--output-fzn-to-file", out.absolutePath, mzn.absolutePath,
        )
        val logFile = File.createTempFile("passthru-log-", ".txt").apply { deleteOnExit() }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(logFile).start()
        try {
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "compile timed out: ${mzn.name}")
            val log = logFile.readText()
            assertTrue(p.exitValue() == 0, "compile failed for ${mzn.name}: ${log.take(400)}")
        } finally {
            logFile.delete()
        }
        return out
    }

    private fun minizincOnPath(): Boolean = runCatching {
        ProcessBuilder("minizinc", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)
}
