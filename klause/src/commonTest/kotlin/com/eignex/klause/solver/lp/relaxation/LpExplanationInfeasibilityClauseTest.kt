package com.eignex.klause.solver.lp.relaxation

import com.eignex.klause.solver.lp.ExactBasisCertifier
import com.eignex.klause.solver.lp.LpBuilder
import com.eignex.klause.solver.lp.LpModel
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.RevisedSimplex
import com.eignex.klause.solver.lp.Sense
import com.eignex.klause.util.BigRational
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #247/#705: the exact Farkas infeasibility ray ([ExactBasisCertifier.farkasRay]) and the bound-atom
 * nogood derived from it must be sound — the clause may never exclude a point satisfying the original
 * constraints. The ray's column support uses only the seated box bounds, so `⋁ ¬(seated bound)` has to
 * be implied by the constraints alone, even though the node tightened bounds beyond the declared box.
 */
class LpExplanationInfeasibilityClauseTest {

    private class Row(val coeffs: LongArray, val rel: Relation, val rhs: Long)

    /** Exact `ρ·A_col` for structural column [col] under ray [ray]. */
    private fun rayDotColumn(model: LpModel, ray: Array<BigRational>, col: Int): BigRational {
        var aj = BigRational.ZERO
        model.forEachInColumn(col) { i, a -> aj += ray[i] * BigRational.of(a) }
        return aj
    }

    @Test
    fun `ray proves a tiny infeasible lp`() {
        // x in [2,5], constraint x <= 1: infeasible. The lower bound x>=2 is the reason.
        val b = LpBuilder()
        val x = b.addVar(2, 5, cost = 0)
        b.addRow(mapOf(x to 1L), Relation.LE, 1)
        val model = b.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)
        val result = simplex.solve()
        assertTrue(result == null, "the LP is infeasible, so solve() must return null")
        val ray = assertNotNull(
            ExactBasisCertifier.farkasRay(model, assertNotNull(simplex.infeasibleBasis), simplex.infeasibleRow),
            "expected a Farkas infeasibility ray",
        )
        // x's seated lower bound participates: ρ·A_x < 0 ⇒ the lower side is load-bearing.
        assertTrue(rayDotColumn(model, ray, x).signum() < 0, "x's lower bound must participate in the ray")
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
            val simplex = RevisedSimplex(model)
            if (simplex.solve() != null) return@repeat // feasible
            val basis = simplex.infeasibleBasis ?: return@repeat
            val ray = ExactBasisCertifier.farkasRay(model, basis, simplex.infeasibleRow) ?: return@repeat
            infeasibleInstances++
            // Seated side per structural column: ρ·A_j > 0 ⇒ upper, < 0 ⇒ lower, 0 ⇒ not in the box.
            val seatUpper = IntArray(n) { col -> rayDotColumn(model, ray, col).signum() }
            if (seatUpper.all { s -> s == 0 }) return@repeat
            withCert++

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
                    for (col in 0 until n) {
                        val sign = seatUpper[col]
                        if (sign == 0) continue
                        val seatedBound = if (sign > 0) {
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
