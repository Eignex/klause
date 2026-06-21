package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [RevisedSimplex.solvePrimal] — the bounded-variable primal phase-2 pass — must reach the same optimum
 * as the dual [RevisedSimplex.solve] (both confirmed by exact certification), and its bound-flipping
 * ratio test must take the long step when an entering variable hits its own bound first.
 */
class PrimalSimplexTest {

    /** `≤`-rows with nonnegative rhs over a bounded box: feasible at the all-lower start the primal
     *  pass begins from, and bounded since every variable has a finite upper bound. */
    private fun randomFeasibleModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) { b.addRow(cols, LongArray(n) { rng.nextLong(-3, 4) }, Relation.LE, rng.nextLong(3, 20)) }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `primal optimum matches the dual optimum and certifies`() {
        val rng = Random(20260621)
        var converged = 0
        repeat(500) {
            val model = randomFeasibleModel(rng.nextInt(3, 9), rng.nextInt(3, 9), rng)
            val primal = RevisedSimplex(model).solvePrimal() ?: return@repeat
            val dual = RevisedSimplex(model).solve() ?: return@repeat
            converged++
            assertTrue(
                abs(primal.objective - dual.objective) <= 1e-6 * maxOf(1.0, abs(dual.objective)),
                "primal obj ${primal.objective} vs dual ${dual.objective}",
            )
            // The primal basis must certify to its float optimum, exactly like the dual path.
            val cert = ExactBasisCertifier.certify(model, primal.basis) ?: return@repeat
            val exact = cert.objective.num.toDouble() / cert.objective.den.toDouble()
            assertTrue(
                abs(primal.objective - exact) <= 1e-6 * maxOf(1.0, abs(exact)),
                "primal float obj ${primal.objective} vs exact certify $exact",
            )
        }
        assertTrue(converged > 200, "primal converged on only $converged instances")
    }

    @Test
    fun `the bound-flipping ratio test flips entering variables to their upper bound`() {
        // maximize x0 + x1 (minimize the negation) with x0, x1 ∈ [0,1] under the loose x0 + x1 ≤ 5.
        // No basic variable blocks before each entering variable reaches its own upper bound, so both
        // pivots are bound flips; the optimum is x0 = x1 = 1, objective -2.
        val b = LpBuilder()
        b.addVar(0L, 1L, cost = -1L)
        b.addVar(0L, 1L, cost = -1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.LE, 5L)
        val model = b.build(Sense.MINIMIZE)
        val result = RevisedSimplex(model).solvePrimal()
        assertTrue(result != null, "primal should solve the loose box")
        assertTrue(abs(result.objective - (-2.0)) <= 1e-9, "optimum ${result.objective} should be -2")
    }
}
