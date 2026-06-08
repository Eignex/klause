package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #18: the bounded-variable fraction-free integer dual simplex core. */
class DualSimplexTest {

    private val eps = 1e-9

    @Test
    fun `minimize sum under a covering constraint`() {
        // min x + y  s.t.  x + y >= 1,  x,y in [0,10].  Optimum 1.
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 1)
        val y = b.addVar(0, 10, cost = 1)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.GE, 1)
        val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(1.0, sol.objectiveValue, eps)
        assertEquals(1L, sol.objectiveLowerBoundCeil())
        assertTrue(sol.primal(x) + sol.primal(y) >= 1.0 - eps)
    }

    @Test
    fun `maximize with two upper-bounding rows`() {
        // max 3x + 2y  s.t.  x + y <= 4,  x + 3y <= 6,  x,y in [0,10].  Optimum 12 at (4,0).
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 3)
        val y = b.addVar(0, 10, cost = 2)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.LE, 4)
        b.addRow(mapOf(x to 1L, y to 3L), Relation.LE, 6)
        val sol = DualSimplex(b.build(Sense.MAXIMIZE)).solve()

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(12.0, sol.objectiveValue, eps)
        assertEquals(4.0, sol.primal(x), eps)
        assertEquals(0.0, sol.primal(y), eps)
    }

    @Test
    fun `fractional optimum is exact`() {
        // max x  s.t.  3x <= 2,  x in [0,10].  Optimum x = 2/3, a value no float represents exactly.
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 1)
        b.addRow(mapOf(x to 3L), Relation.LE, 2)
        val sol = DualSimplex(b.build(Sense.MAXIMIZE)).solve()

        assertEquals(LpStatus.OPTIMAL, sol.status)
        // The exact rational must be 2/3: numerator 2, denominator 3.
        assertEquals(2L, sol.primalNumerator[x])
        assertEquals(3L, sol.denominator)
        assertEquals(2.0 / 3.0, sol.objectiveValue, eps)
    }

    @Test
    fun `equality constraint`() {
        // min y  s.t.  x + y = 5,  x in [0,3], y in [0,10].  x maxes at 3 so y = 2.
        val b = LpBuilder()
        val x = b.addVar(0, 3, cost = 0)
        val y = b.addVar(0, 10, cost = 1)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.EQ, 5)
        val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps)
        assertEquals(2.0, sol.primal(y), eps)
    }

    @Test
    fun `infeasible LP is detected`() {
        // x + y <= 1 and x + y >= 5 with x,y in [0,10] is infeasible.
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 1)
        val y = b.addVar(0, 10, cost = 1)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.LE, 1)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.GE, 5)
        val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()

        assertEquals(LpStatus.INFEASIBLE, sol.status)
    }

    @Test
    fun `bound-only problem with no rows`() {
        // min 2x - 3y, x in [1,4], y in [2,5]. min at x=1, y=5 -> 2 - 15 = -13.
        val b = LpBuilder()
        val x = b.addVar(1, 4, cost = 2)
        val y = b.addVar(2, 5, cost = -3)
        val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(-13.0, sol.objectiveValue, eps)
        assertEquals(1.0, sol.primal(x), eps)
        assertEquals(5.0, sol.primal(y), eps)
    }

    @Test
    fun `warm start from optimal basis reproduces the optimum`() {
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 3)
        val y = b.addVar(0, 10, cost = 2)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.LE, 4)
        b.addRow(mapOf(x to 1L, y to 3L), Relation.LE, 6)
        val model = b.build(Sense.MAXIMIZE)
        val cold = DualSimplex(model).solve()

        // Re-solving from the returned basis must give the identical exact solution.
        val warm = DualSimplex(model).solve(cold.basis)
        assertEquals(LpStatus.OPTIMAL, warm.status)
        assertEquals(cold.objectiveValue, warm.objectiveValue, eps)
        assertEquals(cold.primal(x), warm.primal(x), eps)
        assertEquals(cold.primal(y), warm.primal(y), eps)
    }

    @Test
    fun `dual values certify the bound`() {
        // min x + y s.t. x + y >= 1; dual of the single >= row should reconstruct the objective.
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 1)
        val y = b.addVar(0, 10, cost = 1)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.GE, 1)
        val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()
        assertEquals(LpStatus.OPTIMAL, sol.status)
        // y·b (single row) should equal the optimum; dual is in normalized (negated >=) space, so
        // |y · rhs_normalized| == objective.
        val y0 = sol.dualNumerator[0].toDouble() / sol.denominator
        // Normalized row is -x - y <= -1, so dual * (-1) gives the bound contribution = 1.
        assertEquals(1.0, abs(y0 * -1.0), eps)
    }

    // ---- Randomized parity against an independent Double vertex-enumeration oracle (n = 2). ----

    /** Brute optimum over all 2-tight-constraint vertices; null if infeasible. Minimization. */
    private fun oracle(
        loX: Double,
        hiX: Double,
        loY: Double,
        hiY: Double,
        cx: Double,
        cy: Double,
        rows: List<Triple<Double, Double, Pair<Char, Double>>>, // (ax, ay, (rel, b)); rel in <,>,=
    ): Double? {
        // Candidate lines: x=loX, x=hiX, y=loY, y=hiY, and each row as an equality.
        data class Line(val ax: Double, val ay: Double, val b: Double)
        val lines = mutableListOf(
            Line(1.0, 0.0, loX),
            Line(1.0, 0.0, hiX),
            Line(0.0, 1.0, loY),
            Line(0.0, 1.0, hiY),
        )
        for ((ax, ay, rb) in rows) lines.add(Line(ax, ay, rb.second))

        fun feasible(px: Double, py: Double): Boolean {
            if (px < loX - 1e-7 || px > hiX + 1e-7) return false
            if (py < loY - 1e-7 || py > hiY + 1e-7) return false
            for ((ax, ay, rb) in rows) {
                val lhs = ax * px + ay * py
                val ok = when (rb.first) {
                    '<' -> lhs <= rb.second + 1e-7
                    '>' -> lhs >= rb.second - 1e-7
                    else -> abs(lhs - rb.second) <= 1e-7
                }
                if (!ok) return false
            }
            return true
        }

        var best: Double? = null
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val l1 = lines[i]
                val l2 = lines[j]
                val det = l1.ax * l2.ay - l2.ax * l1.ay
                if (abs(det) < 1e-9) continue // parallel lines: no unique vertex
                val px = (l1.b * l2.ay - l2.b * l1.ay) / det
                val py = (l1.ax * l2.b - l2.ax * l1.b) / det
                if (!feasible(px, py)) continue
                val obj = cx * px + cy * py
                if (best == null || obj < best) best = obj
            }
        }
        return best
    }

    @Test
    fun `randomized parity with double oracle`() {
        // 120 instances cover the LE/GE/EQ × sign × bound combinations while staying fast on the
        // single-threaded JS/wasm targets, where this loop dominates the suite's wall time.
        val rng = Random(20260608)
        var checked = 0
        repeat(120) {
            val hiX = rng.nextInt(1, 8).toLong()
            val hiY = rng.nextInt(1, 8).toLong()
            val cx = rng.nextInt(-5, 6).toLong()
            val cy = rng.nextInt(-5, 6).toLong()
            val numRows = rng.nextInt(0, 4)

            val b = LpBuilder()
            val x = b.addVar(0, hiX, cost = cx)
            val y = b.addVar(0, hiY, cost = cy)
            val rows = ArrayList<Triple<Double, Double, Pair<Char, Double>>>()
            repeat(numRows) {
                val ax = rng.nextInt(-3, 4).toLong()
                val ay = rng.nextInt(-3, 4).toLong()
                if (ax == 0L && ay == 0L) return@repeat
                val rhs = rng.nextInt(-6, 10).toLong()
                val rel = when (rng.nextInt(3)) {
                    0 -> Relation.LE
                    1 -> Relation.GE
                    else -> Relation.EQ
                }
                b.addRow(mapOf(x to ax, y to ay), rel, rhs)
                val rc = when (rel) {
                    Relation.LE -> '<'
                    Relation.GE -> '>'
                    else -> '='
                }
                rows.add(Triple(ax.toDouble(), ay.toDouble(), rc to rhs.toDouble()))
            }

            val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()
            val expected = oracle(0.0, hiX.toDouble(), 0.0, hiY.toDouble(), cx.toDouble(), cy.toDouble(), rows)

            if (expected == null) {
                assertEquals(LpStatus.INFEASIBLE, sol.status, "expected infeasible")
            } else {
                assertEquals(LpStatus.OPTIMAL, sol.status, "expected optimal")
                assertEquals(expected, sol.objectiveValue, 1e-6, "objective mismatch")
            }
            checked++
        }
        assertTrue(checked > 100, "ran $checked random instances")
    }
}
