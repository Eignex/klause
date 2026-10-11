package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LocalSearchRunTest {
    @Test
    fun `an incumbent consumer failure closes the walk and releases the solver`() {
        val solver = LocalSearchSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())
        val params = LocalSearchParams(maxFlips = 1L, randomSeed = 7L)
        val walk = solver.resumable(LinearObjective(), params)

        assertFailsWith<IllegalArgumentException> {
            walk.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { throw IllegalArgumentException("consumer") }
        }

        val next = solver.resumableSolve(params)
        walk.close()
        assertFailsWith<IllegalStateException> { solver.resumableSolve(params) }
        assertFailsWith<IllegalStateException> {
            walk.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}
        }
        assertIs<SolveResult.Sat>(next.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
        next.close()
    }
}
