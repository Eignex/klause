package com.eignex.klause.lp.cut

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnapsackCoverSeparatorTest {

    private fun posLits(n: Int) = IntArray(n) { Lit.make(it, true) }

    private fun coverVarsOf(r: LpRelaxation, cut: Cut): Set<Int> = cut.cols.map { r.colVarId[it] }.toSet()

    @Test
    fun `separates a violated cover and the cut is valid`() {
        // 3 items, weights [3,3,2], capacity 4. Maximising x0+x1 drives the LP to x0=x1=2/3. The cover
        // {0,1} weighs 6 > 4, and x2 (weight 2 > b - w_x0 = 1) up-lifts with coefficient 1, so the
        // sequential-lifted cut is x0 + x1 + x2 <= 1, violated at 4/3 (#552).
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(longArrayOf(3, 3, 2), posLits(3), PbOp.LE, 4L)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, 0))).build(PropagationSession(p))
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))

        assertTrue(cuts.isNotEmpty(), "a violated cover should be separated")
        val cut = cuts.first()
        assertEquals(Relation.LE, cut.rel)
        assertEquals(setOf(0, 1, 2), coverVarsOf(r, cut), "x2 is up-lifted into the cut")
        assertEquals(1L, cut.rhs, "rhs stays |C| - 1 = 1")
    }

    @Test
    fun `extended cover folds in a heavy non-cover item and separates a point the bare cover misses`() {
        // 3 items, weights [3,3,3], capacity 4: at most one item fits. Maximising x0+x1+x2 drives the LP
        // to 4/9 each. The bare cover {0,1} (3+3>4) has Σx* = 8/9 < 1 — NOT violated — but extending it
        // with item 2 (weight 3 >= the cover max 3) gives x0+x1+x2 <= 1, violated at 4/3 (#552).
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(longArrayOf(3, 3, 3), posLits(3), PbOp.LE, 4L)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, -1))).build(PropagationSession(p))
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
        assertTrue(cuts.isNotEmpty(), "the extended cover should separate the all-4/9 point")
        val cut = cuts.first()
        assertEquals(setOf(0, 1, 2), coverVarsOf(r, cut), "all three items are in the extended cover")
        assertEquals(1L, cut.rhs, "rhs stays |C| - 1 = 1")
    }

}
