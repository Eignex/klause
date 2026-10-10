package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertTrue

class FlatZincSolutionWriterTest {
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
        val program = parseFlatZinc("var float: x; solve satisfy;", exactFloats = true)
        val third = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(third.toDouble()), listOf(third))
        val applier = OznApplier("float: x; output [show(x)];")

        val output = applier.render(program, sample)

        assertTrue("% klause-exact: x = 1/3;\n" in output)
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
