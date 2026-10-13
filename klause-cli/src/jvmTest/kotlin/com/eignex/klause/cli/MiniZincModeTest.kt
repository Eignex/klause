package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class MiniZincModeTest(private val direction: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun directions(): List<Array<String>> = listOf("minimize", "maximize").map { arrayOf(it) }
    }

    @Test
    fun `an unattained source boundary retains its witness without an optimum terminator`() {
        val source = File.createTempFile("polynomial-flatzinc-limit", ".fzn").apply {
            writeText("""
                var 0..1: i :: output_var;
                var float: r :: output_var;
                var float: x :: output_var;
                var float: y :: output_var;
                constraint int2float(i,r);
                constraint float_times(r,x,y);
                constraint float_lt(0.0,x);
                constraint float_lt(x,1.0);
                solve $direction x;
            """.trimIndent())
        }
        val common = CommonOptions().apply { engine = "cp"; parallel = 1; randomSeed = 17; exact = true }
        val session = MiniZincMode.newSession()
        val protocol = session.output(common)
        var terminalResult: QuadraticOptimizationResult? = null
        val output = object : OutputProtocol by protocol {
            override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
                terminalResult = result
                protocol.onQuadraticResult(rendered, result)
            }
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            val solvable = session.load(source.absolutePath, common)
            assertIs<SolvablePipeline.PolynomialTheory>(solvable.pipeline)
            System.setOut(stream)
            SolveCore.solve(solvable, common, output)
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val limit = assertIs<QuadraticOptimizationResult.UnattainedLimit>(terminalResult)
        val point = assertNotNull(limit.incumbent.value.rational)
        val expected = if (direction == "minimize") BigFraction.ZERO else BigFraction.ONE
        val text = bytes.toString(Charsets.UTF_8.name())
        assertEquals(expected, limit.bound.value.rational)
        assertTrue(point > BigFraction.ZERO && point < BigFraction.ONE)
        assertEquals(1, text.lines().count { it == "----------" }, text)
        assertTrue("% objective=$point" in text.lines(), text)
        assertFalse("==========" in text.lines(), text)
        assertFalse("=====UNKNOWN=====" in text.lines(), text)
    }
}
