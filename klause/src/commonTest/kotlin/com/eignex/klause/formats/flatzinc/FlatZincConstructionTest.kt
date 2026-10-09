package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincConstructionTest {

    @Test
    fun `integer set aliases intersect the original domain`() {
        val cases = listOf(
            "1..3" to "3, 1, 3, 4",
            "{1, 3, 5}" to "1, 2, 3",
            "1..5" to "1, 3",
        )
        for ((domain, values) in cases) {
            val program = parseFlatZinc("var $domain: x; var {$values}: y = x; solve satisfy;")

            val declared = program.problem.declaredIntDomains.finiteDomain(0).span()
            assertEquals(listOf(1L, 3L), List(declared.size) { declared.valueAt(it) })
        }
    }

    @Test
    fun `integer set aliases reject excluded pinned values`() {
        for (declaration in listOf("var {1, 3}: y = x;", "var {1, 2}: y = x; var {1, 3}: z = y;")) {
            val program = parseFlatZinc("var 1..3: x; $declaration constraint int_eq(x, 2); solve satisfy;")

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `disjoint integer alias domains are unsatisfiable`() {
        val program = parseFlatZinc("var {1, 3}: x; var {2, 4}: y = x; solve satisfy;")

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `explicit integer sets preserve their distinct values across wide gaps`() {
        val cases = listOf(
            "32461759, 8, 0, 8" to listOf(0L, 8L, 32461759L),
            "8, -1000000000000, 0, 8" to listOf(-1000000000000L, 0L, 8L),
            "9223372036854775807" to listOf(Long.MAX_VALUE),
        )
        for ((entries, expected) in cases) {
            for (declaration in listOf("var {$entries}: x;", "array[1..2] of var {$entries}: x;")) {
                val program = parseFlatZinc("$declaration solve satisfy;")

                for (id in 0 until program.problem.numIntVars) {
                    val domain = program.problem.declaredIntDomains.finiteDomain(id)
                    val values = domain.span()
                    assertEquals(expected, List(values.size) { values.valueAt(it) })
                    assertEquals(expected.first(), domain.min)
                    assertEquals(expected.last(), domain.max)
                }
            }
        }
    }

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

    private fun assertIsClause(factor: com.eignex.klause.ir.Factor): Clause = assertIs<Clause>(factor)
}
