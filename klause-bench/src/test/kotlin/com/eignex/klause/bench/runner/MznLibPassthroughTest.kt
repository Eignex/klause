package com.eignex.klause.bench.runner

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the MiniZinc-library passthrough declarations against the silent-decomposition trap
 * (#492): a global that klause handles natively must reach the front-end *as that global*, not be
 * expanded into primitive constraints by a std-library decomposition.
 *
 * The trap has two shapes, both of which left road-cons / carpet-cutting crawling at ~1 node/s:
 *  1. A std `redefinitions-<ver>.mzn` carries a decomposition *body* that beats a bare `predicate`
 *     declaration elsewhere (array_int_minimum/maximum → chains of pairwise int_min/int_max).
 *  2. klause declares a global in a *narrower* (const-args) signature than the model uses, so a
 *     variable-args call (cumulative/diffn/disjunctive with var durations/sizes) fails to match and
 *     falls back to the std reified decomposition.
 *
 * Each case compiles a tiny model and asserts the native global token survives in the `.fzn` while
 * the decomposition token does not. Skips when `minizinc` isn't on PATH so bare CI images stay green.
 */
class MznLibPassthroughTest {

    private data class Case(val name: String, val model: String, val mustContain: String, val mustNotContain: String)

    private val cases = listOf(
        // `min`/`max` over a var-int list → array_int_minimum/maximum, not pairwise int_min/int_max.
        Case(
            "array_int_minimum",
            "array[1..4] of var 0..9: a;\nvar int: m = min(a);\nconstraint m >= 2;\nsolve satisfy;\n",
            mustContain = "array_int_minimum",
            mustNotContain = "int_min(",
        ),
        Case(
            "array_int_maximum",
            "array[1..4] of var 0..9: a;\nvar int: m = max(a);\nconstraint m <= 7;\nsolve satisfy;\n",
            mustContain = "array_int_maximum",
            mustNotContain = "int_max(",
        ),
        // Variable durations / sizes must route to the native factor, not the reified decomposition.
        Case(
            "cumulative (var durations)",
            "include \"globals.mzn\";\narray[1..3] of var 0..9: s;\narray[1..3] of var 1..3: d;\n" +
                "constraint cumulative(s, d, [1,1,1], 2);\nsolve satisfy;\n",
            mustContain = "fzn_cumulative",
            mustNotContain = "int_lin_le_reif",
        ),
        Case(
            "diffn (var sizes)",
            "include \"globals.mzn\";\narray[1..3] of var 0..9: x;\narray[1..3] of var 0..9: y;\n" +
                "array[1..3] of var 1..3: dx;\narray[1..3] of var 1..3: dy;\n" +
                "constraint diffn(x, y, dx, dy);\nsolve satisfy;\n",
            mustContain = "fzn_diffn",
            mustNotContain = "int_lin_le_reif",
        ),
        Case(
            "disjunctive (var durations)",
            "include \"globals.mzn\";\narray[1..3] of var 0..9: s;\narray[1..3] of var 1..3: d;\n" +
                "constraint disjunctive(s, d);\nsolve satisfy;\n",
            mustContain = "fzn_disjunctive",
            mustNotContain = "int_lin_le_reif",
        ),
    )

    @Test
    fun `native globals survive compilation instead of decomposing`() {
        if (!minizincOnPath()) {
            println("[mzn-passthrough] minizinc not on PATH — skipping")
            return
        }
        val root = CorpusFetcher.workspaceRoot()
        val msc = File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")
        val libDir = File(root, "klause-mzn-lib/share/minizinc/klause")
        assertTrue(msc.exists(), "klause.msc not found at $msc")
        for (c in cases) {
            val mzn = File.createTempFile("passthru-${c.name.substringBefore(' ')}-", ".mzn").apply {
                writeText(c.model)
                deleteOnExit()
            }
            val fzn = compile(msc, libDir, mzn)
            val text = fzn.readText()
            assertTrue(
                "constraint ${c.mustContain}" in text,
                "${c.name}: expected native `${c.mustContain}` in compiled FZN — it decomposed instead:\n" +
                    text.lines().filter { it.startsWith("constraint") }.groupingBy { it.substringBefore('(').trim() }
                        .eachCount(),
            )
            assertTrue(
                "constraint ${c.mustNotContain}" !in text,
                "${c.name}: decomposition token `${c.mustNotContain}` leaked into the FZN",
            )
        }
    }

    private fun compile(msc: File, libDir: File, mzn: File): File {
        val out = File.createTempFile("passthru-out-", ".fzn").apply { deleteOnExit() }
        val cmd = listOf(
            "minizinc", "--solver", msc.absolutePath, "-c", "-G", libDir.absolutePath,
            "--output-fzn-to-file", out.absolutePath, mzn.absolutePath,
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "compile timed out: ${mzn.name}")
        assertTrue(p.exitValue() == 0, "compile failed for ${mzn.name}: ${log.take(400)}")
        return out
    }

    private fun minizincOnPath(): Boolean = runCatching {
        ProcessBuilder("minizinc", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)
}
