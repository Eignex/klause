package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.integerDualLowerBoundCeil
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The integer-multiplier bound ([integerDualLowerBoundCeil]) must be SOUND — never exceeding
 * `ceil(LP optimum)` — and usefully tight (equal to it on the large majority), the same way
 * [SafeObjectiveBoundTest] checks the float bound.
 */
class IntegerDualBoundTest {

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
    fun `integer bound is sound and tight against ceil of the LP optimum`() {
        val rng = Random(20260622)
        var total = 0
        var finite = 0
        var matchesCeil = 0
        repeat(1500) {
            val model = randomModel(rng.nextInt(3, 10), rng.nextInt(3, 10), rng)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            total++
            val bound = integerDualLowerBoundCeil(model, rev.duals) ?: return@repeat
            finite++
            // Sound: ceil of a valid LP lower bound never exceeds ceil(LP optimum).
            val ceilOpt = ceil(opt)
            assertTrue(
                bound.toDouble() <= ceilOpt + 1e-6,
                "UNSOUND integer bound $bound > ceil(optimum $opt)",
            )
            // Power-of-two scaling should recover ceil(LP optimum) on the large majority of instances.
            if (bound.toDouble() in (ceilOpt - 0.5)..(ceilOpt + 0.5)) matchesCeil++
        }
        assertTrue(total > 300, "covered only $total instances")
        assertTrue(finite >= total * 4 / 5, "integer bound was finite on only $finite/$total")
        assertTrue(matchesCeil >= finite * 2 / 3, "matched ceil(optimum) on only $matchesCeil/$finite")
    }

    @Test
    fun `a slack multiplier off by float noise still yields an exact bound`() {
        // Mirrors the float bound's own repair test: min -x subject to x <= 4, x in [0, 10]. The row's
        // exact multiplier is -1, and a slack carries no upper bound, so a multiplier a hair the other
        // side of its own reduced cost used to abandon the certificate outright.
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = -1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 4L)
        val model = b.build(Sense.MINIMIZE)
        val optimum = exactLpOptimum(model)

        val bound = assertNotNull(
            integerDualLowerBoundCeil(model, doubleArrayOf(1e-12)),
            "a multiplier off by 1e-12 must not cost the whole certificate",
        )

        assertTrue(bound <= ceil(optimum) + 1e-9, "UNSOUND repaired bound $bound > ceil(optimum) ${ceil(optimum)}")
    }

    @Test
    fun `the repair leaves a certificate that already had one untouched`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = -1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 4L)
        val model = b.build(Sense.MINIMIZE)

        val exact = assertNotNull(integerDualLowerBoundCeil(model, doubleArrayOf(-1.0)))

        assertEquals(-4L, exact)
    }
}
