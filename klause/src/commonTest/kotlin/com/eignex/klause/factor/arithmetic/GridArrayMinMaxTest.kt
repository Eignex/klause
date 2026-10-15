package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GridArrayMinMaxTest {
    @Test
    fun `signed half unit rounding preserves scaled maximum assignments`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf(
            GridArrayMinMax(intArrayOf(0, 1, 2), DoubleArray(3) { 0.5 }, DoubleArray(3),
                BooleanArray(3), 1L, max = true),
        ))

        val assignments = BacktrackSolver(source.bake()).enumerate().map { it.ints.toList() }.toSet()

        assertEquals(setOf(listOf(0L, 0L, 0L), listOf(1L, 0L, 0L), listOf(2L, 0L, 0L)), assignments)
    }

    @Test
    fun `constant branches keep their signed scaled bounds`() {
        val source = Problem(0, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 0)), arrayOf(
            GridArrayMinMax(intArrayOf(0, 1, 2), doubleArrayOf(0.5, 0.5, 0.0), doubleArrayOf(0.0, 0.0, 0.5),
                booleanArrayOf(false, false, true), 1L, max = true),
        ))

        val assignments = BacktrackSolver(source.bake()).enumerate().map { it.ints.toList() }.toSet()

        assertEquals(setOf(listOf(0L, 0L, 0L), listOf(1L, 0L, 0L), listOf(2L, 0L, 0L)), assignments)
    }

    @Test
    fun `grid extrema share propagated bounds with distinctness conflicts`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf<Factor>(
            GridArrayMinMax(intArrayOf(0, 1, 2), DoubleArray(3) { 1.0 }, DoubleArray(3),
                BooleanArray(3), 1000L, max = false),
            AllDifferent(intArrayOf(0, 1, 2)),
        ))

        val result = BacktrackSolver(source.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `unequal grid units cannot equate equal bucket indexes`() {
        val source = Problem(0, 3, arrayOf(IntDomain(0, 2), IntDomain(1, 1), IntDomain(0, 0)), arrayOf(
            GridArrayMinMax(intArrayOf(0, 1, 2), doubleArrayOf(1.5, 1.0, 1.0), doubleArrayOf(-1.0, -1.0, 0.0),
                BooleanArray(3), 1000L, max = false),
        ))

        val result = BacktrackSolver(source.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }
}
