package com.eignex.klause.solver.lp

import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #281: the objective lower-bound reason must be sound. For an OPTIMAL LP with optimum `L`, the
 * nonbasic columns seated at a bound with a nonzero reduced cost are the load-bearing support: given
 * the constraints and those seated bounds alone, every point has objective ≥ L — independent of the
 * other variables' bounds. This mirrors the Farkas harness ([FarkasExplanationTest]) for the bounding
 * case: the implied clause `(objective ≥ ⌈L⌉) ∨ ⋁ ¬(seated bound of a support column)` must exclude no
 * constraint-feasible integer point over the whole declared box, even though the node tightened bounds.
 */
class ObjectiveBoundReasonTest {

    private class Row(val coeffs: LongArray, val rel: Relation, val rhs: Long)

    @Test
    fun `objective-bound reason never excludes a constraint-feasible point below the bound`() {
        val rng = Random(20260610)
        var optimalInstances = 0
        var withSupport = 0
        repeat(2000) {
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 6)
            // Node-tightened bounds [lo_j, up_j] inside the declared box [0, hi].
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
            optimalInstances++

            // Support: structural columns nonbasic with a nonzero reduced cost — the seated bounds the
            // reason cites. (Columns 0 until n are the structural vars; slacks are >= n.)
            val support = (0 until n).filter { c ->
                sol.basis.status[c] != VarStatus.BASIC && sol.reducedCostNumerator[c] != 0L
            }
            if (support.isNotEmpty()) withSupport++
            val bound = ceil(sol.objectiveValue - 1e-9).toLong()

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
                    // Does the point honour every support column's seated bound? If so, the reason
                    // clause requires objective >= bound, so the point's objective must meet it.
                    var seated = true
                    for (col in support) {
                        val ok = if (sol.basis.status[col] == VarStatus.AT_UPPER) {
                            point[col] <= model.loShift[col] + model.upper[col]
                        } else {
                            point[col] >= model.loShift[col]
                        }
                        if (!ok) {
                            seated = false
                            break
                        }
                    }
                    if (!seated) return
                    var obj = 0L
                    for (k in 0 until n) obj += cost[k] * point[k]
                    assertTrue(
                        obj >= bound,
                        "objective-bound reason excludes feasible point ${point.toList()} (obj=$obj < $bound)",
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
        assertTrue(optimalInstances > 200, "covered only $optimalInstances optimal instances")
        assertTrue(withSupport > 50, "only $withSupport instances had a nonempty support")
    }
}
