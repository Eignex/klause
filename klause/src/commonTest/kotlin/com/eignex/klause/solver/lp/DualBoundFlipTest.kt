package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The dual simplex's bound-flipping (long-step) ratio test must reach the same optimum as the plain
 * minimum-ratio test, since both only change the pivot path and the basis is certified downstream. The
 * flip path is exercised by bounded variables; a mix of `≤` and `≥` rows gives non-trivial duals.
 */
class DualBoundFlipTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) {
            val rel = if (rng.nextBoolean()) Relation.LE else Relation.GE
            val rhs = if (rel == Relation.LE) rng.nextLong(3, 20) else rng.nextLong(0, 6)
            b.addRow(cols, LongArray(n) { rng.nextLong(-3, 4) }, rel, rhs)
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `bound-flipping ratio test reaches the same optimum as the plain ratio test`() {
        val rng = Random(20260624)
        var compared = 0
        repeat(600) {
            val model = randomModel(rng.nextInt(3, 9), rng.nextInt(3, 9), rng)
            val flip = RevisedSimplex(model, boundFlip = true).solve()
            val plain = RevisedSimplex(model, boundFlip = false).solve()
            // Both engines agree on solvability (feasible/infeasible) for the same model.
            if (flip == null || plain == null) {
                assertTrue(flip == null && plain == null, "one engine solved and the other did not")
                return@repeat
            }
            compared++
            assertTrue(
                abs(flip.objective - plain.objective) <= 1e-6 * maxOf(1.0, abs(plain.objective)),
                "bound-flip obj ${flip.objective} vs plain ${plain.objective}",
            )
            // The bound-flip basis must certify to its float optimum, like every other solve path.
            val cert = ExactBasisCertifier.certify(model, flip.basis) ?: return@repeat
            val exact = cert.objective.num.toDouble() / cert.objective.den.toDouble()
            assertTrue(
                abs(flip.objective - exact) <= 1e-6 * maxOf(1.0, abs(exact)),
                "bound-flip float obj ${flip.objective} vs exact certify $exact",
            )
        }
        assertTrue(compared > 200, "compared on only $compared instances")
    }
}
