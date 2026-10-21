package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSearchSolverCancellationTest {

    @Test
    fun `local search samples should stop before exhausting their allowance after cancellation`() {
        val problem = unconstrainedBools(numVars = 20)
        var cancelled = false
        val params = LocalSearchParams(
            maxFlips = 10_000L,
            randomSeed = 1L,
            cancellation = { cancelled },
        )
        val solver = LocalSearchSolver(problem.bake())

        val samples = solver.samples(params).onEach {
            cancelled = true
        }.toList()

        assertTrue(samples.size.toLong() in 1 until params.maxFlips)
        samples.forEach { assertEquals(problem.numBoolVars, it.bools.size) }
    }

    private fun unconstrainedBools(numVars: Int): Problem =
        Problem(numBoolVars = numVars, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
}
