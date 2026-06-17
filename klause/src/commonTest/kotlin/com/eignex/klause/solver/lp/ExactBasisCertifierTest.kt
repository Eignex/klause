package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Exact BigInt basis-certification (#567 / #705): at the float-optimal basis the certify is tight,
 *  and [ExactBasisCertifier.lowerBoundCeil] is exactly `ceil(LP optimum)` — a sound integer bound. */
class ExactBasisCertifierTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) { b.addRow(cols, LongArray(n) { rng.nextLong(-3, 4) }, Relation.LE, rng.nextLong(3, 20)) }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `certify is tight at the optimal basis and the ceil bound is sound`() {
        val rng = Random(20260616)
        var checked = 0
        repeat(800) {
            val model = randomModel(rng.nextInt(3, 8), rng.nextInt(3, 8), rng)
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            val cert = ExactBasisCertifier.certify(model, rev.basis) ?: return@repeat
            val ceil = ExactBasisCertifier.lowerBoundCeil(model, rev.basis) ?: return@repeat
            checked++
            val exact = cert.objective.num.toDouble() / cert.objective.den.toDouble()
            assertTrue(
                abs(exact - rev.objective) <= 1e-6 * maxOf(1.0, abs(exact)),
                "certify $exact vs float ${rev.objective}",
            )
            assertTrue(
                ceil.toDouble() >= exact - 1e-6 && ceil.toDouble() < exact + 1.0 + 1e-6,
                "ceil $ceil not round(optimum $exact)",
            )
        }
        assertTrue(checked > 300, "covered only $checked instances")
    }
}
