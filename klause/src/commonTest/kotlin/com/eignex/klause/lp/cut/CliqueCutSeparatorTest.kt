package com.eignex.klause.lp.cut

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
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

}
