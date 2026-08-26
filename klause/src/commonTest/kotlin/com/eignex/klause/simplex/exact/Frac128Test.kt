package com.eignex.klause.simplex.exact

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Frac128Test {

    @Test
    fun `fixed-width level agrees with the big level on random fraction chains`() {
        val rng = Random(11)
        repeat(200) {
            val f128 = Frac128Ops()
            val a = rng.nextLong(-1_000_000L, 1_000_000L)
            val b = rng.nextLong(1L, 1_000_000L)
            val c = rng.nextLong(-1_000L, 1_000L)
            var x = f128.times(f128.ofLong(a), f128.reciprocal(f128.ofLong(b)))
            x = f128.plus(x, f128.ofLong(c))
            x = f128.minus(x, f128.times(f128.ofLong(2L), f128.reciprocal(f128.ofLong(3L))))
            var y = BigFracOps.times(BigFracOps.ofLong(a), BigFracOps.reciprocal(BigFracOps.ofLong(b)))
            y = BigFracOps.plus(y, BigFracOps.ofLong(c))
            y = BigFracOps.minus(
                y,
                BigFracOps.times(BigFracOps.ofLong(2L), BigFracOps.reciprocal(BigFracOps.ofLong(3L))),
            )
            assertTrue(!f128.overflowed(), "no chain here should overflow 128 bits")
            assertEquals(BigFracOps.signum(y), f128.signum(x))
            assertEquals(y.toDouble(), f128.toDouble(x), 1e-9)
        }
    }

    @Test
    fun `the fixed-width level latches overflow past 128 bits`() {
        val ops = Frac128Ops()
        val big = ops.ofLong(1L shl 62)
        val sq = ops.times(big, big)
        assertTrue(!ops.overflowed(), "2^124 still fits 128 bits")
        ops.times(sq, big)
        assertTrue(ops.overflowed(), "2^186 must latch the level's overflow")
    }

    @Test
    fun `a system that escapes 128 bits is still decided by escalation`() {
        // x0 pinned to 1 and x_i = 2^62 x_{i+1} force x3 = 2^-186: the solution's exact fraction
        // cannot fit 128 bits, so the fixed level latches and the big level proves feasibility.
        val k = (1L shl 62).toDouble()
        val b = LpBuilder()
        val x0 = b.addRealVar(1.0, 1.0, cost = 0.0)
        val x1 = b.addRealVar(0.0, 1.0, cost = 0.0)
        val x2 = b.addRealVar(0.0, 1.0, cost = 0.0)
        val x3 = b.addRealVar(0.0, 1.0, cost = 0.0)
        b.addRealRow(intArrayOf(x0, x1), doubleArrayOf(1.0, -k), Relation.EQ, 0.0)
        b.addRealRow(intArrayOf(x1, x2), doubleArrayOf(1.0, -k), Relation.EQ, 0.0)
        b.addRealRow(intArrayOf(x2, x3), doubleArrayOf(1.0, -k), Relation.EQ, 0.0)
        assertEquals(RationalFeasibility.FEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }
}
