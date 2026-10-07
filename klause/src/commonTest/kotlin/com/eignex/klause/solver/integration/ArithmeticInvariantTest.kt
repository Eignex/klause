package com.eignex.klause.solver.integration

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ProductInvariant
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class ArithmeticInvariantTest {

    // --- ProductTest ---

    @Test
    fun `product factor repairs incrementally`() {
        val factor = Product(a = 0, b = 1, result = 2)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 16)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 17)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = s.ints[0]
            val b = s.ints[1]
            val r = s.ints[2]
            assertTrue(a * b == r, "a=$a b=$b r=$r")
        }
    }

    @Test
    fun `a product past the Long range is never scored as satisfied`() {
        // 2^40 · 2^40 wraps to 0 in Long arithmetic, which the result 0 would match.
        val big = 1L shl 40
        val state = LocalSearchState(Problem(0, 3, Array(3) { IntDomain(0, big) }, emptyArray()).bake(), Random(0))
        state.assignment.setInt(0, big)
        state.assignment.setInt(1, big)
        state.assignment.setInt(2, 0L)

        val invariant = ProductInvariant(a = 0, b = 1, result = 2)

        assertTrue(invariant.isViolated(state, 0))
        assertTrue(invariant.violationDegree(state, 0) > 0)
    }
}
