package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.prepareSourceExecution
import com.eignex.klause.formats.flatzinc.sourceAttachment
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialToleranceTest {
    // Over the declared ranges the least product is 2 and the least square 1, so a bound just below either is met
    // only within the slack.
    private val product = """
        var 1.0..1.5: x;
        var 2.0..3.0: w;
        var -10.0..10.0: z;
        constraint float_times(x, w, z);
        constraint float_le(z, BOUND);
        solve satisfy;
    """.trimIndent()

    private val power = """
        var 1.0..2.0: x;
        var -10.0..10.0: y;
        constraint float_pow(x, 2.0, y);
        constraint float_le(y, BOUND);
        solve satisfy;
    """.trimIndent()

    private val withinTolerance = mapOf("product" to product.replace("BOUND", "1.99999995"),
        "power" to power.replace("BOUND", "0.99999995"))

    private val beyondTolerance = mapOf("product" to product.replace("BOUND", "1.9999"),
        "power" to power.replace("BOUND", "0.9999"))

    private fun execute(source: String, overrides: SemanticOverrides = SemanticOverrides()): SourceExecutionResult {
        val program = parseFlatZinc(source, semanticOverrides = overrides)
        val objective = program.sourceProgram.linearObjective()
        val invocation = program.prepareSourceExecution(SourceSetupRequest(program.sourceAttachment(),
            if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
            FiniteEngine.BACKTRACK, objective = objective))
        return SourceSetup.execute(SourceExecutionRequest(invocation))
    }

    private fun verdict(source: String, overrides: SemanticOverrides = SemanticOverrides()): String? =
        assertIs<SourceExecutionResult.Polynomial>(execute(source, overrides)).result.result::class.simpleName

    @Test
    fun `relations satisfiable only within the tolerance should be satisfied by default`() {
        val verdicts = withinTolerance.mapValues { verdict(it.value) }

        assertEquals(withinTolerance.mapValues { "Sat" }, verdicts)
    }

    @Test
    fun `relations satisfiable only within the tolerance should be refuted at zero tolerance`() {
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())

        val verdicts = withinTolerance.mapValues { verdict(it.value, exact) }

        assertEquals(withinTolerance.mapValues { "Unsat" }, verdicts)
    }

    @Test
    fun `relations violated beyond the tolerance should be refuted by default`() {
        val verdicts = beyondTolerance.mapValues { verdict(it.value) }

        assertEquals(beyondTolerance.mapValues { "Unsat" }, verdicts)
    }

    @Test
    fun `a point satisfying a product within the tolerance should pass the source acceptance`() {
        val source = withinTolerance.getValue("product")
        val problem = parseFlatZinc(source).sourceProgram.problem

        val solved = assertIs<SourceExecutionResult.Polynomial>(execute(source)).result.result
        val point = assertIs<OpenTheoryResult.Sat>(solved).assignment
        val reals = List(problem.numRealVars) { assertNotNull(assertNotNull(point.algebraicRealValue(it)).rational) }
        val sample = Sample(BooleanArray(problem.numBoolVars) { point.boolValue(it) }, LongArray(0),
            DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals)

        assertTrue(assertNotNull(capturedRealAcceptance(problem, ExecutionControl.Never))(sample))
    }

    private val leastSquare = power.replace("constraint float_le(y, BOUND);\n", "")
        .replace("solve satisfy;", "solve minimize y;")

    @Test
    fun `minimizing a power should attain the optimum over the points within the tolerance`() {
        val slack = BigFraction.of(bigIntOf(1L), bigIntOf(10_000_000L))
        val expected = (BigFraction.ONE - slack) * (BigFraction.ONE - slack) - slack

        val result = assertIs<SourceExecutionResult.Quadratic>(execute(leastSquare))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertEquals(expected, optimum.incumbent.value.rational)
    }

    @Test
    fun `an algebraic coordinate within the tolerance should be published as its nearest float`() {
        val base = parseFlatZinc(leastSquare).sourceProgram.floatVarsByName.getValue("x").varId

        val result = assertIs<SourceExecutionResult.Quadratic>(execute(leastSquare))

        val point = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result).incumbent.sample
        assertEquals(null, assertNotNull(point.algebraicReals)[base].rational)
        assertEquals(0.9999999, point.approximateRealValue(base))
    }
}
