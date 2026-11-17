package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.prepareSourceExecution
import com.eignex.klause.formats.flatzinc.sourceAttachment
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialToleranceTest {
    // Over the declared ranges the least product is 2 and the least square 1, so an upper bound just below either is
    // met only within the slack.
    private fun product(bound: String) = """
        var 1.0..1.5: x;
        var 2.0..3.0: w;
        var -10.0..$bound: z;
        constraint float_times(x, w, z);
        solve satisfy;
    """.trimIndent()

    private fun power(bound: String) = """
        var 1.0..2.0: x;
        var -10.0..$bound: y;
        constraint float_pow(x, 2.0, y);
        solve satisfy;
    """.trimIndent()

    private val withinTolerance = mapOf("product" to product("1.99999995"), "power" to power("0.99999995"))

    private val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())

    private fun execute(
        source: String,
        overrides: SemanticOverrides = SemanticOverrides(),
        params: PolynomialTheoryParams = PolynomialTheoryParams(),
    ): SourceExecutionResult {
        val program = parseFlatZinc(source, semanticOverrides = overrides)
        val objective = program.sourceProgram.linearObjective()
        val invocation = program.prepareSourceExecution(SourceSetupRequest(program.sourceAttachment(),
            if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
            FiniteEngine.BACKTRACK, objective = objective))
        return SourceSetup.execute(SourceExecutionRequest(invocation, polynomial = params))
    }

    private fun verdict(source: String, overrides: SemanticOverrides = SemanticOverrides()): String? =
        when (val result = execute(source, overrides)) {
            is SourceExecutionResult.Polynomial -> result.result.result::class.simpleName
            is SourceExecutionResult.Refuted -> "Unsat"
            else -> result::class.simpleName
        }

    // The point a frontend prints: every real at the float it renders, with no rational authority.
    private fun printed(point: OpenTheoryAssignment, source: String): Sample {
        val problem = parseFlatZinc(source).sourceProgram.problem
        return Sample(BooleanArray(problem.numBoolVars) { point.boolValue(it) }, LongArray(0),
            DoubleArray(problem.numRealVars) { id ->
                val value = assertNotNull(point.algebraicRealValue(id))
                value.lowerBound.toDouble() / 2 + value.upperBound.toDouble() / 2
            })
    }

    private fun accepted(sample: Sample, source: String): Boolean =
        assertNotNull(capturedRealAcceptance(parseFlatZinc(source).sourceProgram.problem, ExecutionControl.Never))(
            sample)

    @Test
    fun `an exactly feasible product should publish an exact point at zero tolerance`() {
        val source = product("2.5")

        val point = assertIs<OpenTheoryResult.Sat>(
            assertIs<SourceExecutionResult.Polynomial>(execute(source, exact)).result.result).assignment

        val (x, w, z) = List(3) { assertNotNull(assertNotNull(point.algebraicRealValue(it)).rational) }
        assertEquals(z, x * w)
    }

    @Test
    fun `relations satisfiable only within the tolerance should be satisfied by default`() {
        val verdicts = withinTolerance.mapValues { verdict(it.value) }

        assertEquals(withinTolerance.mapValues { "Sat" }, verdicts)
    }

    @Test
    fun `relations satisfiable only within the tolerance should be refuted at zero tolerance`() {
        val verdicts = withinTolerance.mapValues { verdict(it.value, exact) }

        assertEquals(withinTolerance.mapValues { "Unsat" }, verdicts)
    }

    @Test
    fun `a product violated beyond the tolerance should be refuted by default`() {
        assertEquals("Unsat", verdict(product("1.9999")))
    }

    @Test
    fun `a power violated beyond the tolerance should be refuted by default`() {
        assertEquals("Unsat", verdict(power("0.9999")))
    }

    @Test
    fun `a negative base satisfiable only within the tolerance should be satisfied by default`() {
        val cube = """
            var -2.0..-1.0: x;
            var -0.99999995..10.0: y;
            constraint float_pow(x, 3.0, y);
            solve satisfy;
        """.trimIndent()

        assertEquals("Sat", verdict(cube))
    }

    @Test
    fun `a false activator should keep the exact complement of its row`() {
        val reified = """
            var 0.0..2.0: x;
            var 0.0..4.0: y;
            var bool: b;
            constraint float_le_reif(x, 1.0, b);
            constraint bool_clause([], [b]);
            constraint float_lt(x, 1.0);
            constraint float_times(x, x, y);
            solve satisfy;
        """.trimIndent()

        assertEquals("Unsat", verdict(reified))
    }

    @Test
    fun `a point satisfiable only within the tolerance should print floats the source acceptance admits`() {
        val source = withinTolerance.getValue("product")

        val point = assertIs<OpenTheoryResult.Sat>(
            assertIs<SourceExecutionResult.Polynomial>(execute(source)).result.result).assignment

        assertTrue(accepted(printed(point, source), source))
    }

    @Test
    fun `a widened decision stopped at its limit should leave the source undecided`() {
        val params = PolynomialTheoryParams(maximumWork = WIDENING_LIMIT)

        val result = assertIs<SourceExecutionResult.Polynomial>(execute(withinTolerance.getValue("product"),
            params = params))

        assertIs<OpenTheoryResult.Unknown>(result.result.result)
    }

    @Test
    fun `an exact refutation under a tolerance without an absolute part should leave the source undecided`() {
        val relative = SemanticOverrides(feasibilityTolerance = SourceTolerance(
            relative = SemanticRational.of(bigIntOf(1L), bigIntOf(1_000_000L))))

        assertNotEquals("Unsat", verdict(withinTolerance.getValue("product"), relative))
    }

    private val leastRoot = power("10.0").replace("solve satisfy;", "solve minimize x;")

    private fun optimum(source: String, overrides: SemanticOverrides = SemanticOverrides()): QuadraticIncumbent =
        assertIs<QuadraticOptimizationResult.WithIncumbent>(
            assertIs<SourceExecutionResult.Quadratic>(execute(source, overrides)).result.result).incumbent

    @Test
    fun `a minimization should report the exact optimum at zero tolerance`() {
        val result = assertIs<SourceExecutionResult.Quadratic>(execute(leastRoot, exact))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertEquals(BigFraction.ONE, optimum.incumbent.value.rational)
    }

    @Test
    fun `a minimization should report the optimum within the tolerance by default`() {
        val value = assertNotNull(optimum(leastRoot).value.rational)

        assertTrue(value < BigFraction.ONE && value >= BigFraction.ONE - SLACK)
    }

    @Test
    fun `a minimization satisfiable only within the tolerance should attain its optimum by default`() {
        val result = assertIs<SourceExecutionResult.Quadratic>(execute(
            power("0.99999995").replace("solve satisfy;", "solve minimize x;")))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertTrue(assertNotNull(optimum.incumbent.value.rational) >= BigFraction.ONE - SLACK)
    }

    @Test
    fun `a widened optimum should report the objective of the floats it prints`() {
        val incumbent = optimum(leastRoot)

        assertEquals(BigFraction.ofDouble(incumbent.sample.approximateRealValue(0)), incumbent.value.rational)
    }

    @Test
    fun `a zero tolerance should declare no widening`() {
        val source = withinTolerance.getValue("product")
        val problem = parseFlatZinc(source, semanticOverrides = exact).sourceProgram.problem

        assertNull(PolynomialTolerance.of(problem))
    }

    @Test
    fun `a declared tolerance should leave the routed formula exact`() {
        val program = parseFlatZinc(withinTolerance.getValue("product"))
        val invocation = program.prepareSourceExecution(SourceSetupRequest(program.sourceAttachment(),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val route = assertIs<SourceProblemRoute.PolynomialTheory>(invocation.engineRoute)

        assertNull(route.request.resolvedModel().tolerance)
    }

    private companion object {
        // Less native work than the widened refutation of the product takes.
        const val WIDENING_LIMIT = 20_000L

        val SLACK: BigFraction = BigFraction.of(bigIntOf(1L), bigIntOf(10_000_000L))
    }
}
