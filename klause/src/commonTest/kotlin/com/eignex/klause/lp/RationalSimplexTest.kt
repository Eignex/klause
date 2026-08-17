package com.eignex.klause.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RationalSimplexTest {

    @Test
    fun `decides a fractional feasible system exactly`() {
        // 2x = 1 over x in [0, 1]: feasible only at the non-integer point x = 1/2.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 1.0)
        assertEquals(RationalFeasibility.FEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `decides an infeasible system exactly`() {
        // 2x = 3 over x in [0, 1] has no solution.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 3.0)
        assertEquals(RationalFeasibility.INFEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `decides a coupled system with non-dyadic coefficients`() {
        // x/3 + y/3 = 1 and x + y <= 2 conflict (x + y must be 3); doubles of 1/3 are exact rationals.
        val third = 1.0 / 3.0
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 5.0, cost = 0.0)
        val y = b.addRealVar(0.0, 5.0, cost = 0.0)
        b.addRealRow(intArrayOf(x, y), doubleArrayOf(third, third), Relation.EQ, 1.0)
        b.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.LE, 2.0)
        assertEquals(RationalFeasibility.INFEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `strict rows are decided by delta rationals`() {
        // x < 1 and x >= 1 over x in [0, 2]: infeasible only because of strictness.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 2.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0, strict = true)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        assertEquals(RationalFeasibility.INFEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `a strict feasible system yields a witness off the boundary`() {
        // 1 < x < 2 over x in [0, 3]: feasible strictly inside the open interval.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 3.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 2.0, strict = true)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0, strict = true)
        val outcome = rationalOutcome(b.build(Sense.MINIMIZE))
        assertEquals(RationalFeasibility.FEASIBLE, outcome.feasibility)
        val w = outcome.witness!!
        assertTrue(w[x] > 1.0 && w[x] < 2.0, "witness ${w[x]} must sit strictly inside (1, 2)")
    }

    @Test
    fun `pins nonbasic columns at their upper bound to satisfy an equality`() {
        // x + y + z = 3 over [0, 1] boxes holds only with every variable at its upper bound.
        val b = LpBuilder()
        val cols = IntArray(3) { b.addRealVar(0.0, 1.0, cost = 0.0) }
        b.addRealRow(cols, DoubleArray(3) { 1.0 }, Relation.EQ, 3.0)
        val outcome = rationalOutcome(b.build(Sense.MINIMIZE))
        assertEquals(RationalFeasibility.FEASIBLE, outcome.feasibility)
        val w = outcome.witness!!
        assertTrue(cols.all { w[it] == 1.0 }, "witness ${w.toList()} must sit at every upper bound")
    }

    @Test
    fun `refutes an equality just past the sum of the upper bounds`() {
        val b = LpBuilder()
        val cols = IntArray(3) { b.addRealVar(0.0, 1.0, cost = 0.0) }
        b.addRealRow(cols, DoubleArray(3) { 1.0 }, Relation.EQ, 3.5)
        assertEquals(RationalFeasibility.INFEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `decides a sparse system far larger than a dense tableau would fit`() {
        // 4000 rows x 4000 columns with two nonzeros each: a dense tableau is 32M entries, the
        // sparse one 12k. Feasible at the origin, so the cost is the tableau alone.
        val size = 4000
        val b = LpBuilder()
        val cols = IntArray(size) { b.addRealVar(0.0, 1.0, cost = 0.0) }
        for (i in 0 until size) {
            b.addRealRow(intArrayOf(cols[i], cols[(i + 1) % size]), doubleArrayOf(1.0, 1.0), Relation.LE, 1.0)
        }
        assertEquals(RationalFeasibility.FEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `a zero pivot budget reports unknown`() {
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 3.0)
        assertEquals(RationalFeasibility.UNKNOWN, rationalFeasible(b.build(Sense.MINIMIZE), maxPivots = 0))
    }

    @Test
    fun `ofDouble is the exact rational of the stored double`() {
        // 0.5 is exactly 1/2; 0.1 is exactly 3602879701896397/2^55, NOT 1/10.
        assertEquals("1/2", BigFraction.ofDouble(0.5).toString())
        assertEquals("3602879701896397/36028797018963968", BigFraction.ofDouble(0.1).toString())
    }
}
