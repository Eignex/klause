package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Product
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class ProductTest {

    @Test
    fun `product factor repairs incrementally`() {
        val factor = Product(a = 0, b = 1, result = 2)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 16)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 17)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = s.ints[0]
            val b = s.ints[1]
            val r = s.ints[2]
            assertTrue(a * b == r, "a=$a b=$b r=$r")
        }
    }
}
