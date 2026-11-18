package com.eignex.klause.cli

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class MiniZincEnumerationTest(
    private val engine: String,
    private val all: Boolean,
    private val cap: Int?,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} all={1} cap={2}")
        fun modes(): List<Array<Any?>> = listOf("fixed", "cp", "mixed", "ls", "alns").flatMap { engine ->
            listOf(true to null, true to 1, true to 2, false to 2).map { (all, cap) ->
                arrayOf(engine, all, cap)
            }
        }
    }

    @Test
    fun `finite enumeration should list the requested assignments and mark only exhaustion`() {
        val source = File.createTempFile("finite-enumeration", ".fzn").apply {
            writeText("var 0..2: k :: output_var; var 0..2: j :: output_var; constraint int_le(k,j); solve satisfy;")
        }
        try {
            val common = CommonOptions().apply {
                engine = this@MiniZincEnumerationTest.engine
                allSolutions = all
                solutionCap = cap?.toLong()
                parallel = 1
                randomSeed = 17
            }
            val session = MiniZincMode.newSession()
            val solvable = session.load(source.absolutePath, common)

            val out = captureStdout { SolveCore.solve(solvable, common, session.output(common)) }

            assertEquals(cap ?: 6, out.lines().count { it == "----------" }, out)
            assertEquals(cap == null, "==========" in out.lines(), out)
        } finally {
            source.delete()
        }
    }
}
