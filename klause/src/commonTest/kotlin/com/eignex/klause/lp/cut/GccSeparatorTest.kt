package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.withCpBounds
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GccSeparatorTest {

    @Test
    fun `local gcc cuts retain occurrence bounds until their scope is popped`() {
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1), cover = longArrayOf(0, 1), countVars = intArrayOf(2, 3), closed = true,
        )
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2), IntDomain(0, 2)), arrayOf(factor),
        )
        val session = PropagationSession(problem)
        session.pinIntAtMost(2, 0)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 1))).build(session)
        val context = CutContext(problem, relaxation, DoubleArray(relaxation.model.n), session)
        val cut = GccSeparator().separate(context).single()
        val source = assertNotNull(SourceCut.fromCut(cut, relaxation).orNull())
        val guard = CutPremise.Bound(
            CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 2) to BigFraction.ONE)), true, BigFraction.ZERO,
        )
        val map = assertNotNull(relaxation.sourceMap)

        assertEquals(2L, cut.rhs)
        assertTrue(source.provenance.facts.contains(CutProofFact(guard, false)))
        assertNotNull(source.toCut(map.withCpBounds(relaxation.model, session)).orNull())
        session.popToLevel(0)
        assertNull(source.toCut(map.withCpBounds(relaxation.model, session)).orNull())
    }

    /**
     * Separate the GCC cut at the LP vertex chosen by [coef]: with no GCC rows in the relaxation each
     * column seats at the cost-favoured bound, so `coef = -1` maximizes `Σx` (violates the upper cut)
     * and `coef = +1` minimizes it (violates the lower cut).
     */
    private fun cuts(factor: GlobalCardinality, hi: Int, coef: Long): List<Cut> {
        val n = factor.xs.size
        val p = Problem(0, n, Array(n) { IntDomain(0, hi.toLong()) }, arrayOf<Factor>(factor))
        val session = PropagationSession(p)
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = LongArray(n) { coef }))
            .build(session)
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        return GccSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
    }

    private fun lower(factor: GlobalCardinality, hi: Int) = cuts(factor, hi, 1L).single { it.rel == Relation.GE }.rhs
    private fun upper(factor: GlobalCardinality, hi: Int) = cuts(factor, hi, -1L).single { it.rel == Relation.LE }.rhs

    @Test
    fun `cut bounds the sum by the occurrence-capped distribution`() {
        // 6 vars over cover {0,1,2}, each value used in [1,3] times: forced 0+1+2 = 3, then 3 free
        // slots. Each value's residual capacity is high−low = 2, so min fills value 0 (×2) then value 1
        // (×1) = 3+1 = 4; max fills value 2 (×2) then value 1 (×1) = 3+4+1 = 8.
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2, 3, 4, 5),
            cover = longArrayOf(0, 1, 2),
            countLow = intArrayOf(1, 1, 1),
            countHigh = intArrayOf(3, 3, 3),
            closed = true,
        )
        assertEquals(4L, lower(gcc, 2))
        assertEquals(8L, upper(gcc, 2))
    }

    @Test
    fun `open gcc is not separated`() {
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = longArrayOf(1, 2),
            countLow = intArrayOf(0, 0),
            countHigh = intArrayOf(2, 2),
            closed = false,
        )
        assertTrue(cuts(gcc, 2, -1L).isEmpty())
        assertTrue(cuts(gcc, 2, 1L).isEmpty())
    }
}
