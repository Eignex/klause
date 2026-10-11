package com.eignex.klause.formats.flatzinc

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertTrue

class FlatZincSolutionWriterTest {
    @Test
    fun `mixed components preserve exact coordinates and grid provenance`() {
        val program = parseFlatZinc(
            "var 0.0..1.0: exact; var 0.0..1.0: grid; constraint float_le(grid, 0.5); solve satisfy;",
            floatBuckets = 3,
        )
        val third = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val sample = Sample(
            BooleanArray(program.problem.numBoolVars), longArrayOf(1), doubleArrayOf(third.toDouble()), listOf(third),
        )

        val output = writeFlatZincSolution(program, sample)

        assertTrue("% klause-coordinate: exact = 1/3;" in output)
        assertTrue("% klause-coordinate: grid = 1/2;" in output)
        assertTrue("% klause-float-component: grid = grid" in output)
        assertTrue("reason=continuous component without finite choices" in output)
    }

    @Test
    fun `certified rational coordinates accompany standard decimal assignments`() {
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
    fun `open witnesses retain exact rational coordinates`() {
        val program = parseFlatZinc("var float: x; solve satisfy;", exactFloats = true)
        val third = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val assignment = OpenTheoryAssignment.Sampled(
            Sample(BooleanArray(0), LongArray(0), doubleArrayOf(third.toDouble()), listOf(third)),
        )

        val output = writeFlatZincSolution(program, assignment)

        assertTrue("% klause-exact: x = 1/3;\n" in output)
    }

    @Test
    fun `output model rendering preserves authoritative coordinates`() {
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
    fun `approximate samples cannot claim exact coordinates`() {
        val program = parseFlatZinc("var float: x; solve satisfy;", exactFloats = true)
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.3))

        val output = writeFlatZincSolution(program, sample)

        assertTrue("klause-exact" !in output)
    }
}
