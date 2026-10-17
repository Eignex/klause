package com.eignex.klause.cli

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MiniZincModeTest(private val direction: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun directions(): List<Array<String>> = listOf("minimize", "maximize").map { arrayOf(it) }
    }

    @Test
    fun `automatic complete and mixed loading should solve and render an off grid original optimum`() {
        val source = File.createTempFile("original-off-grid", ".fzn").apply {
            writeText("var 0.0..1.0: x :: output_var; constraint float_eq(x,0.25); solve $direction x;")
        }
        val previous = System.out
        try {
            for (engine in listOf("cp", "mixed")) {
                val common = CommonOptions().apply {
                    this.engine = engine
                    parallel = 1
                    sourceOptions.floatBuckets = 2
                }
                val session = MiniZincMode.newSession()
                val solvable = session.load(source.absolutePath, common)
                val invocation = assertNotNull(solvable.sourceInvocation)
                assertTrue(ExecutionStrategy.COMPLETE in assertNotNull(invocation.admission).arms)
                assertNull(invocation.floatProjection)
                val bytes = ByteArrayOutputStream()
                val stream = PrintStream(bytes)
                try {
                    System.setOut(stream)
                    SolveCore.solve(solvable, common, session.output(common))
                } finally {
                    System.setOut(previous)
                    stream.close()
                }
                val output = bytes.toString(Charsets.UTF_8.name())
                assertTrue("x = 0.25;" in output, output)
                assertTrue("==========" in output.lines(), output)
                assertFalse("private-grid" in output, output)
            }
        } finally {
            System.setOut(previous)
            source.delete()
        }
    }

    @Test
    fun `prepared finite dispatch should retain its source invocation objective units and routing costs`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(3), constant = 7)
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective, maximize = direction == "maximize"))
        val stats = LpStats()

        val solvable = preparedSourceSolvable(invocation, { "" }, { "" }, stats, 17)

        assertSame(invocation, solvable.sourceInvocation)
        assertSame(invocation.finiteShape(), solvable.finiteShape)
        assertEquals(10L, assertNotNull(solvable.objectiveValue)(Sample(booleanArrayOf(), longArrayOf(1))))
        assertSame(stats, solvable.routingLpStats)
        assertEquals(17L, solvable.routingElapsedMs)
    }

    @Test
    fun `finite grid loading should retain original real source and private objective units`() {
        val source = File.createTempFile("source-grid-flatzinc", ".fzn").apply {
            writeText("var 0.0..2.0: x; constraint float_le(x,1.0); solve $direction x;")
        }
        try {
            val common = CommonOptions().apply { engine = "ls" }

            val solvable = MiniZincMode.newSession().load(source.absolutePath, common)
            val shape = solvable.finiteShape
            val invocation = assertNotNull(shape.sourceInvocation)
            val projection = assertNotNull(invocation.floatProjection)
            val original = assertIs<com.eignex.klause.ir.Problem>(invocation.source.originalSource)

            assertEquals(1, original.numRealVars)
            assertEquals(0, original.numIntVars)
            assertEquals(0, shape.problem.numRealVars)
            assertEquals(1, shape.problem.numIntVars)
            assertSame(shape.problem, projection.privateProblem)
            assertSame(original, projection.source.originalSource)
            assertNotNull(projection.objectiveScale)
            assertNull(solvable.objectiveValue)
            assertNotNull(solvable.continuousObjectiveValue)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `finite loading should capture source definitions without materializing heuristic state`() {
        val source = File.createTempFile("source-setup-flatzinc", ".fzn").apply {
            writeText("var -2..2: x; var 0..2: a; var 4..8: score; " +
                "constraint int_abs(x,a) :: defines_var(a); " +
                "constraint int_lin_eq([2,-1],[a,score],-4) :: defines_var(score); solve $direction score;")
        }
        try {
            for (engine in listOf("cp", "ls")) {
                val common = CommonOptions().apply { this.engine = engine }

                val solvable = MiniZincMode.newSession().load(source.absolutePath, common)
                val shape = solvable.finiteShape
                val invocation = assertNotNull(shape.sourceInvocation)

                assertTrue(solvable.problem === invocation.source.originalSource)
                assertSame(shape.preparedPlan, invocation.preparedPlan)
                assertEquals(2, invocation.source.definitionHints.size)
                assertNull(solvable.definitionalSweep)
                assertNull(solvable.lsObjective)
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun `an unattained source boundary should retain its witness without an optimum terminator`() {
        val source = File.createTempFile("polynomial-flatzinc-limit", ".fzn").apply {
            writeText(
                """
                var 0..1: i :: output_var;
                var float: r :: output_var;
                var float: x :: output_var;
                var float: y :: output_var;
                constraint int2float(i,r);
                constraint float_times(r,x,y);
                constraint float_lt(0.0,x);
                constraint float_lt(x,1.0);
                solve $direction x;
                """.trimIndent(),
            )
        }
        val common = CommonOptions().apply {
            engine = "cp"
            parallel = 1
            randomSeed = 17
            exact = true
        }
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
        val status = if (direction == "minimize") "unattained-infimum" else "unattained-supremum"
        val text = bytes.toString(Charsets.UTF_8.name())
        assertEquals(expected, limit.bound.value.rational)
        assertEquals(1, text.lines().count { it == "----------" }, text)
        assertTrue("% objective=$point" in text.lines(), text)
        assertTrue("% optimizationStatus=$status" in text.lines(), text)
        assertFalse("==========" in text.lines(), text)
    }
}
