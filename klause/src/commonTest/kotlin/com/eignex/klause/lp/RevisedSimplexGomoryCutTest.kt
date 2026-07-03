package com.eignex.klause.lp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #22: exact Gomory integrality cuts from the dual-simplex tableau. */
class RevisedSimplexGomoryCutTest {

    private class Row(val coeffs: LongArray, val rel: Relation, val rhs: Long)

    @Test
    fun `gomory cut fires on a fractional optimum and is valid`() {
        // max x  s.t. 2x <= 3, x in [0,5]  -> x = 3/2 fractional. Gomory must cut it.
        val b = LpBuilder()
        val x = b.addVar(0, 5, cost = 1)
        b.addRow(mapOf(x to 2L), Relation.LE, 3)
        val simplex = RevisedSimplex(b.build(Sense.MAXIMIZE))
        val sol = requireNotNull(simplex.solve())
        assertEquals(1.5, sol.primal[x], 1e-9)

        val cuts = simplex.gomoryCuts(8)
        assertTrue(cuts.isNotEmpty(), "expected a Gomory cut for the fractional optimum")
        // Valid: every integer x in [0,5] with 2x<=3 (i.e. x in {0,1}) satisfies each cut.
        for (xi in 0..5) {
            if (2 * xi > 3) continue
            for (cut in cuts) {
                val lhs = cut.cols.indices.sumOf { cut.coeffs[it] * xi } // single column here
                assertTrue(lhs >= cut.rhs, "x=$xi violates a Gomory cut")
            }
        }
    }

    @Test
    fun `randomized gomory cuts never exclude an integer-feasible point`() {
        val rng = Random(20260608)
        var instances = 0
        var totalCuts = 0
        repeat(500) {
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 6)
            val b = LpBuilder()
            repeat(n) { b.addVar(0, hi.toLong(), cost = rng.nextInt(-3, 4).toLong()) }
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
            val simplex = RevisedSimplex(b.build(Sense.MINIMIZE))
            simplex.solve() ?: return@repeat
            val cuts = simplex.gomoryCuts(8)
            if (cuts.isEmpty()) return@repeat
            instances++
            totalCuts += cuts.size

            // Enumerate the integer box; every point satisfying the original rows must satisfy
            // every cut. A violation would mean Gomory removed a feasible integer point — unsound.
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
                    for (cut in cuts) {
                        var lhs = 0L
                        for (t in cut.cols.indices) lhs += cut.coeffs[t] * point[cut.cols[t]]
                        assertTrue(lhs >= cut.rhs, "cut excludes feasible point ${point.toList()}: $lhs < ${cut.rhs}")
                    }
                    return
                }
                for (v in 0..hi) {
                    point[idx] = v
                    rec(idx + 1)
                }
            }
            rec(0)
        }
        assertTrue(instances > 50, "covered only $instances cut-producing instances")
        assertTrue(totalCuts > 0)
    }
}
