package com.eignex.klause.solver.lp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #247: the LP infeasibility (Farkas) certificate and the bound-atom nogood derived from it must be
 * sound — the clause may never exclude a point that satisfies the original constraints. The dual ray
 * uses only the certificate columns' seated bounds, so the clause `⋁ ¬(seated bound)` has to be
 * implied by the constraints alone, even though the node tightened bounds beyond the declared box.
 */
class FarkasExplanationTest {

    private class Row(val coeffs: LongArray, val rel: Relation, val rhs: Long)

    @Test
    fun `certificate proves a tiny infeasible lp`() {
        // x in [2,5], constraint x <= 1: infeasible. The lower bound x>=2 is the reason.
        val b = LpBuilder()
        val x = b.addVar(2, 5, cost = 0)
        b.addRow(mapOf(x to 1L), Relation.LE, 1)
        val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()
        assertEquals(LpStatus.INFEASIBLE, sol.status)
        assertTrue(sol.certCols.isNotEmpty(), "expected a non-empty infeasibility certificate")
        // The seated lower bound of x participates.
        assertTrue(x in sol.certCols)
    }

    @Test
    fun `randomized farkas clause never excludes a constraint-feasible point`() {
        val rng = Random(20260609)
        var infeasibleInstances = 0
        var withCert = 0
        repeat(2000) {
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 6)
            // Node-tightened bounds [lo_j, up_j] inside the declared box [0, hi].
            val lo = IntArray(n) { rng.nextInt(0, hi + 1) }
            val up = IntArray(n) { j -> lo[j] + rng.nextInt(0, hi - lo[j] + 1) }
            val b = LpBuilder()
            repeat(n) { j -> b.addVar(lo[j].toLong(), up[j].toLong(), cost = rng.nextInt(-3, 4).toLong()) }
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
            if (sol.status != LpStatus.INFEASIBLE) return@repeat
            infeasibleInstances++
            if (sol.certCols.isEmpty()) return@repeat
            withCert++

            // Reconstruct the clause's certificate box: each certificate column is pinned to the side
            // of its seated bound; a point lies "in the box" when it satisfies all of them — exactly
            // when it violates the clause. Validity ⇒ no constraint-feasible integer point is in it.
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
                    // Constraint-feasible point: it must escape the certificate box (satisfy the clause).
                    var inBox = true
                    for (c in sol.certCols.indices) {
                        val col = sol.certCols[c]
                        val seatedBound = if (sol.certBoundIsUpper[c]) {
                            point[col] <= model.loShift[col] + model.upper[col]
                        } else {
                            point[col] >= model.loShift[col]
                        }
                        if (!seatedBound) {
                            inBox = false
                            break
                        }
                    }
                    assertTrue(!inBox, "farkas clause excludes constraint-feasible point ${point.toList()}")
                    return
                }
                // Enumerate the declared box [0, hi] — the clause must hold over the whole declared range.
                for (v in 0..hi) {
                    point[idx] = v
                    rec(idx + 1)
                }
            }
            rec(0)
        }
        assertTrue(infeasibleInstances > 100, "covered only $infeasibleInstances infeasible instances")
        assertTrue(withCert > 50, "only $withCert instances produced a certificate")
    }
}
