package com.eignex.klause.formats.flatzinc

import com.eignex.klause.formats.minizinc.OznParseException
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OznApplierTest {
    @Test
    fun `finite set bindings widen elements at the output boundary`() {
        val program = parseFlatZinc("var set of {-2147483648,2147483647}: s; solve satisfy;")
        val sample = Sample(BooleanArray(program.problem.numBoolVars) { true }, LongArray(0))
        val applier = OznApplier("set of int: s; output [show(s)];")

        val out = applier.render(program, sample)

        assertTrue("{-2147483648, 2147483647}" in out, out)
    }

    @Test
    fun `set parameter arrays preserve signed elements in output`() {
        val program = parseFlatZinc(
            "array[1..2] of set of int: a = [{-2147483648},{2147483647}]; solve satisfy;",
        )
        val sample = Sample(BooleanArray(0), LongArray(0))
        val applier = OznApplier("array[1..2] of set of int: a; output [show(a)];")

        val out = applier.render(program, sample)

        assertTrue("[{-2147483648}, {2147483647}]" in out, out)
    }

    @Test
    fun `integer bindings outside the rendering range report an evaluation error`() {
        val program = parseFlatZinc("var int: x; solve satisfy;")
        val sample = Sample(BooleanArray(0), LongArray(0), exactInts = listOf(parseBigInt("9223372036854775808")))
        val applier = OznApplier("int: x; output [show(x)];")

        assertFailsWith<OznParseException> { applier.render(program, sample) }
    }
}
