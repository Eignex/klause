package com.eignex.klause.formats.flatzinc

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.RationalInterval
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.shl
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincSolutionWriterTest {
    @Test
    fun `irrational source coordinates should retain their selected exact root`() {
        val program = parseFlatZinc("var 0.0..2.0: x :: output_var; solve minimize x;", exactFloats = true)
        val root = AlgebraicArithmetic().roots(RationalPolynomial(
            listOf(BigFraction.ofLong(-2), BigFraction.ZERO, BigFraction.ONE))).last()
        val assignment = OpenTheoryAssignment.ExactNra(ExactNraAssignment(BooleanArray(0), listOf(root)))

        val output = writeFlatZincSolution(program.sourceProgram, assignment, outputObjective = true)

        assertTrue("% klause-exact: x = root(2;[-2,0,1]);" in output.lines(), output)
        assertTrue("% klause-exact: _objective = root(2;[-2,0,1]);" in output.lines(), output)
        assertTrue(output.endsWith("----------\n"), output)
    }

    @Test
    fun `solved algebraic coordinates should print their nearest binary64 value`() {
        for ((square, printed) in listOf("1.0" to 1.0, "2.0" to sqrt(2.0))) {
            val program = parseFlatZinc(
                "var 0.0..3.0: x :: output_var; var 0.0..3.0: y; constraint float_times(x, x, y); " +
                    "constraint float_eq(y, $square); solve satisfy;",
                exactFloats = true,
            )
            val request = program.problem.pipelineRequest()
            val execution = OpenTheoryPipeline.execute(request, engine = FiniteEngine.BACKTRACK)
            val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment

            val output = writeFlatZincSolution(program.sourceProgram, witness)

            assertTrue("x = $printed;" in output.lines(), output)
        }
    }

    @Test
    fun `a coordinate past binary64 should print the largest finite value as an overflow`() {
        val program = parseFlatZinc("var float: x :: output_var; solve satisfy;", exactFloats = true)
        fun power(exponent: Int) = BigFraction.of(BIG_ONE shl exponent, BIG_ONE)
        val root = AlgebraicReal(RationalPolynomial(listOf(power(2100).negated(), BigFraction.ZERO, BigFraction.ONE)),
            2, RationalInterval(power(1049), power(1051)))
        val rational = power(1100)
        val outputs = listOf(
            "algebraic" to writeFlatZincSolution(program.sourceProgram,
                OpenTheoryAssignment.ExactNra(ExactNraAssignment(BooleanArray(0), listOf(root)))),
            "rational" to writeFlatZincSolution(program.sourceProgram,
                Sample(BooleanArray(0), LongArray(0), doubleArrayOf(rational.toDouble()), listOf(rational))),
        )
        for ((name, output) in outputs) {
            assertTrue("x = ${Double.MAX_VALUE};" in output.lines(), "$name\n$output")
            assertTrue("% klause-float-rounding: x = overflow" in output.lines(), "$name\n$output")
        }
    }

    @Test
    fun `a narrowing out of allowance should print the interval midpoint as undetermined`() {
        val program = parseFlatZinc("var 0.0..2.0: x :: output_var; solve satisfy;", exactFloats = true)
        val root = AlgebraicArithmetic().roots(RationalPolynomial.of(-1, 0, 1)).last()
        val assignment = OpenTheoryAssignment.ExactNra(ExactNraAssignment(BooleanArray(0), listOf(root)))

        val output = writeFlatZincSolution(program.sourceProgram, assignment, false, ExecutionControl { true })

        assertTrue("x = ${root.approximateDouble};" in output.lines(), output)
        assertTrue("% klause-float-rounding: x = undetermined" in output.lines(), output)
    }

    @Test
    fun `certified rational coordinates should accompany standard decimal assignments`() {
        val program = parseFlatZinc("var float: x; solve minimize x;", exactFloats = true)
        val third = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(third.toDouble()), listOf(third))

        val output = writeFlatZincSolution(program, sample, outputObjective = true)

        assertTrue("x = 0.3333333333333333;\n" in output)
        assertTrue("% klause-exact: x = 1/3;\n" in output)
        assertTrue("% klause-exact: _objective = 1/3;\n" in output)
        assertTrue(output.endsWith("----------\n"))
    }

    @Test
    fun `open witnesses should retain exact rational coordinates`() {
        val program = parseFlatZinc("var float: x; solve satisfy;", exactFloats = true)
        val third = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), LongArray(0), doubleArrayOf(third.toDouble()), listOf(third)),
        )

        val output = writeFlatZincSolution(program, assignment)

        assertTrue("% klause-exact: x = 1/3;\n" in output)
    }

    @Test
    fun `output model rendering should preserve authoritative coordinates`() {
        val program = parseFlatZinc("var float: x; solve minimize x;", exactFloats = true)
        val third = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(third.toDouble()), listOf(third))
        val applier = OznApplier("float: x; output [show(x)];")

        val output = applier.render(program, sample)

        assertTrue("% klause-exact: x = 1/3;\n" in output)
        assertTrue("% klause-exact: _objective = 1/3;\n" in output)
        assertTrue(output.endsWith("----------\n"))
    }

    @Test
    fun `approximate samples should not claim exact coordinates`() {
        val program = parseFlatZinc("var float: x; solve satisfy;", exactFloats = true)
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.3))

        val output = writeFlatZincSolution(program, sample)

        assertTrue("klause-exact" !in output)
    }

    @Test
    fun `solution writer should render custom output items`() {
        val src = """
            var 0..5: a;
            var 0..5: b;
            constraint int_lin_eq([1, 1], [a, b], 3);
            solve satisfy;
            output ["a=", show(a), " b=", show(b), "\n"];
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem.bake()).sample(BacktrackParams(randomSeed = 0L)).assignment!!
        val rendered = writeFlatZincSolution(program, sample)
        assertTrue(rendered.startsWith("a="), "got: $rendered")
        assertTrue(rendered.contains(" b="), "got: $rendered")
        assertTrue(rendered.contains("----------"))
        assertEquals(3, sample.ints[0] + sample.ints[1])
    }

    @Test
    fun `solution writer should reconstruct set output from indicators`() {
        val src = """
            var set of 1..3: s;
            constraint set_in(1, s);
            constraint set_in(3, s);
            constraint set_card(s, 2);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val output = writeFlatZincSolution(program, sat.assignment)
        assertTrue(output.contains("s = {1, 3};"), "got: $output")
    }
}
