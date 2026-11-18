package com.eignex.klause.cli

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class MiniZincSourceEnumerationTest(private val engine: String, private val sourceText: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {index}")
        fun sources(): List<Array<Any>> = listOf("mixed", "localsearch", "alns").flatMap { engine ->
            listOf(
                "var 0.0..1.0: x; constraint float_lin_eq([3.0],[x],1.0); solve satisfy;",
                "var 0.0..1.0: x; var 0.0..1.0: y; constraint float_eq(x,0.25); " +
                    "constraint float_times(x,x,y); solve satisfy;",
            ).map { arrayOf<Any>(engine, it) }
        }
    }

    @Test
    fun `continuous enumeration should report unknown without a completeness marker`() {
        val input = File.createTempFile("continuous-enumeration", ".fzn").apply { writeText(sourceText) }
        try {
            val common = CommonOptions().apply {
                this.engine = this@MiniZincSourceEnumerationTest.engine
                allSolutions = true
                parallel = 1
                randomSeed = 17
            }
            val session = MiniZincMode.newSession()
            val solvable = session.load(input.absolutePath, common)

            val output = captureStdout { SolveCore.solve(solvable, common, session.output(common)) }

            assertTrue("=====UNKNOWN=====" in output.lines(), output)
            assertTrue("==========" !in output.lines(), output)
        } finally {
            input.delete()
        }
    }
}
