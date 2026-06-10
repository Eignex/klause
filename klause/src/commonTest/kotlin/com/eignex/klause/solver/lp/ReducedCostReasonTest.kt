package com.eignex.klause.solver.lp

import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #282: the reason for a reduced-cost domain fixing must be sound. Fixing a nonbasic column `col`
 * (reduced cost `d`) to its tightened bound is justified by `objective ≥ L + d·(x_col − seated_col)` —
 * which holds given the OTHER support columns' seated bounds (the reduced-cost decomposition) — plus
 * the incumbent `objective ≤ M`. So the reason for `x_col ≤ b` (at lower) is the support bounds
 * excluding `col` together with `objective ≤ M`; this harness checks that clause excludes no
 * constraint-feasible integer point with `objective ≤ M`, over the whole declared box.
 */
class ReducedCostReasonTest {

    private class Row(val coeffs: LongArray, val rel: Relation, val rhs: Long)

    @Test
    fun `reduced-cost fixing reason never excludes a feasible improving point`() {
        val rng = Random(20260610)
        var checks = 0
        repeat(3000) {
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 6)
            val lo = IntArray(n) { rng.nextInt(0, hi + 1) }
            val up = IntArray(n) { j -> lo[j] + rng.nextInt(0, hi - lo[j] + 1) }
            val cost = LongArray(n) { rng.nextInt(-3, 4).toLong() }
            val b = LpBuilder()
            repeat(n) { j -> b.addVar(lo[j].toLong(), up[j].toLong(), cost = cost[j]) }
            val rows = ArrayList<Row>()
            repeat(rng.nextInt(1, 4)) { _ ->
                val coeffs = LongArray(n) { rng.nextInt(-3, 4).toLong() }
                if (coeffs.all { it == 0L }) return@repeat
                val rhs = rng.nextInt(-4, hi * n + 1).toLong()
                val rel = when (rng.nextInt(3)) {
                    0 -> Relation.LE
                    1 -> Relation.GE
                    else -> Relation.EQ
                }
                rows.add(Row(coeffs, rel, rhs))
                b.addRow((0 until n).associateWith { coeffs[it] }.filterValues { it != 0L }, rel, rhs)
            }
            val model = b.build(Sense.MINIMIZE)
            val sol = DualSimplex(model).solve()
            if (sol.status != LpStatus.OPTIMAL) return@repeat
            val den = sol.denominator
            // Incumbent target: an integer bound on the objective at or above ⌈L⌉ (some slack).
            val m = ceil(sol.objectiveValue - 1e-9).toLong() + rng.nextInt(0, 3)
            val objTrueNum = sol.objectiveNumerator
            val slack = m * den - objTrueNum
            if (slack < 0L) return@repeat

            // Support: nonbasic structural columns with a nonzero reduced cost.
            val support = (0 until n).filter { c ->
                sol.basis.status[c] != VarStatus.BASIC && sol.reducedCostNumerator[c] != 0L
            }
            for (col in support) {
                val dNum = sol.reducedCostNumerator[col]
                val seatLo = model.loShift[col]
                val seatHi = model.loShift[col] + model.upper[col]
                val atLower = sol.basis.status[col] == VarStatus.AT_LOWER
                // Mirror applyReducedCostFixing's step bound; only the side dual feasibility allows.
                val fixUpper: Long
                val fixLower: Long
                if (atLower && dNum > 0L) {
                    val dMax = slack / dNum
                    fixUpper = seatLo + dMax
                    fixLower = Long.MIN_VALUE
                } else if (!atLower && dNum < 0L) {
                    val dMax = slack / -dNum
                    fixLower = seatHi - dMax
                    fixUpper = Long.MAX_VALUE
                } else {
                    continue
                }
                checks++
                // Verify: any point in the declared box satisfying the constraints, the OTHER support
                // columns' seated bounds, and objective ≤ m, honours the fixed bound on col.
                val point = IntArray(n)
                fun rec(idx: Int) {
                    if (idx == n) {
                        for (row in rows) {
                            var s = 0L
                            for (k in 0 until n) s += row.coeffs[k] * point[k]
                            val ok = when (row.rel) {
                                Relation.LE -> s <= row.rhs
                                Relation.GE -> s >= row.rhs
                                Relation.EQ -> s == row.rhs
                            }
                            if (!ok) return
                        }
                        var obj = 0L
                        for (k in 0 until n) obj += cost[k] * point[k]
                        if (obj > m) return // objective ≤ m is part of the reason
                        for (j in support) {
                            if (j == col) continue
                            val seated = if (sol.basis.status[j] == VarStatus.AT_UPPER) {
                                point[j] <= model.loShift[j] + model.upper[j]
                            } else {
                                point[j] >= model.loShift[j]
                            }
                            if (!seated) return // a support bound violated ⇒ clause satisfied
                        }
                        assertTrue(
                            point[col] <= fixUpper && point[col] >= fixLower,
                            "reduced-cost reason excludes feasible point ${point.toList()} for col $col",
                        )
                        return
                    }
                    for (v in 0..hi) {
                        point[idx] = v
                        rec(idx + 1)
                    }
                }
                rec(0)
            }
        }
        assertTrue(checks > 100, "covered only $checks fixings")
    }
}
