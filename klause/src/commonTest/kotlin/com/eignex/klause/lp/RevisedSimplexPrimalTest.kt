package com.eignex.klause.lp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [RevisedSimplex.solvePrimal] — the bounded-variable primal phase-2 pass — must reach the same optimum
 * as the dual [RevisedSimplex.solve] (both confirmed by the integer-multiplier bound), and its
 * bound-flipping ratio test must take the long step when an entering variable hits its own bound first.
 */
class RevisedSimplexPrimalTest {

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
            // The primal basis must certify to a sound integer bound, exactly like the dual path.
            val bound = integerDualLowerBoundCeil(model, primal.duals) ?: return@repeat
            assertTrue(
                bound.toDouble() <= ceil(primal.objective) + 1e-6,
                "primal certified bound $bound > ceil(obj ${primal.objective})",
            )
        }
        assertTrue(converged > 200, "primal converged on only $converged instances")
    }

    /** `≥`-rows with positive rhs (covering): the all-lower start is primal-infeasible (every slack
     *  starts negative), so [RevisedSimplex.solvePrimal] must run phase-1 to recover feasibility. */
    private fun randomCoveringModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 6), cost = rng.nextLong(0, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) { b.addRow(cols, LongArray(n) { rng.nextLong(1, 4) }, Relation.GE, rng.nextLong(1, 9)) }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `primal phase-1 recovers feasibility on covering models`() {
        val rng = Random(20260622)
        var converged = 0
        repeat(500) {
            val model = randomCoveringModel(rng.nextInt(2, 6), rng.nextInt(3, 7), rng)
            val primal = RevisedSimplex(model).solvePrimal() ?: return@repeat // infeasible models skip
            val dual = RevisedSimplex(model).solve() ?: return@repeat
            converged++
            assertTrue(
                abs(primal.objective - dual.objective) <= 1e-6 * maxOf(1.0, abs(dual.objective)),
                "primal obj ${primal.objective} vs dual ${dual.objective}",
            )
            val bound = integerDualLowerBoundCeil(model, primal.duals) ?: return@repeat
            assertTrue(
                bound.toDouble() <= ceil(primal.objective) + 1e-6,
                "primal certified bound $bound > ceil(obj ${primal.objective})",
            )
        }
        assertTrue(converged > 100, "primal phase-1 converged on only $converged covering instances")
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

    @Test
    fun `primal solves a degenerate LP to the dual optimum`() {
        // Overlapping/redundant constraints create degenerate vertices (several basics at zero), the
        // setting where the Dantzig rule could cycle and Bland's rule keeps termination. maximize the
        // sum (minimize its negation): optimum 3, e.g. (1,1,1,0).
        val b = LpBuilder()
        repeat(4) { b.addVar(0L, 3L, cost = -1L) }
        val all = intArrayOf(0, 1, 2, 3)
        b.addRow(all, longArrayOf(1L, 1L, 1L, 1L), Relation.LE, 3L)
        b.addRow(all, longArrayOf(1L, 1L, 1L, 1L), Relation.LE, 3L) // redundant ⇒ degeneracy
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.LE, 2L)
        b.addRow(intArrayOf(2, 3), longArrayOf(1L, 1L), Relation.LE, 2L)
        val model = b.build(Sense.MINIMIZE)
        val primal = RevisedSimplex(model).solvePrimal()
        val dual = RevisedSimplex(model).solve()
        assertTrue(primal != null && dual != null, "both engines should solve the degenerate LP")
        assertTrue(
            abs(primal.objective - dual.objective) <= 1e-6 * maxOf(1.0, abs(dual.objective)),
            "primal ${primal.objective} vs dual ${dual.objective}",
        )
        assertTrue(abs(primal.objective - (-3.0)) <= 1e-9, "optimum ${primal.objective} should be -3")
    }
}
