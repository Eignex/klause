package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class CliPresolveOutputTest(private val extension: String, private val interrupted: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} interrupted={1}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("smt2", false), arrayOf("smt2", true), arrayOf("fzn", false), arrayOf("fzn", true),
        )
    }

    @Test
    fun `CLI output should retain preparation observations`() {
        val model = File.createTempFile("presolve-output", ".$extension")
        try {
            model.writeText(if (extension == "smt2") {
                "(set-logic QF_LRA) (declare-const x Real) (assert (<= x 7)) (check-sat)"
            } else {
                "var 0..10: x; constraint int_lin_le([1], [x], 7); solve satisfy;"
            })
            fun invoke(limit: Long): Map<String, String> {
                val output = ByteArrayOutputStream()
                val previous = System.out
                try {
                    System.setOut(PrintStream(output))
                    assertEquals(0, runCli(arrayOf("-s", "-e", "fixed", "--presolve", "off,+strengthen,+bve",
                        "--param", "open-bound-proof=false", "--param", "presolve-work-limit=$limit",
                        model.absolutePath)))
                } finally {
                    System.setOut(previous)
                }
                return output.toString().lineSequence().mapNotNull { line ->
                    val pair = line.removePrefix("; ").removePrefix("%%%mzn-stat: ").split('=', limit = 2)
                    if (pair.size == 2) pair[0] to pair[1] else null
                }.toMap()
            }
            val complete = invoke(1_000_000)
            val stats = if (interrupted) {
                val beforeFinite = complete.filterKeys {
                    val sourcePass = it.startsWith("presolveInvocation_SOURCE_")
                    val initialBake = it.startsWith("presolveStage_FINITE_") &&
                        ("_ROOT_BAKE_" in it || "_PRE_BAKE_LP_" in it)
                    it.endsWith("_Work") && (sourcePass || initialBake)
                }.values.sumOf { it.toLong() }
                invoke(if (extension == "smt2") 1L else beforeFinite + 1L)
            } else complete
            val outcome = if (interrupted) "INTERRUPTED" else "COMPLETE_MISS"

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
