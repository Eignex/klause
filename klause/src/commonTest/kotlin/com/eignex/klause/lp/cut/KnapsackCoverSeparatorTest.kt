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
    fun `separator should lift a violated cover into a valid cut`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(longArrayOf(3, 3, 2), posLits(3), PbOp.LE, 4L)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, 0))).build(PropagationSession(p))
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))

        assertTrue(cuts.isNotEmpty())
        val cut = cuts.first()
        assertEquals(Relation.LE, cut.rel)
        assertEquals(setOf(0, 1, 2), coverVarsOf(r, cut))
        assertEquals(1L, cut.rhs)
    }

    @Test
    fun `extended cover should fold in a heavy item and separate a point the bare cover misses`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(longArrayOf(3, 3, 3), posLits(3), PbOp.LE, 4L)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, -1))).build(PropagationSession(p))
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
        assertTrue(cuts.isNotEmpty())
        val cut = cuts.first()
        assertEquals(setOf(0, 1, 2), coverVarsOf(r, cut))
        assertEquals(1L, cut.rhs)
    }
}
