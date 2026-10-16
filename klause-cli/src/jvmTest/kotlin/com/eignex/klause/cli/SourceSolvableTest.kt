package com.eignex.klause.cli

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SourceSolvableTest(private val direction: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun directions(): List<String> = listOf("minimize", "maximize")
    }

    @Test
    fun `finite source definitions should retain one invocation and publish original objective units`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(2, 3)),
            arrayOf(Element(0, 1, longArrayOf(2, 3), arrIsVars = false, indexOffset = 0)))
        val maximize = direction == "maximize"
        val objective = LinearObjective(intCoefficients = longArrayOf(0, if (maximize) -1L else 1L))
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
            randomSeed = 17L
            presolve = "none"
        }
        val solvable = sourceSolvable(source, objective, maximize, common,
            { "x=${it.intValue(1)}" }, { error("finite source selected open rendering") },
            definedVars = intArrayOf(1))
        val points = mutableListOf<Pair<String, Long?>>()
        var completed: Verdict? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onSolution(rendered: String, objective: Long?, continuousObjective: Double?) {
                points += rendered to objective
            }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        SolveCore.solve(solvable, common, output)

        val optimum = if (maximize) 3L else 2L
        assertEquals("x=$optimum" to optimum, points.last())
        assertEquals(Verdict.OPTIMAL, completed)
        assertSame(assertNotNull(solvable.sourceInvocation), solvable.finiteShape.sourceInvocation)
        assertSame(source, solvable.sourceInvocation?.source?.originalSource)
        assertNull(solvable.definitionalSweep)
        assertNull(solvable.lsObjective)
    }

    @Test
    fun `XCSP optimization should publish exact source units beyond binary64 integer precision`() {
        val source = File.createTempFile("xcsp-source-setup", ".xml").apply {
            writeText(
                """
                <instance format="XCSP3" type="COP">
                  <variables><var id="x">1999999999..2000000000</var></variables>
                  <objectives><$direction type="sum">
                    <list>x</list><coeffs>2000000001</coeffs>
                  </$direction></objectives>
                </instance>
                """.trimIndent(),
            )
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            System.setOut(stream)
            main(arrayOf("--format", "xcsp3", "-e", "cp", "-p", "1", source.absolutePath))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val output = bytes.toString(Charsets.UTF_8.name())
        val objective = if (direction == "maximize") "4000000002000000000" else "3999999999999999999"
        val value = if (direction == "maximize") "2000000000" else "1999999999"
        assertTrue("o $objective" in output.lines(), output)
        assertTrue("s OPTIMUM FOUND" in output.lines(), output)
        assertTrue("<values> $value </values>" in output, output)
    }
}
