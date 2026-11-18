package com.eignex.klause.cli

import com.eignex.klause.formats.flatzinc.OznApplier
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class MiniZincOutputTest(private val direction: String, private val exact: Boolean, private val ozn: Boolean) {
    private val program = parseFlatZinc("var float: x; solve $direction x;", exactFloats = exact)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, exact={1}, ozn={2}")
        fun cases(): List<Array<Any>> = listOf("minimize", "maximize").flatMap { direction ->
            listOf(false, true).flatMap { exact ->
                listOf(false, true).map { ozn -> arrayOf(direction, exact, ozn) }
            }
        }
    }

    @Test
    fun `published objective should use the selected coordinate semantics`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(third.toDouble()), listOf(third))
        val rendered = if (ozn) {
            OznApplier("float: x; output [\"x = \", show(x), \";\\n\"];").render(program, sample)
        } else writeFlatZincSolution(program, sample)
        val output = MiniZincOutput(sourceSemantics = program.semantics, floatSource = true)

        val text = captureStdout { output.onArithmeticIncumbent(rendered, SourceValue.Rational(third)) }

        val expected = if (exact) third.toString() else text.lineSequence().first { it.startsWith("x = ") }
            .removePrefix("x = ").removeSuffix(";")
        assertEquals(listOf("% objective=$expected"), text.lines().filter { it.startsWith("% objective=") })
    }

    @Test
    fun `exact coordinate metadata should retain its own objective without the output flag`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(third.toDouble()), listOf(third))

        val text = if (ozn) OznApplier("float: x; output [show(x)];").render(program, sample)
        else writeFlatZincSolution(program, sample)

        assertTrue("% klause-exact: x = 1/3;" in text.lines(), text)
        assertTrue("% klause-exact: _objective = 1/3;" in text.lines(), text)
    }
}
