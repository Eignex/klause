package com.eignex.klause.cli

import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.prepareFiniteCompatibility
import com.eignex.klause.formats.flatzinc.sourceAttachment
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.ir.FloatSemantics
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
    fun `complete assignment capture should retain reconstructed hidden coordinates without changing model output`() {
        val source = File.createTempFile("complete-assignment", ".fzn").apply {
            writeText("""
                var 7..7: x :: output_var;
                var 0..28: hidden;
                constraint int_lin_eq([2,-1], [x,hidden], 0);
                solve $direction hidden;
            """.trimIndent())
        }
        val common = CommonOptions().apply {
            engine = "cp"
            parallel = 1
            randomSeed = 17
        }
        val session = MiniZincMode.newSession()
        parseArgs(arrayOf("--output-complete-assignment"), session.flags()) {}
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            val solvable = session.load(source.absolutePath, common)
            System.setOut(stream)
            SolveCore.solve(solvable, common, session.output(common))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        assertTrue("x = 7;" in text.lines(), text)
        assertFalse("hidden = 14;" in text.lines(), text)
        assertTrue("% klause-assignment: hidden = 14;" in text.lines(), text)
        assertTrue("% klause-assignment: _objective = 14;" in text.lines(), text)
        assertTrue("% klause-assignment-end" in text.lines(), text)
    }

    @Test
    fun `complete assignment capture should decline float coordinates without publishing a partial point`() {
        val source = File.createTempFile("complete-float-assignment", ".fzn").apply {
            writeText("var 0.0..1.0: x :: output_var; constraint float_eq(x,0.25); solve $direction x;")
        }
        val common = CommonOptions().apply {
            engine = "cp"
            parallel = 1
            randomSeed = 17
        }
        val session = MiniZincMode.newSession()
        parseArgs(arrayOf("--output-complete-assignment"), session.flags()) {}
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            val solvable = session.load(source.absolutePath, common)
            System.setOut(stream)
            SolveCore.solve(solvable, common, session.output(common))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        assertTrue("% klause-assignment-unavailable: integer and Boolean coordinates required" in text.lines(), text)
        assertFalse("% klause-assignment-begin:" in text, text)
    }

    @Test
    fun `output reconstruction should retain independent scaled costs and original objective units`() {
        val source = File.createTempFile("scaled-costs", ".fzn").apply {
            writeText("""
                var 12811..12811: length_a;
                var 12811..12811: length_b;
                var 0..100: scaled_a;
                var 0..100: scaled_b;
                var 910..910: cost_a;
                var 1050..1050: cost_b;
                var 3000000000..3000014000: total;
                constraint int_lin_le([1,-1000], [length_a,scaled_a], 0);
                constraint int_lin_eq([1,-70], [cost_a,scaled_a], 0);
                constraint int_lin_le([1,-1000], [length_b,scaled_b], 0);
                constraint int_lin_eq([1,-70], [cost_b,scaled_b], 0);
                constraint int_lin_eq([1,1,-1], [cost_a,cost_b,total], -3000000000);
                solve $direction total;
            """.trimIndent())
        }
        val outputModel = File.createTempFile("scaled-costs", ".ozn").apply {
            writeText("""
                int: cost_a;
                int: cost_b;
                int: total;
                output ["costs = ", show(array1d(-2..-1, [cost_a,cost_b])), ";\n",
                        "_objective = ", show(total), ";\n"];
            """.trimIndent())
        }
        val common = CommonOptions().apply {
            engine = "cp"
            parallel = 1
            randomSeed = 17
        }
        val session = MiniZincMode.newSession()
        parseArgs(arrayOf("--ozn", outputModel.absolutePath), session.flags()) {}
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            val solvable = session.load(source.absolutePath, common)
            System.setOut(stream)
            SolveCore.solve(solvable, common, session.output(common))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
            outputModel.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        assertTrue("910, 1050" in text, text)
        assertTrue("_objective = 3000001960;" in text.lines(), text)
        assertTrue("==========" in text.lines(), text)
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
    fun `declared nonlinear grid optimization should report its optimum in original units`() {
        val program = parseFlatZinc("var 0.0..1.0: x :: output_var; var 0.0..1.0: y :: output_var; " +
            "constraint float_le(x,0.75); constraint float_times(x,x,y); solve $direction y;", floatBuckets = 5)
        val maximize = direction == "maximize"
        val objective = assertNotNull(program.sourceProgram.linearObjective())
        val normalized = if (maximize) objective.negated() else objective
        val invocation = program.prepareFiniteCompatibility(SourceSetupRequest(program.sourceAttachment(),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED, normalized, maximize = maximize))
        assertTrue(assertNotNull(invocation.floatProjection).declaredGridSemantics)
        val solvable = Solvable(invocation.finiteShape(), { writeFlatZincSolution(program.sourceProgram, it, true) },
            null, continuousObjectiveValue = { normalized.evaluateExact(it).toDouble() })
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
            randomSeed = 17
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            System.setOut(stream)
            SolveCore.solve(solvable, common, MiniZincOutput(floatApproximation = true))
        } finally {
            System.setOut(previous)
            stream.close()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        val expected = if (maximize) "0.5" else "0.0"
        assertTrue("_objective = $expected;" in text.lines(), text)
        assertTrue("==========" in text.lines(), text)
        assertTrue("klause-grid-verdict: OPTIMAL" in text, text)
        assertTrue("declared finite grid" in text, text)
        assertFalse("=====UNKNOWN=====" in text.lines(), text)
    }

    @Test
    fun `exact nonlinear float optimization should retain continuous semantics without a grid`() {
        val source = File.createTempFile("exact-nonlinear-flatzinc", ".fzn").apply {
            writeText("var 0.0..1.0: x :: output_var; var 0.0..1.0: y :: output_var; " +
                "constraint float_eq(x,0.25); constraint float_times(x,x,y); solve $direction y;")
        }
        val common = CommonOptions().apply {
            engine = "cp"
            parallel = 1
            randomSeed = 17
        }
        parseArgs(arrayOf("--exact"), commonFlagSpecs(common)) {}
        val session = MiniZincMode.newSession()
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            val solvable = session.load(source.absolutePath, common)
            val invocation = assertNotNull(solvable.sourceInvocation)
            assertEquals(FloatSemantics.Continuous, invocation.source.semantics.effective.floats)
            assertNull(invocation.floatProjection)
            System.setOut(stream)
            SolveCore.solve(solvable, common, session.output(common))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        assertTrue("y = 0.0625;" in text.lines(), text)
        assertTrue("==========" in text.lines(), text)
        assertFalse("klause-grid-verdict" in text, text)
        assertFalse("declared finite grid" in text, text)
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
