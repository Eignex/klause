package com.eignex.klause.solver.factor

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class ProductTest {

    @Test
    fun productFactorRepairsIncrementally() {
        // a*b = result with a, b ∈ [0..4], result ∈ [0..16].
        val factor = Product(a = 0, b = 1, result = 2)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 16)),
            factors = listOf(factor),
        )
        val solver = LocalSearchSolver(problem, maxFlipsBeforeRestart = 200)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 17)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = s.ints[0]; val b = s.ints[1]; val r = s.ints[2]
            assertTrue(a * b == r, "a=$a b=$b r=$r")
        }
    }
}
