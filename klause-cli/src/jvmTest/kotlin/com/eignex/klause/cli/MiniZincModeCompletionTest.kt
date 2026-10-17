package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MiniZincModeCompletionTest(private val engine: String, private val exact: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} exact={1}")
        fun modes(): List<Array<Any>> = listOf("cp", "ls").flatMap { engine ->
            listOf(false, true).map { exact -> arrayOf<Any>(engine, exact) }
        }
    }

    @Test
    fun `bounded int to float products should retain finite execution and publish a source witness`() {
        val source = File.createTempFile("mixed-product-completion", ".fzn").apply {
            writeText(
                """
                var 1..2: n :: output_var;
                var 1.0..2.0: nf :: output_var;
                var 1.0..4.0: x :: output_var;
                var 4.0..4.0: y :: output_var;
                constraint int2float(n,nf);
                constraint float_times(nf,x,y);
                solve satisfy;
                """.trimIndent(),
            )
        }
        val common = CommonOptions().apply {
            engine = this@MiniZincModeCompletionTest.engine
            exact = this@MiniZincModeCompletionTest.exact
            parallel = 1
            randomSeed = 17
        }
        val session = MiniZincMode.newSession()
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            val solvable = session.load(source.absolutePath, common)
            assertEquals(SourceRendering.FiniteCp, solvable.rendering)
            System.setOut(stream)
            SolveCore.solve(solvable, common, session.output(common))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        assertTrue("----------" in text.lines(), text)
        assertFalse("=====UNKNOWN=====" in text.lines(), text)
        assertTrue("y = 4.0;" in text.lines() || "y = 4;" in text.lines(), text)
    }
}
