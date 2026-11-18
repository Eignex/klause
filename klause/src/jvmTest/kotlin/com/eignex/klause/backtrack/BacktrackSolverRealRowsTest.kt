package com.eignex.klause.backtrack

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class BacktrackSolverRealRowsTest(private val reals: Int) : RuntimeInitializedTest() {
    @Test
    fun `a leaf with more than a thousand real rows should be completed`() {
        val result = BacktrackSolver(manyRealRows(1000L, reals).bake()).solve(BacktrackParams(randomSeed = 1L))

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `a leaf with more than a thousand real rows should be refuted`() {
        val result = BacktrackSolver(manyRealRows(-2L, reals).bake()).solve(BacktrackParams(randomSeed = 1L))

        assertIs<SolveResult.Unsat>(result)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "reals={0}")
        fun widths(): List<Array<Int>> = listOf(arrayOf(2), arrayOf(70))
    }
}
