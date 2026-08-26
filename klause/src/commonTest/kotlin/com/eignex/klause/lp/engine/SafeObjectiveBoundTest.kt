package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.exactVariableBound
import com.eignex.klause.lp.engine.safeObjectiveLowerBound
import com.eignex.klause.lp.engine.safeVariableBound
import com.eignex.klause.lp.engine.tightVariableBound
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            total++
            val safe = safeObjectiveLowerBound(model, rev.duals) ?: return@repeat
            finite++
            assertTrue(safe <= opt + 1e-6, "UNSOUND safe bound $safe > optimum $opt")
        }
        assertTrue(total > 300, "covered only $total instances")
        // The bound should be usefully tight (finite) on the large majority of instances.
        assertTrue(finite >= total * 4 / 5, "safe bound was finite on only $finite/$total")
    }

    @Test
    fun `exact variable bound tightens a free column the safe bound leaves loose`() {
        // maximize x subject to x <= 5, x open above (a free column at the ±∞ probe upper).
        val b = LpBuilder()
        val x = b.addFreeVar(0L, null, cost = -1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 5L)
        val model = b.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solvePrimal())

        val exact = model.exactVariableBound(result, x, maximize = true)
        val safe = assertNotNull(model.safeVariableBound(result, x, maximize = true))
        val tight = assertNotNull(model.tightVariableBound(result, x, maximize = true))

        assertEquals(5L, exact, "exact bound should be the true max")
        assertEquals(5L, tight, "tight bound should match the exact bound")
        assertTrue(safe >= 5L, "safe bound must stay sound")
        assertTrue(tight <= safe, "tight bound must not exceed the looser safe bound")
    }
}
