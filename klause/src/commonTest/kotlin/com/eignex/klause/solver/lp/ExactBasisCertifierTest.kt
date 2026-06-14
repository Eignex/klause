package com.eignex.klause.solver.lp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exact BigInt basis-certification (#567 component 4): certifying the exact optimal basis
 *  reproduces [DualSimplex]'s bound; certifying a float basis is a sound lower bound. */
class ExactBasisCertifierTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        for (j in 0 until n) b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7))
        repeat(m) {
            val cols = IntArray(n) { it }
            val vals = LongArray(n) { rng.nextLong(-3, 4) }
            b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 20))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `certifying the exact optimal basis matches DualSimplex`() {
        val rng = Random(20260616)
        var checked = 0
        repeat(800) {
            val model = randomModel(rng.nextInt(3, 8), rng.nextInt(3, 8), rng)
            val exact = try {
                DualSimplex(model).solve()
            } catch (_: LpOverflowException) {
                return@repeat
            }
            if (exact.status != LpStatus.OPTIMAL) return@repeat
            checked++
            // Strong duality at the optimal basis ⇒ exact L(y) ceil == DualSimplex's ceil bound.
            val cert = ExactBasisCertifier.lowerBoundCeil(model, exact.basis)
            assertEquals(exact.objectiveLowerBoundCeil(), cert, "exact-basis certify vs DualSimplex")

            // On the float basis it must stay a sound lower bound on the optimum.
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            val soft = ExactBasisCertifier.lowerBoundCeil(model, rev.basis)
            if (soft != null) {
                assertTrue(
                    soft <= exact.objectiveLowerBoundCeil(),
                    "UNSOUND float-basis certify $soft > ${exact.objectiveLowerBoundCeil()}",
                )
            }
        }
        assertTrue(checked > 300, "covered only $checked instances")
    }
}
