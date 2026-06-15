package com.eignex.klause.solver.lp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Neumaier–Shcherbina safe bound must never exceed the true optimum (#567 component 3): a
 *  sound lower bound on `min cᵀz`, validated against the exact [DualSimplex]. */
class SafeObjectiveBoundTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 9), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) {
            val vals = LongArray(n) { rng.nextLong(-4, 5) }
            b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 25))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `safe bound never exceeds the exact optimum`() {
        val rng = Random(20260615)
        var finite = 0
        var total = 0
        repeat(1200) {
            val model = randomModel(rng.nextInt(3, 10), rng.nextInt(3, 10), rng)
            val exact = try {
                DualSimplex(model).solve()
            } catch (_: LpOverflowException) {
                return@repeat
            }
            if (exact.status != LpStatus.OPTIMAL) return@repeat
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            total++
            val safe = safeObjectiveLowerBound(model, rev.duals) ?: return@repeat
            finite++
            assertTrue(
                safe <= exact.objectiveValue + 1e-6,
                "UNSOUND safe bound $safe > optimum ${exact.objectiveValue}",
            )
        }
        assertTrue(total > 300, "covered only $total instances")
        // The bound should be usefully tight (finite) on the large majority of instances.
        assertTrue(finite >= total * 4 / 5, "safe bound was finite on only $finite/$total")
    }
}
