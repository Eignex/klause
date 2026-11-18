package com.eignex.klause.backtrack

import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs

internal class BacktrackSolverRealRowsTest {
    @Test
    @Suppress("FunctionNaming")
    internal fun `a leaf with more than a thousand real rows should be completed`() {
        for (reals in listOf(2, 70)) {
            val result = BacktrackSolver(manyRealRows(1000L, reals).bake()).solve(BacktrackParams(randomSeed = 1L))

            assertIs<SolveResult.Sat>(result)
        }
    }

    @Test
    @Suppress("FunctionNaming")
    internal fun `a leaf with more than a thousand real rows should be refuted`() {
        for (reals in listOf(2, 70)) {
            val result = BacktrackSolver(manyRealRows(-2L, reals).bake()).solve(BacktrackParams(randomSeed = 1L))

            assertIs<SolveResult.Unsat>(result)
        }
    }
}
