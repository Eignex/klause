package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.FlatZincParseException
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincConstructionTest {

    @Test
    fun `a fixed set variable pins exactly its declared membership indicators`() {
        val program = parseFlatZinc("var set of {1, 2, 3}: s = {1, 3}; solve satisfy;")

        val layout = program.setVarsByName.getValue("s")
        val factors = program.problem.factors.map(::assertIsClause)
        assertEquals(listOf(1, 2, 3), layout.elements.toList())
        assertEquals(3, factors.size)
        assertEquals(
            listOf(true, false, true),
            factors.map { Lit.isPositive(it.literals.single()) },
        )
        assertEquals(layout.indicatorBoolIds.toList(), factors.map { Lit.variable(it.literals.single()) })
    }

    @Test
    fun `duplicate declarations are rejected before a problem can alias variable ids`() {
        val error = assertFailsWith<FlatZincParseException> {
            parseFlatZinc("var bool: x; var 0..1: x; solve satisfy;")
        }

        assertTrue(error.message.orEmpty().contains("duplicate declaration of `x`"))
    }

    @Test
    fun `trailing declarations after solve are rejected`() {
        assertFailsWith<FlatZincParseException> {
            parseFlatZinc("solve satisfy; var bool: x;")
        }
    }

    @Test
    fun `an uninitialized parameter is rejected instead of becoming a solver variable`() {
        assertFailsWith<FlatZincParseException> {
            parseFlatZinc("par 1..2: limit; solve satisfy;")
        }
    }

    @Test
    fun `nonfinite float literals are rejected with a located format error`() {
        val error = assertFailsWith<FlatZincParseException> {
            parseFlatZinc("var 0.0..1e999: x; solve satisfy;")
        }

        assertTrue(error.message.orEmpty().contains("outside the finite range"))
    }

    private fun assertIsClause(factor: com.eignex.klause.solver.Factor): Clause = assertIs<Clause>(factor)
}
