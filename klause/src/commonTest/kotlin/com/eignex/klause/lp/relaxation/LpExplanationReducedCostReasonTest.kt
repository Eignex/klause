package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.integerCertify
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #282/#705: the reason for a reduced-cost domain fixing must be sound on the sparse path. Fixing a
 * nonbasic column `col` (reduced cost `d` from the integer-multiplier [integerCertify]) to its
 * tightened bound is justified by `objective ≥ L + d·(x_col − seated_col)` — which holds given the
 * OTHER support columns' seated bounds — plus the incumbent `objective ≤ M`. So the reason for
 * `x_col ≤ b` (at lower) is the support bounds excluding `col` together with `objective ≤ M`; this
 * harness checks that clause excludes no constraint-feasible integer point with `objective ≤ M`.
 */
class LpExplanationReducedCostReasonTest {

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
            val sol = RevisedSimplex(model).solve() ?: return@repeat
            val cert = integerCertify(model, sol.duals) ?: return@repeat
            // Incumbent target: an integer bound on the objective at or above ⌈L⌉ (some slack).
            val m = (cert.objectiveBoundCeil(0L) ?: return@repeat) + rng.nextInt(0, 3)
            if (!cert.improvingGapNonNegative(m)) return@repeat

            // Support: nonbasic structural columns with a nonzero reduced cost.
            val support = (0 until n).filter { c -> cert.reducedCostSign(c) != 0 }
            for (col in support) {
                val djSign = cert.reducedCostSign(col)
                val seatLo = model.loShift[col]
                val seatHi = model.loShift[col] + model.upper[col]
                val atLower = sol.basis.status[col] == VarStatus.AT_LOWER
                // Mirror applySparseReducedCostFixing's step bound; only the side dual feasibility allows.
                val fixUpper: Long
                val fixLower: Long
                if (atLower && djSign > 0) {
                    val dMax = cert.fixSteps(col, m) ?: continue
                    fixUpper = seatLo + dMax
                    fixLower = Long.MIN_VALUE
                } else if (!atLower && djSign < 0) {
                    val dMax = cert.fixSteps(col, m) ?: continue
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
