package com.eignex.klause.solver.lp

import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The integer-multiplier bound ([integerDualLowerBoundCeil]) must be SOUND — never exceeding the exact
 * authoritative bound — and usefully tight. Validated against the rational [ExactBasisCertifier] (the
 * oracle) and the exact LP optimum, the same way [SafeObjectiveBoundTest] checks the float bound.
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
    fun `integer bound is sound and never exceeds the exact certified ceil`() {
        val rng = Random(20260622)
        var total = 0
        var finite = 0
        var matchesExact = 0
        repeat(1500) {
            val model = randomModel(rng.nextInt(3, 10), rng.nextInt(3, 10), rng)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            total++
            val bound = integerDualLowerBoundCeil(model, rev.duals) ?: return@repeat
            finite++
            // Sound: ceil of a valid LP lower bound never exceeds ceil(LP optimum).
            assertTrue(
                bound.toDouble() <= ceil(opt) + 1e-6,
                "UNSOUND integer bound $bound > ceil(optimum $opt)",
            )
            // Must never exceed the authoritative rational certifier's ceil (the tight bound).
            val exact = ExactBasisCertifier.lowerBoundCeil(model, rev.basis)
            if (exact != null) {
                assertTrue(bound <= exact, "integer bound $bound exceeded exact certified ceil $exact")
                if (bound == exact) matchesExact++
            }
        }
        assertTrue(total > 300, "covered only $total instances")
        assertTrue(finite >= total * 4 / 5, "integer bound was finite on only $finite/$total")
        // Power-of-two scaling should recover the exact ceil on the large majority of instances.
        assertTrue(matchesExact >= finite * 2 / 3, "matched the exact ceil on only $matchesExact/$finite")
    }
}
