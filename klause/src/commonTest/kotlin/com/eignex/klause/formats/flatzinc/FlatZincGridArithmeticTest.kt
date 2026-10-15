package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.table.Table
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlatZincGridArithmeticTest {
    @Test
    fun `grid multiplication should retain the relation without a frontend table`() {
        val program = parseFlatZinc(
            "var -1.0..1.0: x; var 0.0..1.0: y; " +
                "constraint float_times(x, x, y); solve satisfy;",
            floatBuckets = 5,
        )
        val factor = program.problem.factors.filterIsInstance<GridProduct>().single()

        assertEquals(factor.a, factor.b)
        assertEquals(5, factor.a.buckets)
        assertFalse(program.problem.factors.any { it is Table })
        assertTrue(program.floatRepresentations.values.all { it.approximate })
    }

    @Test
    fun `overflowing grid ranges should decline with a format diagnostic`() {
        val source = "var -1.0e308..1.0e308: x; var 0.0..1.0: y; " +
            "constraint float_times(x, x, y); solve satisfy;"

        assertFailsWith<UnsupportedFlatZincException> { parseFlatZinc(source) }
    }

    @Test
    fun `fixed float powers should preserve exact and grid representation policies`() {
        val source = "var -2.0..2.0: x; var 0.0..4.0: y; constraint float_pow(x, 2.0, y); solve satisfy;"

        val grid = parseFlatZinc(source, floatBuckets = 5)
        val exact = parseFlatZinc(source, exactFloats = true)

        assertEquals(2, grid.problem.factors.filterIsInstance<GridPower>().single().exponent)
        assertEquals(2, exact.problem.factors.filterIsInstance<Power>().single().exponent)
        assertTrue(grid.floatRepresentations.values.all { it.approximate })
        assertTrue(exact.floatRepresentations.values.none { it.approximate })
    }

    @Test
    fun `FlatZinc grid absolute value should retain its source relation`() {
        val parsed = parseFlatZinc(
            "var -2.0..2.0: x; var 0.0..2.0: y; constraint float_abs(x,y); solve satisfy;",
            floatBuckets = 5,
        )
        val source = parsed.problem
        val relation = source.factors.filterIsInstance<GridAbs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map {
            it.ints[relation.operand] to it.ints[relation.result]
        }.toSet()

        assertEquals(setOf(0L to 4L, 1L to 2L, 2L to 0L, 3L to 2L, 4L to 4L), solutions)
    }

    @Test
    fun `unrepresentable grid absolute values should retain the frontend decline`() {
        assertFailsWith<FlatZincParseException> {
            parseFlatZinc(
                "var 2.0..3.0: x; var 0.0..0.2: y; constraint float_abs(x,y); solve satisfy;",
                floatBuckets = 5,
            )
        }
    }
}
