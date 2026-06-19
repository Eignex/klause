package com.eignex.klause.solver.lp.cut

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.RevisedSimplex
import com.eignex.klause.solver.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.solver.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Clique cuts for set-packing structure. */
class CliqueCutSeparatorTest {

    private fun excl(a: Int, b: Int): Clause = Clause(intArrayOf(Lit.make(a, false), Lit.make(b, false)))

    private fun cliqueVars(r: LpRelaxation, cut: Cut): Set<Int> = cut.cols.map { r.colVarId[it] }.toSet()

    @Test
    fun `extends a base at-most-one into a full clique cut`() {
        // x0..x3 pairwise mutually exclusive (a K4): Cardinality(x0,x1)<=1 is the base clique, the
        // binary exclusions add the rest. Maximising the sum relaxes to x_i = 1/2 (Σ = 2), which the
        // clique cut Σ x <= 1 cuts off.
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), min = 0, max = 1),
                excl(0, 2),
                excl(1, 2),
                excl(0, 3),
                excl(1, 3),
                excl(2, 3),
            ),
        )
        val r = CpToLpRelaxation(
            p,
            LinearObjective(boolWeights = longArrayOf(-1, -1, -1, -1)),
        ).build(PropagationSession(p))
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = CliqueCutSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))

        assertTrue(cuts.isNotEmpty(), "a violated clique should be separated")
        val cut = cuts.first { it.rel == Relation.LE }
        assertEquals(1L, cut.rhs)
        assertEquals(setOf(0, 1, 2, 3), cliqueVars(r, cut))
    }

    @Test
    fun `clique cuts exclude no set-packing-feasible point`() {
        val rng = Random(20260610)
        var separated = 0
        repeat(1000) {
            val n = rng.nextInt(3, 7)
            // Random conflict graph + a base at-most-one over an edge so a clique can root.
            val edges = HashSet<Long>()
            val factors = ArrayList<Factor>()
            for (a in 0 until n) {
                for (b in a + 1 until n) {
                    if (rng.nextInt(3) == 0) {
                        edges.add(a.toLong() * n + b)
                        factors.add(excl(a, b))
                    }
                }
            }
            if (edges.isEmpty()) return@repeat
            // Promote one edge to an at-most-one Cardinality so the separator has a base clique.
            val e = edges.first()
            factors.add(Cardinality(intArrayOf(Lit.make((e / n).toInt(), true), Lit.make((e % n).toInt(), true)), 0, 1))
            val p = Problem(n, 0, emptyArray(), factors.toTypedArray())
            val obj = LinearObjective(boolWeights = LongArray(n) { -1L })
            val r = CpToLpRelaxation(p, obj).build(PropagationSession(p))
            val sol = RevisedSimplex(r.model).solve() ?: return@repeat
            val cuts = CliqueCutSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
            if (cuts.isEmpty()) return@repeat
            separated++
            for (cut in cuts) {
                val clique = cliqueVars(r, cut).toList()
                // Every 0/1 assignment respecting all exclusions must put at most one clique member true.
                for (mask in 0 until (1 shl n)) {
                    var feasible = true
                    for (a in 0 until n) {
                        for (b in a + 1 until n) {
                            if ((a.toLong() * n + b) in edges &&
                                mask and (1 shl a) != 0 && mask and (1 shl b) != 0
                            ) {
                                feasible = false
                            }
                        }
                    }
                    if (!feasible) continue
                    val inClique = clique.count { v -> mask and (1 shl v) != 0 }
                    assertTrue(inClique <= cut.rhs, "clique cut excludes feasible mask $mask")
                }
            }
        }
        assertTrue(separated > 30, "only $separated instances produced a clique cut")
    }
}
