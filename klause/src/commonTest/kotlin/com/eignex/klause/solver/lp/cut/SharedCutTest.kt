package com.eignex.klause.solver.lp.cut

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.RevisedSimplex
import com.eignex.klause.solver.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.solver.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [SharedCut] must round-trip a cut across two relaxations of the same [Problem] preserving the
 * inequality over CP variables (column indices differ per relaxation, CP-variable ids do not), and an
 * imported cut must stay valid — satisfied by every integer-feasible point — since it is folded into
 * another worker's relaxation. Validity is checked by brute force over the integer box.
 */
class SharedCutTest {

    private fun relax(p: Problem, obj: LinearObjective): LpRelaxation =
        CpToLpRelaxation(p, obj).build(PropagationSession(p))

    /** The cut as a CP-variable → coefficient map, read through [rel]'s column→variable map. */
    private fun overVars(cut: Cut, rel: LpRelaxation): Map<Int, Long> =
        cut.cols.indices.associate { rel.colVarId[cut.cols[it]] to cut.coeffs[it] }

    @Test
    fun `a cut round-trips across relaxations over CP variables`() {
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 5) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 6)),
        )
        // Two relaxations of the same problem, different objectives ⇒ potentially different layouts.
        val r1 = relax(p, LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0)))
        val r2 = relax(p, LinearObjective(intCoefficients = longArrayOf(0, 0, 1, 0)))

        // Hand-build a cut over r1's columns for int vars 0 and 2.
        val cut = Cut(
            intArrayOf(r1.intColOf[0], r1.intColOf[2]),
            longArrayOf(3, 5),
            Relation.LE,
            7,
            global = true,
        )

        val shared = assertNotNull(SharedCut.fromCut(cut, r1), "export should name both columns")
        // The portable form carries CP variables, not columns.
        assertEquals(setOf(0, 2), shared.vars.toSet())

        val back2 = assertNotNull(shared.toCut(r2), "r2 has columns for vars 0 and 2")
        // The inequality over CP variables is identical after crossing to r2, whatever r2's columns are.
        assertEquals(overVars(cut, r1), overVars(back2, r2))
        assertEquals(cut.rel, back2.rel)
        assertEquals(cut.rhs, back2.rhs)

        // Round-trip on the source relaxation recovers the exact columns.
        val back1 = assertNotNull(shared.toCut(r1))
        assertTrue(cut.cols.contentEquals(back1.cols) && cut.coeffs.contentEquals(back1.coeffs))

        // Equal inequalities hash equally regardless of term order.
        val reordered = SharedCut(
            intArrayOf(2, 0),
            booleanArrayOf(false, false),
            longArrayOf(5, 3),
            shared.rel,
            shared.rhs,
        )
        assertEquals(shared.key, reordered.key)
    }

    @Test
    fun `missing column drops the cut on import`() {
        // A problem whose relaxation has no column for some variable: importing a cut over it returns null.
        val p = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 3) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2)),
        )
        val r = relax(p, LinearObjective(intCoefficients = longArrayOf(1, 1)))
        val shared = SharedCut(
            intArrayOf(0, 99),
            booleanArrayOf(false, false),
            longArrayOf(1, 1),
            Relation.LE,
            2,
        )
        assertTrue(shared.toCut(r) == null, "a variable with no column cannot be expressed and is dropped")
    }

    private fun satisfies(f: Linear, x: IntArray): Boolean {
        var s = 0L
        for (i in f.vars.indices) s += f.coeffs[i].toLong() * x[f.vars[i]]
        return when (f.op) {
            LinearOp.LE -> s <= f.bound
            LinearOp.GE -> s >= f.bound
            LinearOp.EQ -> s == f.bound.toLong()
            else -> true
        }
    }

    @Test
    fun `imported cuts stay valid at every integer-feasible point`() {
        val rng = Random(20260625)
        var checked = 0
        repeat(300) {
            val n = rng.nextInt(2, 4)
            val domains = Array(n) { IntDomain(0, rng.nextInt(2, 5)) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) {
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                val coeffs = IntArray(k) { rng.nextInt(1, 4) }
                val op = if (rng.nextBoolean()) LinearOp.LE else LinearOp.GE
                factors.add(Linear(coeffs, vars, op, rng.nextInt(0, 3 * k + 1)))
            }
            val p = Problem(0, n, domains, factors.toTypedArray())
            // Separate real global cuts on r1, then share them into r2.
            val r1 = relax(p, LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-3, 4) }))
            val sol = RevisedSimplex(r1.model).solve() ?: return@repeat
            val cuts = AggregationMirSeparator().separate(CutContext(p, r1, sol.primal, PropagationSession(p)))
            if (cuts.isEmpty()) return@repeat
            val r2 = relax(p, LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-3, 4) }))
            val lins = p.factors.filterIsInstance<Linear>()
            for (cut in cuts) {
                val imported = SharedCut.fromCut(cut, r1)?.toCut(r2) ?: continue
                checked++
                val x = IntArray(n)
                fun recurse(v: Int) {
                    if (v == n) {
                        if (lins.all { f -> satisfies(f, x) }) {
                            var lhs = 0L
                            for (kk in imported.cols.indices) {
                                lhs +=
                                    imported.coeffs[kk] * x[r2.colVarId[imported.cols[kk]]]
                            }
                            assertTrue(lhs <= imported.rhs, "imported cut cuts off feasible ${x.toList()}")
                        }
                        return
                    }
                    for (value in domains[v].min..domains[v].max) {
                        x[v] = value
                        recurse(v + 1)
                    }
                }
                recurse(0)
            }
        }
        assertTrue(checked > 0, "no cuts were imported across 300 instances")
    }
}
