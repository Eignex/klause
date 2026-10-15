package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSearchSolverCancellationTest {

    @Test
    fun `local search samples should stop promptly after cancellation`() {
        val problem = unconstrainedBools(numVars = 20)
        val cancel = AtomicBoolean(false)
        val params = LocalSearchParams(
            maxFlips = Long.MAX_VALUE,
            randomSeed = 1L,
            cancellation = { cancel.get() },
        )
        val solver = LocalSearchSolver(problem.bake())

        val flagger = thread(start = true) {
            Thread.sleep(50)
            cancel.set(true)
        }
        val started = System.currentTimeMillis()
        val samples = solver.samples(params).toList()
        val elapsed = System.currentTimeMillis() - started
        flagger.join()

        assertTrue(elapsed < 10_000, "took ${elapsed}ms")
        samples.forEach { assertEquals(problem.numBoolVars, it.bools.size) }
    }

    private fun unconstrainedBools(numVars: Int): Problem =
        Problem(numBoolVars = numVars, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
}
