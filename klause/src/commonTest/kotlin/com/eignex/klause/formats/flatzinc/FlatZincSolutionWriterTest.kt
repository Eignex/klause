package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincSolutionWriterTest {
    @Test
    fun `mixed components should preserve exact coordinates and grid provenance`() {
        val program = parseFlatZinc(
            "var 0.0..1.0: exact; var 0.0..1.0: grid; constraint float_le(grid, 0.5); solve satisfy;",
            floatBuckets = 3,
        )
        val third = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val sample = Sample(
            BooleanArray(program.problem.numBoolVars),
            longArrayOf(1),
            doubleArrayOf(third.toDouble()),
            listOf(third),
        )

        val output = writeFlatZincSolution(program, sample)

        assertTrue("% klause-coordinate: exact = 1/3;" in output)
        assertTrue("% klause-coordinate: grid = 1/2;" in output)
        assertTrue("% klause-float-component: grid = grid" in output)
        assertTrue("reason=continuous component without finite choices" in output)
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
