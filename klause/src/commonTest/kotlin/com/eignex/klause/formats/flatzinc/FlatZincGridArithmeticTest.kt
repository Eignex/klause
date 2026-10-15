package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.table.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlatZincGridArithmeticTest {
    @Test
    fun `grid multiplication retains the relation without a frontend table`() {
        val program = parseFlatZinc("var -1.0..1.0: x; var 0.0..1.0: y; " +
            "constraint float_times(x, x, y); solve satisfy;", floatBuckets = 5)
        val factor = program.problem.factors.filterIsInstance<GridProduct>().single()

        assertEquals(factor.a, factor.b)
        assertEquals(5, factor.a.buckets)
        assertFalse(program.problem.factors.any { it is Table })
        assertTrue(program.floatRepresentations.values.all { it.approximate })
    }

    @Test
    fun `fixed float powers preserve exact and grid representation policies`() {
        val source = "var -2.0..2.0: x; var 0.0..4.0: y; constraint float_pow(x, 2.0, y); solve satisfy;"

        val grid = parseFlatZinc(source, floatBuckets = 5)
        val exact = parseFlatZinc(source, exactFloats = true)

        assertEquals(2, grid.problem.factors.filterIsInstance<GridPower>().single().exponent)
        assertEquals(2, exact.problem.factors.filterIsInstance<Power>().single().exponent)
        assertTrue(grid.floatRepresentations.values.all { it.approximate })
        assertTrue(exact.floatRepresentations.values.none { it.approximate })
    }
}
